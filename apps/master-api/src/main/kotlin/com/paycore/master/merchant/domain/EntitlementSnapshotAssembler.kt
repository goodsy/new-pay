package com.paycore.master.merchant.domain

import java.time.Duration
import java.time.Instant

/**
 * Snapshot 조립(domain service, 명세 §4.6). 판정을 하지 않고 MASTER 원본 상태를 계약 모양으로 옮긴다:
 * - 개통: MID의 결제수단마다 TERMINATED가 아닌 Activation(하나 — DB UNIQUE). 없으면(전부 종료) 생략 → PAYMENT 미개통.
 * - Site 적용 확인: 그 현재 Activation에 묶이고 철회되지 않은 확인만(종료된 옛 Activation의 확인은 재개통에 이어지지 않는다).
 * - 차단 기록(D-MD-006)은 MASTER-LIFECYCLE-001 표가 생기기 전까지 false.
 * - 한도 정책: 이 MID 발급 CSA의 CSA 기본 + 이 MID의 Override(지난 구간 포함 — 내용은 원본 상태만의 함수, MS1 §15-1). 해석(Override 우선)은 PAYMENT.
 * - 카드사 개통: 종료 아닌 행만(종료 행은 판정에 쓸 일이 없다).
 */
object EntitlementSnapshotAssembler {
    fun assemble(source: EntitlementSource, issuedAt: Instant, leaseSeq: Long, leaseDuration: Duration): EntitlementSnapshot {
        val merchant = source.merchant
        require(source.business.id == merchant.businessId) { "business must be the merchant's business" }
        require(source.sites.all { it.merchantId == merchant.id }) { "sites must belong to the merchant" }
        require(source.accounts.all { it.account.merchantId == merchant.id }) { "MIDs must belong to the merchant" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "lease duration must be positive" }
        return EntitlementSnapshot(
            merchantId = merchant.id,
            businessId = merchant.businessId,
            revision = merchant.entitlementRevision,
            issuedAt = issuedAt,
            leaseSeq = leaseSeq,
            leaseUntil = issuedAt.plus(leaseDuration),
            party = PartyState(source.business.lifecycle, merchant.lifecycle, businessBlocked = false, merchantBlocked = false),
            sites = source.sites.map { SiteState(it.id, it.lifecycle, source.siteReviews[it.id] ?: SiteReviewStatus.PENDING, it.channel) },
            accounts = source.accounts.map { accountState(it, source.sites) },
        )
    }

    private fun accountState(a: AccountSource, sites: List<MerchantSite>): AccountState {
        val account = a.account
        require(a.issuingContract.id == account.issuingBasis.contractId) { "issuing contract mismatch" }
        val agreement = checkNotNull(a.issuingContract.agreement(account.issuingBasis.agreementId)) { "issuing agreement must exist" }
        val current = a.activations.filter { it.merchantAccountId == account.id && !it.isTerminated }
        require(current.map { it.paymentMethod }.toSet().size == current.size) { "one non-terminated activation per method" }
        val currentIds = current.map { it.id }.toSet()
        return AccountState(
            mid = account.id,
            lifecycle = account.lifecycle,
            blocked = false,
            issuingAgreementId = agreement.id,
            serviceKind = agreement.serviceKind,
            assignments = a.assignments.map {
                AssignmentState(it.id, it.merchantSiteId, it.role, it.status, it.period.from, it.period.to)
            },
            contractWindows = sites.flatMap { ContractWindows.of(a.issuingContract, it) },
            activations = current.map { ActivationState(it.paymentMethod, it.status) },
            siteConfirmations = a.confirmations.filter { it.isValid && it.activationId in currentIds }
                .map { SiteConfirmationState(it.merchantSiteId, it.paymentMethod) }.distinct(),
            currencies = a.currencies.map { CurrencyState(it.unit.code, it.unit.minorUnit, it.period.from, it.period.to) },
            limitPolicies = a.limitPolicies.filter {
                when (val scope = it.scope) {
                    is LimitScope.CsaDefault -> scope.agreementId == agreement.id
                    is LimitScope.MidOverride -> scope.merchantAccountId == account.id
                }
            }.map {
                LimitPolicyState(
                    it.id, it.scope.kind, (it.scope as? LimitScope.CsaDefault)?.agreementId, it.currency, it.perTransaction, it.daily,
                    it.period.from, it.period.to,
                )
            },
            policyAssignments = a.policyAssignments.map { pa ->
                require(pa.merchantAccountId == account.id && pa.agreementId == agreement.id) { "policy assignments must be of this MID and its issuing CSA" }
                val version = checkNotNull(a.policyVersions.singleOrNull { it.id == pa.policyVersionId }) { "assigned policy version must be loaded" }
                PolicyAssignmentState(
                    pa.id, pa.kind, pa.agreementId, pa.currency, version.id, version.code, version.versionNo, version.contentStatus, pa.period.from, pa.period.to,
                )
            },
            cardRoutes = a.cardRoutes.filter { it.merchantAccountId == account.id && !it.isTerminated }.map {
                CardRouteState(it.id, it.paymentMethod, it.acquirerCode, it.routeRef, it.institutionMerchantRef, it.status)
            },
        )
    }
}
