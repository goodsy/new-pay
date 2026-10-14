package com.paycore.master.merchant.domain

import java.time.Instant

/** Site 적용 확인 철회(D-MS-003: append-only 철회 기록 — 확인 참조·사유·근거·행위자). 재확인은 새 확인 행이다. */
data class ConfirmationRevocation(val reason: DecisionReason, val evidenceRef: EvidenceRef, val revokedAt: Instant, val revokedBy: ActorRef)

/**
 * Site별 개통 적용 확인(D-MD-016, append-only): (MID, Site, 결제수단, 기관 근거 참조, 행위자). 결제 허용 = Assignment 유효 ∧ Activation 유효
 * ∧ 해당 Site 적용 확인 있음. 확인은 특정 Activation 행에 묶인다 — 종료 후 새 Activation(재개통)은 새 확인이 필요하다. Activation 재개
 * (SUSPENDED → ACTIVE) 때 기존 확인은 유지된다(D-MS-003).
 */
class SiteActivationConfirmation private constructor(
    val id: SiteActivationConfirmationId,
    val merchantAccountId: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val merchantId: MerchantId,
    val paymentMethod: PaymentMethod,
    val activationId: PaymentMethodActivationId,
    val evidenceRef: EvidenceRef,
    val confirmedAt: Instant,
    val confirmedBy: ActorRef,
    /** 같은 (Activation, Site) 안의 확인 순번(1부터). 앞선 확인이 모두 철회돼야 다음 순번이 생긴다 — DB UNIQUE(activation, site, seq)가 경합을 막는다. */
    val seq: Int,
    revocation: ConfirmationRevocation?,
) {
    var revocation: ConfirmationRevocation? = revocation
        private set

    val isValid: Boolean get() = revocation == null

    /** 철회 기록(한 번만). 철회는 권리를 줄이므로 MID·Activation 상태와 무관하게 허용한다. */
    fun revoke(reason: DecisionReason, evidence: EvidenceRef, at: Instant, by: ActorRef): ConfirmationRevocation {
        if (revocation != null) throw MasterRuleViolation(MasterRejection.SITE_CONFIRMATION_ALREADY_REVOKED)
        require(!at.isBefore(confirmedAt)) { "a revocation cannot precede the confirmation" }
        return ConfirmationRevocation(reason, evidence, at, by).also { revocation = it }
    }

    override fun toString(): String =
        "SiteActivationConfirmation(id=${id.value}, mid=${merchantAccountId.value}, site=${merchantSiteId.value}, valid=$isValid)"

    companion object {
        internal fun record(
            id: SiteActivationConfirmationId,
            account: MerchantAccount,
            siteId: MerchantSiteId,
            activation: PaymentMethodActivation,
            evidence: EvidenceRef,
            at: Instant,
            by: ActorRef,
            seq: Int,
        ): SiteActivationConfirmation {
            require(activation.merchantAccountId == account.id) { "activation belongs to another MID" }
            require(seq >= 1) { "confirmation sequence starts at 1" }
            return SiteActivationConfirmation(id, account.id, siteId, account.merchantId, activation.paymentMethod, activation.id, evidence, at, by, seq, null)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: SiteActivationConfirmationId,
            merchantAccountId: MerchantAccountId,
            merchantSiteId: MerchantSiteId,
            merchantId: MerchantId,
            paymentMethod: PaymentMethod,
            activationId: PaymentMethodActivationId,
            evidenceRef: EvidenceRef,
            confirmedAt: Instant,
            confirmedBy: ActorRef,
            seq: Int,
            revocation: ConfirmationRevocation?,
        ) = SiteActivationConfirmation(
            id, merchantAccountId, merchantSiteId, merchantId, paymentMethod, activationId, evidenceRef, confirmedAt, confirmedBy, seq, revocation,
        )
    }
}

/**
 * Site 적용 확인 명령 규칙(MASTER-SNAPSHOT-001 명세 §4.2): 그 (MID, Site) 연결이 끝나지 않았고(ENDED 아님·종료 시각 전 — 미래 시작 포함),
 * Activation이 TERMINATED가 아니며, 같은 (Activation, Site)의 유효 확인이 없을 때만. 호출자는 merchant 행을 잠그고 그 MID의 연결과
 * 그 Activation의 확인 전체를 넘긴다(기간·중복 판정을 DB 제약만으로 표현할 수 없다).
 */
object SiteConfirmationRules {
    @Suppress("LongParameterList")
    fun confirm(
        id: SiteActivationConfirmationId,
        account: MerchantAccount,
        site: MerchantSite,
        activation: PaymentMethodActivation,
        assignments: List<SiteAssignment>,
        existing: List<SiteActivationConfirmation>,
        evidence: EvidenceRef,
        at: Instant,
        by: ActorRef,
    ): SiteActivationConfirmation {
        require(assignments.all { it.merchantAccountId == account.id }) { "assignments must belong to the MID" }
        require(existing.all { it.activationId == activation.id }) { "existing confirmations must belong to the activation" }
        if (site.merchantId != account.merchantId) throw MasterRuleViolation(MasterRejection.SITE_OF_OTHER_MERCHANT)
        account.requireActive()
        site.requireActive()
        if (activation.isTerminated) throw MasterRuleViolation(MasterRejection.ACTIVATION_TERMINATED)
        // review M-1: PRIMARY Site의 확인은 개통 승인이 함께 기록한다(명세 §4.2) — 승인 전 별도 확인은 승인 뒤 두 번째 유효 확인을 만든다.
        val isPrimarySite = assignments.any {
            it.merchantSiteId == site.id && it.role == SiteAssignmentRole.PRIMARY && it.status != SiteAssignmentStatus.ENDED && !it.period.hasEndedAt(at)
        }
        if (activation.status == ActivationStatus.UNDER_REVIEW && isPrimarySite) {
            throw MasterRuleViolation(MasterRejection.PRIMARY_CONFIRMED_BY_APPROVAL)
        }
        val open = assignments.any { it.merchantSiteId == site.id && it.status != SiteAssignmentStatus.ENDED && !it.period.hasEndedAt(at) }
        if (!open) throw MasterRuleViolation(MasterRejection.ASSIGNMENT_NOT_OPEN)
        existing.firstOrNull { it.merchantSiteId == site.id && it.isValid }?.let {
            throw MasterRuleViolation(MasterRejection.SITE_CONFIRMATION_DUPLICATE, it.id.value)
        }
        return SiteActivationConfirmation.record(id, account, site.id, activation, evidence, at, by, existing.count { it.merchantSiteId == site.id } + 1)
    }
}
