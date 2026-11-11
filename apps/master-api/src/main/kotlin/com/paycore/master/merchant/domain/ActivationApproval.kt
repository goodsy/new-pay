package com.paycore.master.merchant.domain

import java.time.Instant

/** 개통 승인 결과: 전이 + 그 MID의 유효 PRIMARY Site 적용 확인(PRIMARY가 없으면 null — 확인 없음 → 결제 차단). */
data class ActivationApprovalResult(val transition: ActivationTransition, val primaryConfirmation: SiteActivationConfirmation?)

/**
 * 개통 승인(domain service, D-MD-016·017·D-MS-003): UNDER_REVIEW → ACTIVE와 **같은 TX**에서 그 MID의 [at] 시각 유효 PRIMARY Site 적용 확인을
 * 기록한다. 유효 PRIMARY = [at]에 열린(ENDED 아님·기간 안) PRIMARY 연결. 없으면 확인을 만들지 않는다(fail-closed — 결제는 확인이 생길 때까지 차단).
 * 호출자는 merchant 행을 잠그고 그 MID의 연결 전체를 넘긴다.
 */
object ActivationApproval {
    @Suppress("LongParameterList")
    fun approve(
        activation: PaymentMethodActivation,
        account: MerchantAccount,
        assignments: List<SiteAssignment>,
        existingConfirmations: List<SiteActivationConfirmation>,
        confirmationId: SiteActivationConfirmationId,
        evidence: EvidenceRef,
        at: Instant,
        by: ActorRef,
        expectedPrimarySiteId: MerchantSiteId? = null,
    ): ActivationApprovalResult {
        require(assignments.all { it.merchantAccountId == account.id }) { "assignments must belong to the activation's MID" }
        val transition = activation.approve(account, evidence, at, by)
        require(existingConfirmations.all { it.activationId == activation.id }) { "existing confirmations must belong to the activation" }
        val primary = assignments.singleOrNull { it.role == SiteAssignmentRole.PRIMARY && it.isOpenAt(at) }
        // MERCHANT-OPS-002 C-45: 근거 경로는 승인자가 본 PRIMARY를 고정한다 — 전이 판정 뒤, 다르거나 없으면 거절(적용 확인이 다른 Site에 기록되지 않게).
        // 근거 없는 seed 경로는 null(검사 없음 — 기존 동작).
        if (expectedPrimarySiteId != null && primary?.merchantSiteId != expectedPrimarySiteId) {
            throw MasterRuleViolation(MasterRejection.PRIMARY_SITE_MISMATCH)
        }
        // review M-1: 그 Site의 유효 확인이 이미 있으면 두 번째를 만들지 않는다(철회 하나로 결제가 막혀야 한다 — 유효 확인은 하나).
        val alreadyConfirmed = primary != null && existingConfirmations.any { it.merchantSiteId == primary.merchantSiteId && it.isValid }
        val confirmation = primary?.takeUnless { alreadyConfirmed }?.let { p ->
            SiteActivationConfirmation.record(
                confirmationId, account, p.merchantSiteId, activation, evidence, at, by,
                existingConfirmations.count { it.merchantSiteId == p.merchantSiteId } + 1,
            )
        }
        return ActivationApprovalResult(transition, confirmation)
    }
}
