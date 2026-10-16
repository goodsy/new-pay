package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 카드사(매입사)/Route 개통(D-MD-017, D-MS2-004): (MID, 결제수단, 매입사)당 종료 아닌 개통 하나 = Route 하나(대체 Route 없음). 전이 규칙은
 * 수단 개통과 같다(D-MS-003 준용 — D-MS2-004): 등록 UNDER_REVIEW, 승인 ACTIVE(기관 근거 필수), 정지 SUSPENDED(사유), 재개 ACTIVE(이력 전체와 다른
 * 새 근거), 종료 비가역(재개통은 새 행). 권리를 넓히는 전이는 MID ACTIVE일 때만, 줄이는 전이는 언제나.
 * [institutionMerchantRef]는 내부 식별자 등급(D-MS2-005) — [toString]에 넣지 않는다.
 */
class CardRouteActivation private constructor(
    val id: CardRouteActivationId,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
    val acquirerCode: AcquirerCode,
    val routeRef: RouteRef,
    val institutionMerchantRef: InstitutionMerchantRef,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
    status: ActivationStatus,
    evidenceRef: EvidenceRef?,
    statusChangedAt: Instant,
) {
    var status: ActivationStatus = status
        private set

    var evidenceRef: EvidenceRef? = evidenceRef
        private set

    var statusChangedAt: Instant = statusChangedAt
        private set

    val isTerminated: Boolean get() = status == ActivationStatus.TERMINATED

    init {
        require(status != ActivationStatus.ACTIVE || evidenceRef != null) { "an ACTIVE card route carries its institution evidence reference" }
        require(!statusChangedAt.isBefore(registeredAt)) { "status cannot change before registration" }
    }

    fun approve(account: MerchantAccount, evidence: EvidenceRef, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        return transition(ActivationStatus.UNDER_REVIEW, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun suspend(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition =
        transition(ActivationStatus.ACTIVE, ActivationStatus.SUSPENDED, null, reason, at, by)

    /** SUSPENDED → ACTIVE. [pastEvidence]는 이 개통의 전이 이력에 남은 모든 근거 — 그 어느 것과도 달라야 한다(D-MS-003 준용). */
    fun resume(account: MerchantAccount, evidence: EvidenceRef, pastEvidence: Collection<EvidenceRef>, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        if (status == ActivationStatus.SUSPENDED && (evidence == evidenceRef || evidence in pastEvidence)) {
            throw MasterRuleViolation(MasterRejection.CARD_ROUTE_EVIDENCE_NOT_NEW)
        }
        return transition(ActivationStatus.SUSPENDED, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun terminate(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition {
        if (isTerminated) throw MasterRuleViolation(MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED)
        return apply(ActivationTransition(status, ActivationStatus.TERMINATED, null, reason, at, by))
    }

    private fun transition(
        expected: ActivationStatus,
        to: ActivationStatus,
        evidence: EvidenceRef?,
        reason: DecisionReason?,
        at: Instant,
        by: ActorRef,
    ): ActivationTransition {
        if (status != expected) throw MasterRuleViolation(MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED)
        return apply(ActivationTransition(status, to, evidence, reason, at, by))
    }

    private fun apply(t: ActivationTransition): ActivationTransition {
        require(!t.at.isBefore(statusChangedAt)) { "a transition cannot precede the previous one" }
        status = t.to
        if (t.evidenceRef != null) evidenceRef = t.evidenceRef
        statusChangedAt = t.at
        return t
    }

    private fun requireSameAccount(account: MerchantAccount) {
        require(account.id == merchantAccountId) { "card route belongs to another MID" }
    }

    override fun toString(): String =
        "CardRouteActivation(id=${id.value}, method=$paymentMethod, acquirer=${acquirerCode.value}, route=${routeRef.value}, status=$status)"

    companion object {
        /**
         * 새 카드사 개통(UNDER_REVIEW). 수단 개통(MID, 수단)이 종료되지 않은 상태여야 하고(도출 — 수단 개통이 상위 조건), 같은 (MID, 수단, 매입사)의
         * 종료 아닌 개통이 없어야 한다(D-MS2-004). [existing]은 같은 MID의 카드사 개통 전체, [methodActivation]은 그 수단의 종료 아닌 개통.
         */
        @Suppress("LongParameterList")
        fun register(
            id: CardRouteActivationId,
            account: MerchantAccount,
            methodActivation: PaymentMethodActivation?,
            acquirer: AcquirerCode,
            route: RouteRef,
            institutionMerchantRef: InstitutionMerchantRef,
            existing: List<CardRouteActivation>,
            at: Instant,
            by: ActorRef,
        ): Pair<CardRouteActivation, ActivationTransition> {
            require(existing.all { it.merchantAccountId == account.id }) { "existing card routes must belong to the MID" }
            account.requireActive()
            if (methodActivation == null || methodActivation.isTerminated || methodActivation.merchantAccountId != account.id) {
                throw MasterRuleViolation(MasterRejection.CARD_ROUTE_METHOD_NOT_ACTIVATED)
            }
            existing.firstOrNull { !it.isTerminated && it.paymentMethod == methodActivation.paymentMethod && it.acquirerCode == acquirer }?.let {
                throw MasterRuleViolation(MasterRejection.CARD_ROUTE_DUPLICATE, it.id.value)
            }
            val route0 = CardRouteActivation(
                id, account.id, methodActivation.paymentMethod, acquirer, route, institutionMerchantRef, at, by, ActivationStatus.UNDER_REVIEW, null, at,
            )
            return route0 to ActivationTransition(null, ActivationStatus.UNDER_REVIEW, null, null, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: CardRouteActivationId,
            merchantAccountId: MerchantAccountId,
            paymentMethod: PaymentMethod,
            acquirerCode: AcquirerCode,
            routeRef: RouteRef,
            institutionMerchantRef: InstitutionMerchantRef,
            registeredAt: Instant,
            registeredBy: ActorRef,
            status: ActivationStatus,
            evidenceRef: EvidenceRef?,
            statusChangedAt: Instant,
        ) = CardRouteActivation(
            id, merchantAccountId, paymentMethod, acquirerCode, routeRef, institutionMerchantRef, registeredAt, registeredBy, status, evidenceRef,
            statusChangedAt,
        )
    }
}
