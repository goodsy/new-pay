package com.paycore.payment.payment.domain

import java.time.Instant

/** 논리 취소 상태(CANCEL-CONTRACT-001 §4.6). COMPLETED·FAILED는 종료 상태다. */
enum class CancellationStatus { OPEN, UNKNOWN, COMPLETED, FAILED }

/**
 * 취소 범위(구현 결정 CC-04, 시작 시 고정): FULL = 완료·진행 중 반환이 없는 Payment에서 원 승인 전액을 한 번에 취소, PARTIAL = 그 밖의 모든 취소
 * (부분취소 뒤 남은 잔액 전부를 취소하는 마지막 건 포함). 기관 전문의 전체/부분 구분은 Adapter가 변환한다.
 */
enum class CancelScope { FULL, PARTIAL }

/** 운영자 보정 취소의 목적과 근거 참조(D-CANCEL-005, review m-4). */
sealed interface OperatorCancelPurpose {
    val reference: String

    /** 사고·대사 보정: 사고 hold·대사 Case 참조. */
    data class IncidentOrReconCorrection(val caseRef: String) : OperatorCancelPurpose {
        override val reference: String
            get() = caseRef
    }

    /** 가맹점 종료(키 폐기) 뒤 남은 취소 의무: 종료·철회 기록 참조. */
    data class MerchantTerminatedObligation(val terminationRef: String) : OperatorCancelPurpose {
        override val reference: String
            get() = terminationRef
    }
}

/** 취소 요청의 영구 업무 효과 키(D-CANCEL-002 가맹점 — UNIQUE(payment, merchantCancelId), CC-12 운영자 — UNIQUE(approvalId)). 멱등 기록 30일 보존과 무관하다. */
sealed interface CancelRequestKey {
    data class Merchant(val merchantCancelId: MerchantCancelId) : CancelRequestKey

    data class Operator(val approvalId: OperatorApprovalId) : CancelRequestKey
}

/** 취소 개시자(D-CANCEL-002·005, 사용자 확정 2026-10-17). */
sealed interface CancelInitiator {
    val requestKey: CancelRequestKey

    /** 가맹점 API 취소. [merchantCancelId]가 (Payment, merchantCancelId) 업무 효과 키다(D-CANCEL-002). */
    data class Merchant(val merchantCancelId: MerchantCancelId) : CancelInitiator {
        override val requestKey: CancelRequestKey
            get() = CancelRequestKey.Merchant(merchantCancelId)
    }

    /**
     * 운영자 보정 취소(D-CANCEL-005): 사고·대사 보정 또는 가맹점 종료 뒤 취소 의무([purpose])만, 사유·행위자 기록, 2인 승인(요청자 ≠ 승인자, D-ADMIN-003
     * "Critical 해결" 등급). [approvalId]는 영구 업무 효과 키(CC-12), [approvedTargetFingerprint]는 승인한 대상([OperatorCancelTarget])이다. 소유 서비스
     * 재검사(D-ADMIN-007, review m-3)는 [CancelStart]가 업무 거절로 한다 — 그래서 이 값 자체는 생성 시 검증하지 않는다. 역할·Scope 검증은 ADMIN 연동
     * Use Case(CANCEL-001 경계).
     */
    data class Operator(
        val requestedBy: OperatorId,
        val approvedBy: OperatorId,
        val approvalId: OperatorApprovalId,
        val reason: String,
        val purpose: OperatorCancelPurpose,
        val approvedTargetFingerprint: String,
    ) : CancelInitiator {
        override val requestKey: CancelRequestKey
            get() = CancelRequestKey.Operator(approvalId)

        /** 소유 서비스가 다시 검사하는 2인 승인 입력(대상 버전 제외). 통과하면 null. */
        internal fun approvalDefect(): CancelStartRejection? = when {
            requestedBy == approvedBy -> CancelStartRejection.OPERATOR_SAME_PERSON
            reason.isBlank() -> CancelStartRejection.OPERATOR_REASON_MISSING
            purpose.reference.isBlank() -> CancelStartRejection.OPERATOR_BASIS_MISSING
            else -> null
        }
    }
}

/** 새 취소 요청과 기존 취소의 관계(D-CANCEL-002, CC-12). */
enum class CancelRequestMatch {
    /** 같은 업무 키·같은 내용 → 기존 operation 결과(Idempotency-Key가 달라도, 그 취소가 FAILED여도 — 재시도는 새 키). */
    SAME_REQUEST,

    /** 같은 업무 키·다른 내용 → 409 CANCEL_REQUEST_CONFLICT. */
    CONFLICTING_CONTENT,

    /** 다른 취소 요청. */
    DIFFERENT_REQUEST,
}

/**
 * 논리 취소 operation(Aggregate Root, Payment 1:N — CANCEL-CONTRACT-001 §4.1, CC-01). 취소 요청 1건 = 1개.
 *
 * 불변식:
 * - 대상(Payment·Authorization·원 AUTHORIZATION 거래)·취소 Money·범위·개시자·원승인 정책/권리 버전은 생성 시 고정한다. 원승인 정책 버전은 원
 *   Authorization에서 복사하며 현재 정책을 넣을 경로가 없다(K-7, D-CANCEL-008).
 * - 취소 operation당 dispatch Attempt 1개(CC-11). UNKNOWN에서 새 Attempt를 만들지 않는다(K-14).
 * - 관찰은 append-only, 같은 [CancelObservationKey]·같은 기관 내용은 한 번만, 다른 내용은 상충 증거로 따로 남긴다.
 * - COMPLETED ⇔ CANCEL 거래([transaction]) 정확히 하나. FAILED는 충분한 종료 근거(DECLINE·NO_EFFECT·PRE_TRANSMISSION)로만. 종료 상태는 불가역.
 * 생성은 [CancelStart]만, 상태 전이는 domain 안에서만 한다(전이 메서드는 모듈 internal, 우회는 ArchUnit).
 */
class PaymentCancellation private constructor(
    val id: CancellationId,
    val paymentId: PaymentId,
    val authorizationId: AuthorizationId,
    val originalTransactionId: PaymentTransactionId,
    val orderKey: OrderKey,
    val money: Money,
    val scope: CancelScope,
    val initiator: CancelInitiator,
    val originalProviderTransactionId: String,
    val policyAssignmentVersion: String,
    val entitlementVersion: String,
    val openedAt: Instant,
) {
    var status: CancellationStatus = CancellationStatus.OPEN
        private set

    private val attemptList = mutableListOf<CancelAttempt>()

    private val observationList = mutableListOf<CancelObservation>()

    private val keyConflictList = mutableListOf<CancelObservation>()

    var transaction: CancelTransaction? = null
        private set

    var failureEvidence: TerminationEvidence? = null
        private set

    /**
     * 이 취소에 사건(상충·늦은 성공, D-CANCEL-006)이 기록됐다. 그 뒤 들어온 결과로 자동 확정·종료하지 않는다(해소는 수동·2인·재대사). 한 번 켜지면 끄지 않는다
     * (해제 판정은 RECON-CONTRACT-001).
     */
    var incidentRecorded: Boolean = false
        private set

    val attempts: List<CancelAttempt>
        get() = attemptList.toList()

    val observations: List<CancelObservation>
        get() = observationList.toList()

    /** 같은 키로 먼저 기록된 관찰과 내용이 다른 관찰(append-only 상충 증거). */
    val keyConflictingObservations: List<CancelObservation>
        get() = keyConflictList.toList()

    /** 결과가 확정되지 않았다(OPEN·UNKNOWN) — 반환 예약 유지, 같은 Payment의 다음 취소 차단(D-CANCEL-003). */
    fun isInFlight(): Boolean = status == CancellationStatus.OPEN || status == CancellationStatus.UNKNOWN

    fun attempt(attemptId: CancelAttemptId): CancelAttempt =
        attemptList.firstOrNull { it.id == attemptId } ?: throw IllegalArgumentException("attempt is not part of this cancellation")

    /** 기관 취소 조건이 요청과 같은가: 같은 Money(통화 포함)와 같은 원 승인 거래. 부분 성공은 금액 불일치다. */
    fun matchesRequest(terms: CancelConfirmedTerms): Boolean =
        terms.money == money && terms.originalProviderTransactionId == originalProviderTransactionId

    /**
     * 새 취소 요청(대상 Payment·업무 키·Money)과 이 취소의 관계(D-CANCEL-002, CC-12). application은 업무 키 UNIQUE 조회로 찾은 취소에만 부른다. 가맹점 키와
     * 운영자 키는 서로 겹치지 않는다. 같은 키인데 대상 Payment가 다르면 같은 요청으로 보지 않고 충돌로 본다(fail-closed, review m-B — 운영자 approvalId는
     * 전역 키라 다른 Payment에서 찾힐 수 있다).
     */
    fun compareRequest(paymentId: PaymentId, key: CancelRequestKey, requested: Money): CancelRequestMatch = when {
        key != initiator.requestKey -> CancelRequestMatch.DIFFERENT_REQUEST
        paymentId != this.paymentId -> CancelRequestMatch.CONFLICTING_CONTENT
        requested == money -> CancelRequestMatch.SAME_REQUEST
        else -> CancelRequestMatch.CONFLICTING_CONTENT
    }

    /** 전송 게이트 통과(TXd): PREPARED → DISPATCHING. 이 커밋 뒤에만 기관에 연결한다. */
    fun beginDispatch(attemptId: CancelAttemptId) {
        if (status != CancellationStatus.OPEN) throw InvalidCancellationTransitionException(id, status.name, "beginDispatch")
        attempt(attemptId).beginDispatch(id)
    }

    /** 관찰 기록: 새 키 NEW, 같은 키·같은 기관 내용 DUPLICATE, 같은 키·다른 내용 KEY_CONFLICT(상충 증거로 append). */
    internal fun recordObservation(observation: CancelObservation): ObservationRecord {
        attempt(observation.attemptId)
        val first = observationList.firstOrNull { it.key == observation.key }
        return when {
            first == null -> {
                observationList += observation
                ObservationRecord.NEW
            }
            first.sameInstitutionContent(observation) || keyConflictList.any { it.sameInstitutionContent(observation) } -> ObservationRecord.DUPLICATE
            else -> {
                keyConflictList += observation
                ObservationRecord.KEY_CONFLICT
            }
        }
    }

    internal fun markUnknown() {
        if (status != CancellationStatus.OPEN) throw InvalidCancellationTransitionException(id, status.name, "markUnknown")
        status = CancellationStatus.UNKNOWN
    }

    internal fun complete(transaction: CancelTransaction) {
        if (!isInFlight()) throw InvalidCancellationTransitionException(id, status.name, "complete")
        require(transaction.cancellationId == id && transaction.paymentId == paymentId && transaction.authorizationId == authorizationId) {
            "transaction must belong to this cancellation"
        }
        require(transaction.originalTransactionId == originalTransactionId && transaction.scope == scope) { "transaction must keep the original reference and scope" }
        require(matchesRequest(transaction.terms)) { "only an institution cancel matching the request becomes a transaction" }
        this.transaction = transaction
        status = CancellationStatus.COMPLETED
    }

    internal fun fail(evidence: TerminationEvidence) {
        if (!isInFlight()) throw InvalidCancellationTransitionException(id, status.name, "fail")
        require(evidence.isSufficientFor(PaymentStatus.FAILED)) { "a cancellation fails only with sufficient evidence: $evidence" }
        failureEvidence = evidence
        status = CancellationStatus.FAILED
    }

    internal fun recordIncident() {
        incidentRecorded = true
    }

    override fun toString(): String = "PaymentCancellation(id=${id.value}, payment=${paymentId.value}, status=$status, scope=$scope)"

    companion object {
        /** 기관 요청 namespace의 취소 operation 이름(허용 §5.2 — 승인 요청 ID와 같은 namespace에 섞이지 않게 한다, review n-3). */
        const val CANCEL_OPERATION = "CANCEL"

        /**
         * 취소 operation을 연다(Attempt PREPARED 하나 포함). main 코드에서는 [CancelStart]만 부른다(ArchUnit) — 반환 예약과 같은 연산·같은 TX1.
         * 기관 요청은 원 경로(원 승인 Attempt의 기관·환경·기관 계약계정, 허용 §4.3)여야 한다.
         */
        fun open(
            id: CancellationId,
            payment: Payment,
            authorization: PaymentAuthorization,
            money: Money,
            scope: CancelScope,
            initiator: CancelInitiator,
            attemptId: CancelAttemptId,
            providerRequest: ProviderRequestRef,
            openedAt: Instant,
        ): PaymentCancellation {
            val original = checkNotNull(authorization.transaction) { "only a confirmed authorization transaction can be cancelled" }
            require(authorization.paymentId == payment.id && authorization.orderKey == payment.orderKey) { "authorization must belong to the payment" }
            require(money.currency == original.money.currency) { "cancel currency must be the original currency" }
            require(providerRequest.operation == CANCEL_OPERATION) { "a cancellation is sent under the cancel operation" }
            require(initiator !is CancelInitiator.Operator || initiator.approvalDefect() == null) { "an operator cancel needs a valid two-person approval" }
            requireOriginalRoute(authorization, providerRequest)
            return PaymentCancellation(
                id, payment.id, authorization.id, original.id, payment.orderKey, money, scope, initiator,
                original.terms.providerTransactionId, authorization.admission.policyAssignmentVersion, authorization.admission.entitlementVersion, openedAt,
            ).also { it.attemptList += CancelAttempt(attemptId, providerRequest, openedAt, AttemptState.PREPARED, null, null) }
        }

        /** 원 경로 = 확정 결과를 받은(RESOLVED) 승인 Attempt의 기관·환경·기관 계약계정(K-3). */
        internal fun requireOriginalRoute(authorization: PaymentAuthorization, providerRequest: ProviderRequestRef) {
            val route = authorization.attempts.single { it.state == AttemptState.RESOLVED }.providerRequest
            require(
                providerRequest.institution == route.institution && providerRequest.environment == route.environment &&
                    providerRequest.institutionAccount == route.institutionAccount,
            ) { "a cancel must go through the original route" }
        }
    }
}
