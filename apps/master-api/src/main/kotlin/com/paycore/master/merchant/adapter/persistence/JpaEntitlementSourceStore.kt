package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.EntitlementSourceStore
import com.paycore.master.merchant.domain.AccountSource
import com.paycore.master.merchant.domain.EntitlementSource
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SiteReviewRules
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Snapshot 조립 입력 읽기(명세 §4.6). 호출자의 MASTER TX·영속성 컨텍스트에서 읽는다 — 같은 TX에서 방금 바꾼 행(관리 Entity)이 그대로 보이고,
 * JPQL은 실행 전에 영속성 컨텍스트를 flush한다(FlushMode AUTO). jOOQ를 쓰지 않는다(JPA와 jOOQ가 저절로 같은 TX라고 가정하지 않는다, 원칙 §4).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementSourceStore(
    private val sites: MerchantSiteJpaRepository,
    private val accounts: MerchantAccountJpaRepository,
    private val assignments: SiteAssignmentJpaRepository,
    private val reviews: SiteReviewDecisionJpaRepository,
    private val contracts: JpaContractRepository,
    private val activations: JpaPaymentMethodActivationRepository,
    private val confirmations: JpaSiteActivationConfirmationRepository,
    private val currencies: JpaCurrencyEnablementRepository,
    private val limits: JpaTransactionLimitPolicyRepository,
    private val policyAssignments: JpaPolicyAssignmentRepository,
    private val policyVersions: JpaPolicyVersionRepository,
    private val routes: JpaCardRouteActivationRepository,
    private val entityManager: EntityManager,
) : EntitlementSourceStore {

    override fun load(merchantId: MerchantId): EntitlementSource? = translate(ENTITY) {
        val accountRows = accounts.findByMerchant(merchantId.value)
        if (accountRows.isEmpty()) return@translate null
        val merchant = MasterPersistenceMapper.toMerchant(
            checkNotNull(entityManager.find(JpaMerchantEntity::class.java, merchantId.value)) { "merchant must exist" },
        )
        val business = MasterPersistenceMapper.toBusiness(
            checkNotNull(entityManager.find(JpaBusinessEntity::class.java, merchant.businessId.value)) { "business must exist" },
        )
        val assignmentsByAccount = assignments.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toAssignment)
            .groupBy { it.merchantAccountId }
        val merchantLimits = limits.listByMerchant(merchantId)
        val reviewStatus = reviews.findByMerchant(merchantId.value).map(EntitlementPersistenceMapper::toDecision)
            .groupBy { it.merchantSiteId }.mapValues { (_, history) -> SiteReviewRules.currentStatus(history) }
        EntitlementSource(
            business = business,
            merchant = merchant,
            sites = sites.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toSite),
            siteReviews = reviewStatus,
            accounts = accountRows.map(MasterPersistenceMapper::toAccount).map { account ->
                val assigned = policyAssignments.listByAccount(account.id)
                AccountSource(
                    account = account,
                    issuingContract = checkNotNull(contracts.find(account.issuingBasis.contractId)) { "issuing contract must exist" },
                    assignments = assignmentsByAccount[account.id].orEmpty(),
                    activations = activations.listByAccount(account.id),
                    confirmations = confirmations.listByAccount(account.id),
                    currencies = currencies.listByAccount(account.id),
                    limitPolicies = merchantLimits,
                    policyAssignments = assigned,
                    policyVersions = policyVersions.findAll(assigned.map { it.policyVersionId }),
                    cardRoutes = routes.listByAccount(account.id),
                )
            },
        )
    }

    private companion object {
        const val ENTITY = "entitlement_source"
    }
}
