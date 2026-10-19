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
 * - AUTHORIZED이면 [transaction](확정 금융 사실)이 정확히 하나다. FAILED에도 RECON이 기록한 늦은 승인 거래가 하나 있을 수 있다(D-CANCEL-011).
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

    var transaction: AuthorizationTransaction? = null
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

    /**
     * 전송 게이트 통과(TXd): PREPARED → DISPATCHING. 이 커밋 뒤에만 기관에 연결한다. 이번 전송의 Route 프로파일(Capability) 버전을 Attempt에 고정한다(D-AUTH1-007 —
     * 회복은 이 버전이 현재 등록부 버전과 같을 때만 무효과 종결을 요청한다).
     */
    fun beginDispatch(attemptId: AttemptId, routeProfileVersion: String) {
        if (status != AuthorizationStatus.OPEN) throw InvalidAuthorizationTransitionException(id, status.name, "beginDispatch")
        attempt(attemptId).beginDispatch(id, routeProfileVersion)
    }

    /**
     * 같은 [ObservationKey]로 먼저 기록된 관찰과 내용이 다른 관찰(append-only 증거, review m-1). 기관이 같은 결과 식별자로 다른 내용을
     * 보냈다는 뜻이라 상충 사건이다 — 조용히 버리지 않는다.
     */
    val keyConflictingObservations: List<InstitutionObservation>
        get() = keyConflictList.toList()

    private val keyConflictList = mutableListOf<InstitutionObservation>()

    /**
     * 기록된 관찰 각각과 그 식별([ObservationRef], 검토 #1 H-1): 첫 관찰은 variant 0, 같은 키의 상충 증거는 그 키 안에서 기록 순서대로 1, 2, …(영속 variant와 같은 규칙).
     */
    val observationRefs: List<Pair<InstitutionObservation, ObservationRef>>
        get() {
            val counts = mutableMapOf<ObservationKey, Int>()
            return observationList.map { it to ObservationRef(it.key, 0) } +
                keyConflictList.map { o -> o to ObservationRef(o.key, counts.merge(o.key, 1, Int::plus)!!) }
        }

    /** 이미 기록된 관찰(같은 키·같은 기관 내용)의 식별. 기록되지 않은 관찰이면 예외. */
    fun refOf(observation: InstitutionObservation): ObservationRef =
        observationRefs.firstOrNull { (o, _) -> o.key == observation.key && o.sameInstitutionContent(observation) }?.second
            ?: throw IllegalArgumentException("observation is not recorded on this authorization")

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

    internal fun authorize(transaction: AuthorizationTransaction) {
        if (status != AuthorizationStatus.OPEN && status != AuthorizationStatus.UNKNOWN) {
            throw InvalidAuthorizationTransitionException(id, status.name, "authorize")
        }
        require(transaction.authorizationId == id && transaction.paymentId == paymentId) { "transaction must belong to this authorization" }
        require(matchesRequest(transaction.terms)) { "only a full approval matching the request becomes a transaction" }
        this.transaction = transaction
        status = AuthorizationStatus.AUTHORIZED
    }

    /**
     * RECON 늦은 승인 거래 기록(D-CANCEL-010·011, 사용자 확정 2026-10-17 — **RECON-CONTRACT-001 인계**, 명세 §14.1): 이중 승인 hold가 유지되는 동안 확정 거절로 끝난
     * 승인 operation(FAILED)에 기관이 확정한 늦은 승인을 거래로 기록한다. 상태는 FAILED 그대로(Payment도 종료 상태 유지), 거래만 생긴다 — 이 거래는 운영자 보정
     * 취소의 원 거래가 된다. 같은 RECON TX에서 PaymentAuthorized를 내는 것은 D-CANCEL-012(AUTH §8.1의 유일한 예외)이며 RECON Use Case 몫이다.
     * 지금 main 호출자는 없다(RECON domain 서비스가 생기면 그것만 부른다 — ArchUnit domainTransitionsOnlyInsideDomain).
     */
    internal fun recordLateApprovalByReconciliation(transaction: AuthorizationTransaction) {
        if (status != AuthorizationStatus.FAILED || this.transaction != null) {
            throw InvalidAuthorizationTransitionException(id, status.name, "recordLateApprovalByReconciliation")
        }
        require(transaction.authorizationId == id && transaction.paymentId == paymentId) { "transaction must belong to this authorization" }
        require(matchesRequest(transaction.terms)) { "only a full approval matching the request becomes a transaction" }
        require(observationList.any { it.key == transaction.sourceObservation && it.approved == transaction.terms }) {
            "the late approval must be a recorded institution observation"
        }
        this.transaction = transaction
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

        /**
         * 영속 복원(persistence Mapper 전용, AUTH-001 명세 §4.11-5). 시험 편의 생성 API가 아니다(Named Fixture는 [open]과 전이를 쓴다). 공식 전이로 만들 수 없는 조합은
         * 거부한다(조용히 고치지 않는다): 상태와 거래·종료 근거의 짝(FAILED + RECON 기록 거래는 D-CANCEL-011로 허용), 거래의 출처 관찰·조건 일치, 관찰 키 유일·Attempt 소속,
         * 키 상충 관찰의 첫 관찰 존재, 동시 실행 Attempt 1개(CA-13), 상태별 Attempt 상태.
         */
        fun reconstitute(
            id: AuthorizationId,
            paymentId: PaymentId,
            orderKey: OrderKey,
            intent: OrderIntent,
            plan: PaymentPlan,
            authenticatedCardType: CardType,
            authenticationSessionId: AuthenticationSessionId,
            admission: AuthorizationAdmission,
            planPolicyVersion: String,
            limitPolicyVersion: String,
            limitKey: LimitUsageKey,
            openedAt: Instant,
            status: AuthorizationStatus,
            attempts: List<AuthorizationAttempt>,
            observations: List<InstitutionObservation>,
            keyConflictingObservations: List<InstitutionObservation>,
            transaction: AuthorizationTransaction?,
            failureEvidence: TerminationEvidence?,
        ): PaymentAuthorization {
            require(planPolicyVersion.isNotEmpty() && limitPolicyVersion.isNotEmpty()) { "policy versions must not be empty" }
            require(attempts.isNotEmpty()) { "an authorization has at least one attempt" }
            require(attempts.map { it.id }.toSet().size == attempts.size) { "attempt ids are unique" }
            val attemptIds = attempts.map { it.id }.toSet()
            require(attempts.count { it.state == PREPARED || it.state == DISPATCHING || it.state == AttemptState.OUTCOME_UNKNOWN } <= 1) {
                "at most one attempt is in flight"
            }
            require(observations.map { it.key }.toSet().size == observations.size) { "observation keys are unique" }
            require((observations + keyConflictingObservations).all { it.attemptId in attemptIds }) { "observations belong to an attempt of this authorization" }
            require(keyConflictingObservations.all { c -> observations.any { it.key == c.key && !it.sameInstitutionContent(c) } }) {
                "a key-conflicting observation needs a different first observation with the same key"
            }
            when (status) {
                AuthorizationStatus.OPEN -> require(attempts.all { it.state == PREPARED || it.state == DISPATCHING }) { "an OPEN authorization has only unsent or dispatching attempts" }
                AuthorizationStatus.UNKNOWN -> require(attempts.none { it.state == PREPARED || it.state == DISPATCHING }) { "an UNKNOWN authorization has no unsent or dispatching attempt" }
                AuthorizationStatus.AUTHORIZED -> require(attempts.any { it.state == AttemptState.RESOLVED }) { "an AUTHORIZED authorization has a resolved attempt" }
                AuthorizationStatus.FAILED -> require(attempts.none { it.state == PREPARED || it.state == DISPATCHING }) { "a FAILED authorization has no attempt in flight" }
            }
            require(status != AuthorizationStatus.AUTHORIZED || transaction != null) { "an AUTHORIZED authorization has its transaction" }
            require(transaction == null || status == AuthorizationStatus.AUTHORIZED || status == AuthorizationStatus.FAILED) {
                "only AUTHORIZED (or a FAILED one recorded by reconciliation) has a transaction"
            }
            require((status == AuthorizationStatus.FAILED) == (failureEvidence != null)) { "failure evidence exists exactly for FAILED" }
            require(failureEvidence == null || failureEvidence.isSufficientFor(PaymentStatus.FAILED)) { "failure evidence must be sufficient for FAILED" }
            val restored = PaymentAuthorization(
                id, paymentId, orderKey, intent, plan, authenticatedCardType, authenticationSessionId, admission, planPolicyVersion, limitPolicyVersion, limitKey, openedAt,
            )
            if (transaction != null) {
                require(transaction.authorizationId == id && transaction.paymentId == paymentId) { "transaction must belong to this authorization" }
                require(restored.matchesRequest(transaction.terms)) { "only a full approval matching the request is a transaction" }
                require(observations.any { it.key == transaction.sourceObservation && it.approved == transaction.terms }) {
                    "the transaction's source observation is recorded with the same terms"
                }
            }
            return restored.also {
                it.status = status
                it.attemptList += attempts
                it.observationList += observations
                it.keyConflictList += keyConflictingObservations
                it.transaction = transaction
                it.failureEvidence = failureEvidence
            }
        }
    }
}
