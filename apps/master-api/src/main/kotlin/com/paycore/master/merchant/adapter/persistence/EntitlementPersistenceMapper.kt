package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.application.required.SnapshotIssueRecord
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ConfirmationRevocation
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.CurrencyUnit
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewDecision
import com.paycore.master.merchant.domain.SiteReviewStatus
import com.paycore.master.merchant.domain.SnapshotPriority

/** Domain ↔ 행 변환(MASTER-SNAPSHOT-001 표). 복원은 domain의 공식 복원 경로를 거친다(저장 값이 규칙상 불가능하면 예외). */
internal object EntitlementPersistenceMapper {
    fun toActivation(row: JpaPaymentMethodActivationEntity) = PaymentMethodActivation.reconstitute(
        PaymentMethodActivationId(row.activationId), MerchantAccountId(row.merchantAccountId), PaymentMethod.valueOf(row.paymentMethod),
        row.registeredAt, ActorRef(row.registeredBy), ActivationStatus.valueOf(row.status), row.evidenceRef?.let(::EvidenceRef),
        row.statusChangedAt,
    )

    fun toConfirmation(row: JpaSiteActivationConfirmationEntity, revocation: JpaConfirmationRevocationEntity?) =
        SiteActivationConfirmation.reconstitute(
            SiteActivationConfirmationId(row.confirmationId), MerchantAccountId(row.merchantAccountId), MerchantSiteId(row.merchantSiteId),
            MerchantId(row.merchantId), PaymentMethod.valueOf(row.paymentMethod), PaymentMethodActivationId(row.activationId),
            EvidenceRef(row.evidenceRef), row.confirmedAt, ActorRef(row.confirmedBy), row.confirmationSeq,
            revocation?.let { ConfirmationRevocation(DecisionReason(it.reason), EvidenceRef(it.evidenceRef), it.revokedAt, ActorRef(it.revokedBy)) },
        )

    fun toEnablement(row: JpaCurrencyEnablementEntity, unit: CurrencyUnit) = CurrencyEnablement.reconstitute(
        CurrencyEnablementId(row.enablementId), MerchantAccountId(row.merchantAccountId), unit, EffectivePeriod(row.effectiveFrom, row.effectiveTo),
        EvidenceRef(row.evidenceRef), row.createdAt, ActorRef(row.createdBy), row.endedAt, row.endedBy?.let(::ActorRef),
    )

    fun toUnit(row: JpaCurrencyUnitEntity) = CurrencyUnit(CurrencyCode(row.currencyCode), row.minorUnit)

    fun toDecision(row: JpaSiteReviewDecisionEntity) = SiteReviewDecision(
        MerchantSiteId(row.key.merchantSiteId), MerchantId(row.merchantId), row.key.seq, SiteReviewStatus.valueOf(row.decision),
        DecisionReason(row.reason), row.evidenceRef?.let(::EvidenceRef), row.decidedAt, ActorRef(row.decidedBy),
    )

    fun toIssue(row: JpaSnapshotIssueEntity) = SnapshotIssueRecord(
        MerchantId(row.key.merchantId), row.key.revision, row.key.leaseSeq, row.contentHash, row.issuedAt, row.leaseUntil,
        SnapshotPriority.valueOf(row.priority), row.outboxEventId, row.schemaVersion,
    )
}
