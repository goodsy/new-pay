package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractRevision
import com.paycore.master.merchant.domain.ContractScope
import com.paycore.master.merchant.domain.ContractServiceAgreement
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.IssuingBasis
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeAssignment
import com.paycore.master.merchant.domain.MerchantCodeSource
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.RevisionApproval
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteAssignment
import com.paycore.master.merchant.domain.SiteAssignmentId
import com.paycore.master.merchant.domain.SiteAssignmentRole
import com.paycore.master.merchant.domain.SiteAssignmentStatus
import com.paycore.master.merchant.domain.SiteChannel

/**
 * Domain ↔ 행 변환(adapter 전용). 복원은 domain의 공식 복원 경로(`reconstitute`)와 값 객체 생성자를 거치므로 저장 값이 현재 규칙에서 존재할 수
 * 없으면(형식 축소 등) 조용히 건너뛰지 않고 예외로 드러난다.
 */
internal object MasterPersistenceMapper {

    fun toBusiness(row: JpaBusinessEntity) = Business.reconstitute(
        BusinessId(row.businessId), BusinessRegistrationNumber(row.registrationNumber), DisplayName(row.displayName),
        Lifecycle.valueOf(row.lifecycle), row.createdAt, ActorRef(row.createdBy),
    )

    fun businessRow(b: Business) = JpaBusinessEntity.newRow(
        b.id.value, b.registrationNumber.value, b.displayName.value, b.lifecycle.name, b.createdAt, b.createdBy.value,
    )

    /** MERCHANT-CODE-001: 배정 행(seq 오름차순)을 함께 받아 조립한다 — 배정 없음은 Domain 불변식 위반(결함 신호, IllegalState). */
    fun toMerchant(row: JpaMerchantEntity, assignments: List<JpaMerchantCodeAssignmentEntity>) = Merchant.reconstitute(
        MerchantId(row.merchantId), BusinessId(row.businessId), DisplayName(row.displayName), Lifecycle.valueOf(row.lifecycle),
        row.entitlementRevision, row.createdAt, ActorRef(row.createdBy),
        assignments.map(::toCodeAssignment),
    )

    fun toCodeAssignment(row: JpaMerchantCodeAssignmentEntity) = MerchantCodeAssignment.reconstitute(
        row.assignmentSeq, MerchantCode(row.merchantCode), row.replacesCode?.let { MerchantCode(it) },
        MerchantCodeSource.valueOf(row.source), row.approvalId, row.reasonCode, row.assignedAt, ActorRef(row.assignedBy),
    )

    fun codeAssignmentRow(merchantId: MerchantId, a: MerchantCodeAssignment) = JpaMerchantCodeAssignmentEntity.newRow(
        merchantId.value, a.seq, a.code.value, a.replacesCode?.value, a.source.name, a.approvalId, a.reasonCode, a.assignedAt, a.assignedBy.value,
    )

    fun merchantRow(m: Merchant) = JpaMerchantEntity.newRow(
        m.id.value, m.businessId.value, m.displayName.value, m.lifecycle.name, m.entitlementRevision, m.createdAt, m.createdBy.value,
    )

    fun toSite(row: JpaMerchantSiteEntity) = MerchantSite.reconstitute(
        MerchantSiteId(row.merchantSiteId), MerchantId(row.merchantId), SiteChannel(ChannelType.valueOf(row.channelType), row.channelIdentifier),
        Lifecycle.valueOf(row.lifecycle), row.createdAt, ActorRef(row.createdBy),
    )

    fun siteRow(s: MerchantSite) = JpaMerchantSiteEntity.newRow(
        s.id.value, s.merchantId.value, s.channel.type.name, s.channel.identifier, s.lifecycle.name, s.createdAt, s.createdBy.value,
    )

    fun toContract(
        row: JpaContractEntity,
        revisions: List<JpaContractRevisionEntity>,
        scopeSites: List<JpaContractRevisionScopeSiteEntity>,
        approvals: List<JpaContractRevisionApprovalEntity>,
        agreements: List<JpaContractServiceAgreementEntity>,
    ): Contract {
        val sitesByRevision = scopeSites.groupBy { it.key.revisionNo }
        val approvalByRevision = approvals.associateBy { it.key.revisionNo }
        return Contract.reconstitute(
            ContractId(row.contractId),
            BusinessId(row.businessId),
            MerchantId(row.merchantId),
            revisions.map { r ->
                require(r.businessId == row.businessId) { "revision party must be the contract party" }
                val merchantId = MerchantId(r.scopeMerchantId)
                val listed = sitesByRevision[r.key.revisionNo].orEmpty()
                require(listed.all { it.merchantId == r.scopeMerchantId }) { "scope sites belong to the scope merchant" }
                val scope = when (r.scopeKind) {
                    SCOPE_MERCHANT -> {
                        require(listed.isEmpty()) { "a MERCHANT scope stores no sites" }
                        ContractScope.MerchantWide(merchantId)
                    }
                    SCOPE_SITES -> ContractScope.Sites(merchantId, listed.map { MerchantSiteId(it.key.merchantSiteId) }.toSet())
                    else -> error("unknown scope kind")
                }
                ContractRevision.reconstitute(
                    r.key.revisionNo, scope, EffectivePeriod(r.effectiveFrom, r.effectiveTo), r.registeredAt, ActorRef(r.registeredBy),
                    approvalByRevision[r.key.revisionNo]?.let { RevisionApproval(it.approvedAt, ActorRef(it.approvedBy)) },
                )
            },
            agreements.map {
                ContractServiceAgreement(ContractServiceAgreementId(it.agreementId), ServiceKind.valueOf(it.serviceKind), it.addedAt, ActorRef(it.addedBy))
            },
            row.createdAt,
            ActorRef(row.createdBy),
        )
    }

    fun revisionRow(contract: Contract, revision: ContractRevision) = JpaContractRevisionEntity.newRow(
        RevisionKey.of(contract.id.value, revision.revisionNo), contract.businessId.value,
        if (revision.scope is ContractScope.Sites) SCOPE_SITES else SCOPE_MERCHANT, revision.scope.merchantId.value,
        revision.effectivePeriod.from, revision.effectivePeriod.to, revision.registeredAt, revision.registeredBy.value,
    )

    fun scopeSiteRows(contract: Contract, revision: ContractRevision): List<JpaContractRevisionScopeSiteEntity> =
        when (val scope = revision.scope) {
            is ContractScope.MerchantWide -> emptyList()
            is ContractScope.Sites -> scope.siteIds.sortedBy { it.value }.map {
                JpaContractRevisionScopeSiteEntity.newRow(ScopeSiteKey.of(contract.id.value, revision.revisionNo, it.value), scope.merchantId.value)
            }
        }

    fun toAccount(row: JpaMerchantAccountEntity) = MerchantAccount.reconstitute(
        MerchantAccountId(row.merchantAccountId), MerchantId(row.merchantId), BusinessId(row.businessId),
        IssuingBasis(ContractId(row.issuingContractId), ContractServiceAgreementId(row.issuingAgreementId), row.issuingRevisionNo),
        Lifecycle.valueOf(row.lifecycle), row.issuedAt, ActorRef(row.issuedBy),
    )

    fun accountRow(a: MerchantAccount) = JpaMerchantAccountEntity.newRow(
        a.id.value, a.merchantId.value, a.businessId.value, a.issuingBasis.contractId.value, a.issuingBasis.agreementId.value,
        a.issuingBasis.revisionNo, a.lifecycle.name, a.issuedAt, a.issuedBy.value,
    )

    fun toAssignment(row: JpaSiteAssignmentEntity) = SiteAssignment.reconstitute(
        SiteAssignmentId(row.assignmentId), MerchantAccountId(row.merchantAccountId), MerchantSiteId(row.merchantSiteId),
        MerchantId(row.merchantId), SiteAssignmentRole.valueOf(row.role), SiteAssignmentStatus.valueOf(row.status),
        EffectivePeriod(row.effectiveFrom, row.effectiveTo), row.createdAt, ActorRef(row.createdBy),
    )

    fun assignmentRow(a: SiteAssignment) = JpaSiteAssignmentEntity.newRow(
        a.id.value, a.merchantAccountId.value, a.merchantSiteId.value, a.merchantId.value, a.role.name, a.status.name,
        a.period.from, a.period.to, a.createdAt, a.createdBy.value,
    )

    const val SCOPE_MERCHANT = "MERCHANT"
    const val SCOPE_SITES = "SITES"
}
