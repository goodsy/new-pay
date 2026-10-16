package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 결제수단 개통 상태(D-MD-017): (MID, 결제수단) 단위. 카드사/Route별 사용 가능 목록은 AUTH-CONTRACT-001(기관 기준정보)이다.
 * CreatePayment eligibility는 ACTIVE만 허용으로 본다(MASTER-SNAPSHOT-001 명세 §6 E4).
 */
enum class ActivationStatus { UNDER_REVIEW, ACTIVE, SUSPENDED, TERMINATED }

/** 상태 전이 한 건(append-only 이력 `payment_method_activation_event`의 내용). 등록은 `from = null`. */
data class ActivationTransition(
    val from: ActivationStatus?,
    val to: ActivationStatus,
    val evidenceRef: EvidenceRef?,
    val reason: DecisionReason?,
    val at: Instant,
    val by: ActorRef,
)

/**
 * 결제수단 개통(PaymentMethodActivation). 전이 규칙(D-MS-003, 2026-10-13 사용자 확정):
 * - 등록 → UNDER_REVIEW("등록됨, 개통 아님")
 * - UNDER_REVIEW → ACTIVE: 기관 근거 참조 필수(PRIMARY Site 적용 확인 동시 기록은 [ActivationApproval])
 * - UNDER_REVIEW → TERMINATED: 심사 거절
 * - ACTIVE → SUSPENDED: 사유 필수
 * - SUSPENDED → ACTIVE: **새** 기관 근거 참조 필수(지금 근거와 같은 참조는 거절 — 관례: "새"를 같은 참조 재사용 금지로 해석, fail-closed)
 * - ACTIVE·SUSPENDED → TERMINATED: 비가역. 재개통은 새 Activation 행(같은 (MID, 수단)에 TERMINATED 아닌 행은 하나 — DB 생성 컬럼 UNIQUE)
 * 권리를 넓히는 전이(승인·재개)는 MID가 ACTIVE여야 한다. 권리를 줄이는 전이(정지·종료)는 언제나 허용한다(차단을 막지 않는다).
 */
class PaymentMethodActivation private constructor(
    val id: PaymentMethodActivationId,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
    status: ActivationStatus,
    evidenceRef: EvidenceRef?,
    statusChangedAt: Instant,
) {
    var status: ActivationStatus = status
        private set

    /** 지금 상태의 근거 참조(승인·재개 때 받은 기관 근거). 등록 직후·종료 뒤에는 마지막 근거를 유지하거나 없다. */
    var evidenceRef: EvidenceRef? = evidenceRef
        private set

    var statusChangedAt: Instant = statusChangedAt
        private set

    val isTerminated: Boolean get() = status == ActivationStatus.TERMINATED

    init {
        require(status != ActivationStatus.ACTIVE || evidenceRef != null) { "an ACTIVE activation carries its institution evidence reference" }
        require(!statusChangedAt.isBefore(registeredAt)) { "status cannot change before registration" }
    }

    internal fun approve(account: MerchantAccount, evidence: EvidenceRef, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        return transition(ActivationStatus.UNDER_REVIEW, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun suspend(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition =
        transition(ActivationStatus.ACTIVE, ActivationStatus.SUSPENDED, null, reason, at, by)

    /**
     * SUSPENDED → ACTIVE. [pastEvidence]는 이 Activation 전이 이력(append-only)에 남은 **모든** 근거 참조다 — 재개 근거는 그 어느 것과도 같으면
     * 안 된다(D-MS-003 "새 기관 근거", 관례: 이력 전체 기준 fail-closed — review m-2).
     */
    fun resume(account: MerchantAccount, evidence: EvidenceRef, pastEvidence: Collection<EvidenceRef>, at: Instant, by: ActorRef): ActivationTransition {
        requireSameAccount(account)
        account.requireActive()
        if (status == ActivationStatus.SUSPENDED && (evidence == evidenceRef || evidence in pastEvidence)) {
            throw MasterRuleViolation(MasterRejection.ACTIVATION_EVIDENCE_NOT_NEW)
        }
        return transition(ActivationStatus.SUSPENDED, ActivationStatus.ACTIVE, evidence, null, at, by)
    }

    fun terminate(reason: DecisionReason, at: Instant, by: ActorRef): ActivationTransition {
        if (isTerminated) throw MasterRuleViolation(MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED)
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
        if (status != expected) throw MasterRuleViolation(MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED)
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
        require(account.id == merchantAccountId) { "activation belongs to another MID" }
    }

    override fun toString(): String = "PaymentMethodActivation(id=${id.value}, method=$paymentMethod, status=$status)"

    companion object {
        /** 새 개통 등록(UNDER_REVIEW). 같은 (MID, 수단)의 종료 아닌 기존 행 확인은 호출자가 merchant 잠금 아래에서 한다. */
        fun register(
            id: PaymentMethodActivationId,
            account: MerchantAccount,
            method: PaymentMethod,
            at: Instant,
            by: ActorRef,
        ): Pair<PaymentMethodActivation, ActivationTransition> {
            account.requireActive()
            val activation = PaymentMethodActivation(id, account.id, method, at, by, ActivationStatus.UNDER_REVIEW, null, at)
            return activation to ActivationTransition(null, ActivationStatus.UNDER_REVIEW, null, null, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: PaymentMethodActivationId,
            merchantAccountId: MerchantAccountId,
            paymentMethod: PaymentMethod,
            registeredAt: Instant,
            registeredBy: ActorRef,
            status: ActivationStatus,
            evidenceRef: EvidenceRef?,
            statusChangedAt: Instant,
        ) = PaymentMethodActivation(id, merchantAccountId, paymentMethod, registeredAt, registeredBy, status, evidenceRef, statusChangedAt)
    }
}
