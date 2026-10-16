package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.SiteReviewStore
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteReviewDecision
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaSiteReviewStore(private val decisions: SiteReviewDecisionJpaRepository, private val entityManager: EntityManager) : SiteReviewStore {

    override fun history(siteId: MerchantSiteId): List<SiteReviewDecision> = translate(ENTITY) {
        decisions.findBySite(siteId.value).map(EntitlementPersistenceMapper::toDecision)
    }

    override fun append(decision: SiteReviewDecision, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaSiteReviewDecisionEntity.newRow(
                SiteReviewKey.of(decision.merchantSiteId.value, decision.seq), decision.merchantId.value, decision.decision.name, decision.reason.value,
                decision.evidenceRef?.value, decision.decidedAt, decision.decidedBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "merchant_site_review_decision"
    }
}
