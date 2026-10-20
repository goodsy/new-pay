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

        /**
         * 소유 서비스가 다시 검사하는 2인 승인 입력(대상 버전 제외). 통과하면 null. 사유 ≤ [MAX_REASON]자·근거 참조 ≤ [MAX_BASIS]자(영속 열과 같은 상한)·올바른 UTF-16 —
         * 넘거나 깨지면 업무 거절이다(mid-review #1 m-3·F-16 — 저장 단계 1406·1366 결함으로 가지 않는다).
         */
        internal fun approvalDefect(): CancelStartRejection? = when {
            requestedBy == approvedBy -> CancelStartRejection.OPERATOR_SAME_PERSON
            reason.isBlank() -> CancelStartRejection.OPERATOR_REASON_MISSING
            reason.length > MAX_REASON || !Utf8Text.isWellFormed(reason) -> CancelStartRejection.OPERATOR_REASON_INVALID
            purpose.reference.isBlank() -> CancelStartRejection.OPERATOR_BASIS_MISSING
            purpose.reference.length > MAX_BASIS || !Utf8Text.isWellFormed(purpose.reference) -> CancelStartRejection.OPERATOR_BASIS_INVALID
            else -> null
        }

        companion object {
            /** 운영자 사유 상한(`payment_cancellation.operator_reason` VARCHAR(500)과 같다 — UTF-16 단위라 열보다 엄격하거나 같다). */
            const val MAX_REASON = 500

            /** 목적 근거 참조 상한(`purpose_ref` VARCHAR(200)). */
            const val MAX_BASIS = 200
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
    /** 취소 시작 때 판정한 원 경로 취소 Capability 버전(Auditability — CANCEL-001 F-2). */
    val capabilityVersion: String,
    val openedAt: Instant,
) {
    init {
        require(capabilityVersion.isNotEmpty()) { "cancel capability version must not be empty" }
    }

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
     * 이 취소에 기록된 사건(상충·늦은 성공, D-CANCEL-006) 수. 사건마다 1씩 오르며 사건 이벤트 effect_key의 순번이다(D-CANCEL1-002 — 영속 `incident_count`).
     * 같은 관찰 재반영(DUPLICATE)은 사건이 아니라 오르지 않는다. 줄지 않는다(해제 판정은 RECON-CONTRACT-001).
     */
    var incidentCount: Int = 0
        private set

    /** 사건이 하나라도 기록됐다 — 그 뒤 들어온 결과로 자동 확정·종료하지 않는다(해소는 수동·2인·재대사, CC-16). */
    val incidentRecorded: Boolean
        get() = incidentCount > 0

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

    /**
     * 전송 게이트 통과(TXd): PREPARED → DISPATCHING + Route 프로파일 버전 고정(D-CANCEL1-004). 이 커밋 뒤에만 기관에 연결한다.
     */
    fun beginDispatch(attemptId: CancelAttemptId, routeProfileVersion: String) {
        if (status != CancellationStatus.OPEN) throw InvalidCancellationTransitionException(id, status.name, "beginDispatch")
        attempt(attemptId).beginDispatch(id, routeProfileVersion)
    }

    /**
     * [observation]이 이미 기록된 관찰(첫 관찰·상충 증거)과 **같은 기관 결정**인가(D-CANCEL1-005·007 사건 동일성): 같은 Attempt ∧ 같은 결과 ∧ 같은 확정 조건(금액·통화·원 승인 PTX·
     * PTX·취소 승인번호·기관 거래일·업무일 — 무시하는 필드 없음, CN-31) ∧ (같은 결정 ID(sourceResultId) ∨ 둘 다 승인이고 같은 PTX). PTX만 같고 조건이 다르면 새 사건이다(fail-closed,
     * CC §4.6). 같은 관찰 키·같은 내용(완전 중복)은 [recordObservation]의 DUPLICATE가 따로 거른다. 기록 전에 부른다.
     */
    internal fun repeatsRecordedDecision(observation: CancelObservation): Boolean =
        (observationList + keyConflictList).any { recorded ->
            recorded.attemptId == observation.attemptId && recorded.outcome == observation.outcome && recorded.confirmed == observation.confirmed && (
                recorded.sourceResultId == observation.sourceResultId ||
                    (observation.outcome == InstitutionOutcome.APPROVED && recorded.confirmed?.providerTransactionId == observation.confirmed?.providerTransactionId)
                )
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

    /** 사건 1건을 기록하고 그 순번(1부터)을 돌려준다(D-CANCEL1-002). */
    internal fun recordIncident(): Int {
        incidentCount = Math.addExact(incidentCount, 1)
        return incidentCount
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
            capabilityVersion: String,
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
                original.terms.providerTransactionId, authorization.admission.policyAssignmentVersion, authorization.admission.entitlementVersion, capabilityVersion, openedAt,
            ).also { it.attemptList += CancelAttempt(attemptId, providerRequest, openedAt, AttemptState.PREPARED, null, null) }
        }

        /**
         * 영속 복원(persistence Mapper 전용 — ArchUnit, CANCEL-001 F-1·CC §14.1 nit 4). 시험 편의 생성 API가 아니다(Named Fixture는 [open]과 공식 전이를 쓴다).
         * 공식 전이로 만들 수 없는 조합은 거부한다(조용히 고치지 않는다):
         * - Attempt 정확히 1개(CC-11). 상태별 Attempt: OPEN = PREPARED·DISPATCHING(사건 없음), UNKNOWN = OUTCOME_UNKNOWN 또는 (사건이 있는 보내지 않은 PREPARED),
         *   COMPLETED = RESOLVED, FAILED = 전송 전 실패 근거면 ABANDONED, 기관 거절·무효과면 RESOLVED.
         * - Attempt 원인·버전 짝: ABANDONED ⇔ 포기 원인, OUTCOME_UNKNOWN ⇒ 불명확 원인(그 원인은 OUTCOME_UNKNOWN·RESOLVED에만), 보낸 Attempt(DISPATCHING·OUTCOME_UNKNOWN·RESOLVED,
         *   connect 실패 포기)에는 Route 프로파일 버전, 보내지 않은 Attempt에는 없음(D-CANCEL1-004).
         * - COMPLETED ⇔ CANCEL 거래(이 취소·원 거래·범위·요청 Money 일치, 출처 관찰이 같은 조건으로 기록됨), FAILED ⇔ 충분한 종료 근거.
         * - 관찰 키 유일·Attempt 소속, 키 상충 관찰은 같은 키의 다른 첫 관찰이 있어야 한다. 사건 수 ≥ 0. 운영자 행은 2인 승인 입력이 유효하다.
         */
        fun reconstitute(
            id: CancellationId,
            paymentId: PaymentId,
            authorizationId: AuthorizationId,
            originalTransactionId: PaymentTransactionId,
            orderKey: OrderKey,
            money: Money,
            scope: CancelScope,
            initiator: CancelInitiator,
            originalProviderTransactionId: String,
            policyAssignmentVersion: String,
            entitlementVersion: String,
            capabilityVersion: String,
            openedAt: Instant,
            status: CancellationStatus,
            attempts: List<CancelAttempt>,
            observations: List<CancelObservation>,
            keyConflictingObservations: List<CancelObservation>,
            transaction: CancelTransaction?,
            failureEvidence: TerminationEvidence?,
            incidentCount: Int,
        ): PaymentCancellation {
            require(attempts.size == 1) { "a cancellation has exactly one attempt" }
            val attempt = attempts.single()
            require(incidentCount >= 0) { "incident count cannot be negative" }
            require(initiator !is CancelInitiator.Operator || initiator.approvalDefect() == null) { "an operator cancel row needs a valid two-person approval" }
            require((attempt.state == AttemptState.ABANDONED_BEFORE_DISPATCH) == (attempt.abandonCause != null)) { "an abandon cause exists exactly for an abandoned attempt" }
            require(attempt.state != AttemptState.OUTCOME_UNKNOWN || attempt.unknownCause != null) { "an unknown attempt records its cause" }
            require(attempt.unknownCause == null || attempt.state == AttemptState.OUTCOME_UNKNOWN || attempt.state == AttemptState.RESOLVED) {
                "an unknown cause belongs to an attempt that became unknown"
            }
            val sent = attempt.state == AttemptState.DISPATCHING || attempt.state == AttemptState.OUTCOME_UNKNOWN || attempt.state == AttemptState.RESOLVED ||
                attempt.abandonCause == AbandonCause.CONNECT_FAILED
            require(sent == (attempt.routeProfileVersion != null)) { "a route profile version exists exactly for a dispatched attempt" }
            when (status) {
                CancellationStatus.OPEN -> require(
                    (attempt.state == AttemptState.PREPARED || attempt.state == AttemptState.DISPATCHING) && incidentCount == 0,
                ) { "an OPEN cancellation has an unsent or dispatching attempt and no incident" }
                CancellationStatus.UNKNOWN -> require(
                    attempt.state == AttemptState.OUTCOME_UNKNOWN || (attempt.state == AttemptState.PREPARED && incidentCount > 0),
                ) { "an UNKNOWN cancellation has an unknown attempt (or a never-sent one with an incident)" }
                CancellationStatus.COMPLETED -> require(attempt.state == AttemptState.RESOLVED) { "a COMPLETED cancellation has a resolved attempt" }
                CancellationStatus.FAILED -> require(
                    if (failureEvidence == TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED) {
                        attempt.state == AttemptState.ABANDONED_BEFORE_DISPATCH
                    } else {
                        attempt.state == AttemptState.RESOLVED
                    },
                ) { "a FAILED cancellation's attempt matches its evidence" }
            }
            require((status == CancellationStatus.FAILED) == (failureEvidence != null)) { "failure evidence exists exactly for FAILED" }
            require(failureEvidence == null || failureEvidence.isSufficientFor(PaymentStatus.FAILED)) { "failure evidence must be sufficient for FAILED" }
            // mid-review #1 n-2: 기관 거절·무효과 근거는 그것을 말한 관찰(첫 관찰)이 있어야 한다.
            when (failureEvidence) {
                TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE ->
                    require(observations.any { it.outcome == InstitutionOutcome.DECLINED }) { "a declined cancellation has its DECLINED observation" }
                TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT ->
                    require(observations.any { it.outcome == InstitutionOutcome.NO_EFFECT_CONFIRMED }) { "a no-effect cancellation has its NO_EFFECT_CONFIRMED observation" }
                else -> Unit
            }
            require((status == CancellationStatus.COMPLETED) == (transaction != null)) { "a cancel transaction exists exactly for COMPLETED" }
            require(observations.map { it.key }.toSet().size == observations.size) { "observation keys are unique" }
            require((observations + keyConflictingObservations).all { it.attemptId == attempt.id }) { "observations belong to the attempt of this cancellation" }
            require(keyConflictingObservations.all { c -> observations.any { it.key == c.key && !it.sameInstitutionContent(c) } }) {
                "a key-conflicting observation needs a different first observation with the same key"
            }
            val restored = PaymentCancellation(
                id, paymentId, authorizationId, originalTransactionId, orderKey, money, scope, initiator,
                originalProviderTransactionId, policyAssignmentVersion, entitlementVersion, capabilityVersion, openedAt,
            )
            if (transaction != null) {
                require(transaction.cancellationId == id && transaction.paymentId == paymentId && transaction.authorizationId == authorizationId) {
                    "transaction must belong to this cancellation"
                }
                require(transaction.originalTransactionId == originalTransactionId && transaction.scope == scope) { "transaction must keep the original reference and scope" }
                require(restored.matchesRequest(transaction.terms)) { "only an institution cancel matching the request is a transaction" }
                require(observations.any { it.key == transaction.sourceObservation && it.outcome == InstitutionOutcome.APPROVED && it.confirmed == transaction.terms }) {
                    "the transaction's source observation is recorded with the same terms"
                }
            }
            return restored.also {
                it.status = status
                it.attemptList += attempt
                it.observationList += observations
                it.keyConflictList += keyConflictingObservations
                it.transaction = transaction
                it.failureEvidence = failureEvidence
                it.incidentCount = incidentCount
            }
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
