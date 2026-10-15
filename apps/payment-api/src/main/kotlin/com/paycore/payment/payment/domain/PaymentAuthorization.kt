package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AttemptState.ABANDONED_BEFORE_DISPATCH
import com.paycore.payment.payment.domain.AttemptState.DISPATCHING
import com.paycore.payment.payment.domain.AttemptState.OUTCOME_UNKNOWN
import com.paycore.payment.payment.domain.AttemptState.PREPARED
import com.paycore.payment.payment.domain.AttemptState.RESOLVED
import java.time.Instant
import java.time.LocalDate

/** 논리 승인 상태(Payment 요약 상태와 같은 TX에서 함께 바뀐다: OPEN ⇔ AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED). */
enum class AuthorizationStatus { OPEN, UNKNOWN, AUTHORIZED, FAILED }

/**
 * 기관 요청 ID와 namespace(허용 §5.2: 기관 + 환경 + 기관 계약계정 + operation + 요청 ID). 내부 업무 ID(AttemptId)와 따로 보존한다(시도 §4).
 */
data class ProviderRequestRef(
    val institution: String,
    val environment: Environment,
    val institutionAccount: String,
    val operation: String,
    val requestId: String,
) {
    init {
        require(institution.isNotEmpty() && institutionAccount.isNotEmpty() && operation.isNotEmpty() && requestId.isNotEmpty()) {
            "provider request namespace parts must not be empty"
        }
    }
}

/**
 * 승인 시작 허용 판정의 근거(허용 §4.1 끝 — 판정 입력·권리/정책/Route 버전과 판정 시각을 Attempt에 연결). 값은 application이
 * 허용 판정 Port(로컬 Snapshot)에서 얻는다.
 */
data class AuthorizationAdmission(
    val entitlementVersion: String,
    val policyAssignmentVersion: String,
    val routeProfile: String,
    val protocolVersion: String,
    val evaluatedAt: Instant,
) {
    init {
        require(entitlementVersion.isNotEmpty() && policyAssignmentVersion.isNotEmpty() && routeProfile.isNotEmpty() && protocolVersion.isNotEmpty()) {
            "admission versions must not be empty"
        }
    }
}

/**
 * Attempt dispatch 상태(허용 §5.3-2, AUTH-CONTRACT-001 §5.2). PREPARED에서 DISPATCHING으로 바뀌는 커밋 뒤에만 소켓을 연다 — 그 뒤
 * 실패는 "전송했을 수 있음"이다. 영속 경쟁(전송 vs 회복의 포기)은 같은 행 version 조건부 갱신이 막는다(AUTH-001).
 */
enum class AttemptState { PREPARED, DISPATCHING, ABANDONED_BEFORE_DISPATCH, OUTCOME_UNKNOWN, RESOLVED }

/** 전송 전 포기 원인. 이것만 PRE_TRANSMISSION_FAILURE_CONFIRMED의 출처다(명세 §5.5). */
enum class AbandonCause {
    /** 전송 게이트 거절(차단·lease 만료·Health) — PREPARED에서. */
    GATE_REFUSED,

    /** 회복 Worker가 기한 지난 PREPARED를 fencing으로 포기 — PREPARED에서. */
    FENCED_BY_RECOVERY,

    /** connect 단계 실패로 byte를 보내지 않았고 연결 예산을 소진 — DISPATCHING에서(전송자만 판단). */
    CONNECT_FAILED,
}

/** 결과 불명확 원인(진단용). 어느 것도 무효과 근거가 아니다. */
enum class UnknownCause { TIMEOUT, CONNECTION_LOST, FRAMING, CORRELATION, INTEGRITY, DISPATCH_LEASE_EXPIRED, COMMIT_FAILURE_RECOVERY, INSTITUTION_UNCONFIRMED }

/** 기관 승인 실행 시도(PaymentAuthorization 안의 Entity). 전이는 [PaymentAuthorization]·[AuthorizationResolution]을 거친다. */
class AuthorizationAttempt internal constructor(
    val id: AttemptId,
    val providerRequest: ProviderRequestRef,
    val preparedAt: Instant,
    state: AttemptState,
    abandonCause: AbandonCause?,
    unknownCause: UnknownCause?,
) {
    var state: AttemptState = state
        private set

    var abandonCause: AbandonCause? = abandonCause
        private set

    var unknownCause: UnknownCause? = unknownCause
        private set

    /** 전송했거나 전송 여부가 불명확하다. */
    fun mayHaveBeenSent(): Boolean = state == DISPATCHING || state == OUTCOME_UNKNOWN || state == RESOLVED

    internal fun beginDispatch(owner: AuthorizationId) {
        if (state != PREPARED) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "beginDispatch")
        state = DISPATCHING
    }

    internal fun abandon(owner: AuthorizationId, cause: AbandonCause) {
        val allowed = when (cause) {
            AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY -> state == PREPARED
            AbandonCause.CONNECT_FAILED -> state == DISPATCHING
        }
        if (!allowed) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "abandon($cause)")
        state = ABANDONED_BEFORE_DISPATCH
        abandonCause = cause
    }

    internal fun markOutcomeUnknown(owner: AuthorizationId, cause: UnknownCause) {
        if (state != DISPATCHING) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "markOutcomeUnknown")
        state = OUTCOME_UNKNOWN
        unknownCause = cause
    }

    internal fun resolve(owner: AuthorizationId) {
        if (state != DISPATCHING && state != OUTCOME_UNKNOWN) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "resolve")
        state = RESOLVED
    }

    override fun toString(): String = "AuthorizationAttempt(id=${id.value}, state=$state)"
}

/** 기관 결과 출처(허용 §5.2: 응답·통보·조회·대사 결과의 중복·상충 처리). */
enum class ObservationSource { RESPONSE, INQUIRY, NOTIFICATION, RECONCILIATION }

/**
 * 기관 결과 분류(허용 §5.2). [NO_EFFECT_CONFIRMED]는 기관 프로파일 Capability `confirmsNoEffect`가 있는 기관의 명시적 무효과 확정만
 * Adapter가 이 값으로 바꾼다. [NOT_FOUND]는 무효과가 아니다(시도 §2).
 */
enum class InstitutionOutcome { APPROVED, DECLINED, NO_EFFECT_CONFIRMED, PENDING, UNKNOWN, NOT_FOUND }

/** 기관이 승인한 조건(승인 관찰에만). 승인번호는 전역 UNIQUE로 가정하지 않는다(허용 §5.2). */
data class ApprovedTerms(
    val money: Money,
    val plan: PaymentPlan,
    val cardType: CardType,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: LocalDate,
) {
    init {
        require(approvalNumber.isNotEmpty() && providerTransactionId.isNotEmpty()) { "approval number and provider transaction id must not be empty" }
    }
}

/** [PaymentAuthorization.recordObservation] 결과. */
enum class ObservationRecord { NEW, DUPLICATE, KEY_CONFLICT }

/** 관찰 유일 키(구현 결정 CA-15): 같은 출처의 같은 기관 결과는 한 번만 기록한다. */
data class ObservationKey(val attemptId: AttemptId, val source: ObservationSource, val sourceResultId: String)

/**
 * 기관 결과 한 건(append-only — AUTH-CONTRACT-001 §3·§6). 판정과 무관하게 기록한다. 원문 증거는 [evidenceHash] 참조만(카드번호·CVC·
 * 인증 비밀 없음). 거절·무효과·UNKNOWN·중복 수신은 금융 거래가 아니라 이 관찰로만 남는다(D-AUTH-003).
 */
data class InstitutionObservation(
    val attemptId: AttemptId,
    val source: ObservationSource,
    val sourceResultId: String,
    val outcome: InstitutionOutcome,
    val approved: ApprovedTerms?,
    val evidenceHash: String?,
    val observedAt: Instant,
) {
    init {
        require(sourceResultId.isNotEmpty()) { "sourceResultId must not be empty" }
        require((outcome == InstitutionOutcome.APPROVED) == (approved != null)) { "approved terms exist exactly for APPROVED observations" }
    }

    val key: ObservationKey
        get() = ObservationKey(attemptId, source, sourceResultId)

    /**
     * 기관이 정의한 내용이 같은가(review round2 M-2): attemptId·source·sourceResultId·outcome·approved terms만 비교한다. PG 수신 메타데이터
     * ([observedAt])와 원문 hash([evidenceHash])는 비교하지 않고 증거로만 남긴다 — 원문(전문·통보 본문)에 기관 발송 시각·전송 순번·서명 nonce
     * 같은 수신마다 달라질 수 있는 값이 없다는 보장이 없어서, hash가 다르다는 이유로 같은 결과를 상충으로 오판하지 않게 한다.
     */
    fun sameInstitutionContent(other: InstitutionObservation): Boolean =
        attemptId == other.attemptId && source == other.source && sourceResultId == other.sourceResultId &&
            outcome == other.outcome && approved == other.approved
}

enum class PaymentTransactionType { AUTHORIZATION }

/**
 * 확정 금융 사실(D-AUTH-003, 사용자 확정 2026-10-15): 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건(append-only). 승인 성공 =
 * AUTHORIZATION 거래(취소·부분취소는 CANCEL-CONTRACT-001에서 새 타입). PaymentAuthorized 이벤트 1건 = 이 거래 1건, FINANCIAL 업무 키 = [id].
 * 늦은/상충 승인은 hold 해제 판정 전에는 거래를 만들지 않는다.
 */
data class PaymentTransaction(
    val id: PaymentTransactionId,
    val type: PaymentTransactionType,
    val authorizationId: AuthorizationId,
    val paymentId: PaymentId,
    val terms: ApprovedTerms,
    val sourceObservation: ObservationKey,
    val confirmedAt: Instant,
)

/**
 * Payment의 논리 승인(Aggregate Root, Payment와 1:1 — AUTH-CONTRACT-001 §3·§5.2).
 *
 * 불변식:
 * - 요청 금액·PaymentPlan·인증 cardType·귀속 세션·판정 근거는 생성 시 고정한다(요청 전액 승인만, 시도 §1).
 * - 초기 계약은 Authorization당 dispatch Attempt 1개(CA-13). UNKNOWN에서 새 Attempt를 만들지 않는다.
 * - 관찰은 append-only이고 같은 [ObservationKey]는 한 번만 기록한다. AUTHORIZED·FAILED는 되돌리지 않는다.
 * - AUTHORIZED이면 [transaction](확정 금융 사실)이 정확히 하나다.
 * 상태 전이는 [AuthorizationResolution](Payment·control·한도와 함께)만 한다 — 이 클래스의 전이 메서드는 모듈 internal이다.
 */
class PaymentAuthorization private constructor(
    val id: AuthorizationId,
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val intent: OrderIntent,
    val plan: PaymentPlan,
    val authenticatedCardType: CardType,
    val authenticationSessionId: AuthenticationSessionId,
    val admission: AuthorizationAdmission,
    val planPolicyVersion: String,
    val limitPolicyVersion: String,
    val limitKey: LimitUsageKey,
    val openedAt: Instant,
) {
    var status: AuthorizationStatus = AuthorizationStatus.OPEN
        private set

    private val attemptList = mutableListOf<AuthorizationAttempt>()

    private val observationList = mutableListOf<InstitutionObservation>()

    var transaction: PaymentTransaction? = null
        private set

    var failureEvidence: TerminationEvidence? = null
        private set

    val money: Money
        get() = intent.money

    val attempts: List<AuthorizationAttempt>
        get() = attemptList.toList()

    val observations: List<InstitutionObservation>
        get() = observationList.toList()

    fun attempt(attemptId: AttemptId): AuthorizationAttempt =
        attemptList.firstOrNull { it.id == attemptId } ?: throw IllegalArgumentException("attempt is not part of this authorization")

    /** 기관 승인 조건이 요청과 같은가(금액·통화·Plan·cardType — 부분 승인은 금액 불일치). */
    fun matchesRequest(terms: ApprovedTerms): Boolean = terms.money == money && terms.plan == plan && terms.cardType == authenticatedCardType

    /** 전송 게이트 통과(TXd): PREPARED → DISPATCHING. 이 커밋 뒤에만 기관에 연결한다. */
    fun beginDispatch(attemptId: AttemptId) {
        if (status != AuthorizationStatus.OPEN) throw InvalidAuthorizationTransitionException(id, status.name, "beginDispatch")
        attempt(attemptId).beginDispatch(id)
    }

    /**
     * 같은 [ObservationKey]로 먼저 기록된 관찰과 내용이 다른 관찰(append-only 증거, review m-1). 기관이 같은 결과 식별자로 다른 내용을
     * 보냈다는 뜻이라 상충 사건이다 — 조용히 버리지 않는다.
     */
    val keyConflictingObservations: List<InstitutionObservation>
        get() = keyConflictList.toList()

    private val keyConflictList = mutableListOf<InstitutionObservation>()

    /**
     * 관찰을 기록한다. 새 키면 append(NEW). 같은 키·같은 내용이면 효과 없음(DUPLICATE). 같은 키·다른 내용이면 상충 증거로 따로 append하고
     * KEY_CONFLICT(같은 상충 내용의 재수신은 DUPLICATE).
     */
    internal fun recordObservation(observation: InstitutionObservation): ObservationRecord {
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
        if (status != AuthorizationStatus.OPEN) throw InvalidAuthorizationTransitionException(id, status.name, "markUnknown")
        status = AuthorizationStatus.UNKNOWN
    }

    internal fun authorize(transaction: PaymentTransaction) {
        if (status != AuthorizationStatus.OPEN && status != AuthorizationStatus.UNKNOWN) {
            throw InvalidAuthorizationTransitionException(id, status.name, "authorize")
        }
        require(transaction.authorizationId == id && transaction.paymentId == paymentId) { "transaction must belong to this authorization" }
        require(matchesRequest(transaction.terms)) { "only a full approval matching the request becomes a transaction" }
        this.transaction = transaction
        status = AuthorizationStatus.AUTHORIZED
    }

    internal fun fail(evidence: TerminationEvidence) {
        if (status != AuthorizationStatus.OPEN && status != AuthorizationStatus.UNKNOWN) {
            throw InvalidAuthorizationTransitionException(id, status.name, "fail")
        }
        require(evidence.isSufficientFor(PaymentStatus.FAILED)) { "authorization fails only with sufficient evidence: $evidence" }
        failureEvidence = evidence
        status = AuthorizationStatus.FAILED
    }

    override fun toString(): String = "PaymentAuthorization(id=${id.value}, payment=${paymentId.value}, status=$status)"

    companion object {
        /**
         * 승인 operation을 연다(Attempt PREPARED 하나 포함). main 코드에서는 [AuthorizationStart]만 부른다(ArchUnit) — Payment AUTHORIZING
         * 전이·세션 귀속·한도 점유와 같은 연산·같은 TX1이어야 한다(L-3).
         */
        fun open(
            id: AuthorizationId,
            payment: Payment,
            intent: OrderIntent,
            plan: PaymentPlan,
            authenticatedCardType: CardType,
            authenticationSessionId: AuthenticationSessionId,
            admission: AuthorizationAdmission,
            planPolicyVersion: String,
            limitPolicyVersion: String,
            limitKey: LimitUsageKey,
            attemptId: AttemptId,
            providerRequest: ProviderRequestRef,
            openedAt: Instant,
        ): PaymentAuthorization {
            require(intent.fingerprint() == payment.intent) { "authorization intent must be the payment's fixed intent" }
            require(providerRequest.environment == payment.orderKey.environment) { "provider request environment must match the order" }
            require(planPolicyVersion.isNotEmpty() && limitPolicyVersion.isNotEmpty()) { "policy versions must not be empty" }
            return PaymentAuthorization(
                id, payment.id, payment.orderKey, intent, plan, authenticatedCardType, authenticationSessionId, admission,
                planPolicyVersion, limitPolicyVersion, limitKey, openedAt,
            ).also { it.attemptList += AuthorizationAttempt(attemptId, providerRequest, openedAt, PREPARED, null, null) }
        }
    }
}
