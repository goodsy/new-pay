package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AttemptState.DISPATCHING
import com.paycore.payment.payment.domain.AttemptState.PREPARED
import java.time.Instant

/** 논리 승인 상태(Payment 요약 상태와 같은 TX에서 함께 바뀐다: OPEN ⇔ AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED). */
enum class AuthorizationStatus { OPEN, UNKNOWN, AUTHORIZED, FAILED }

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
