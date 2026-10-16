package com.paycore.master.merchant.domain

/**
 * 계약 Scope(초기: Merchant 또는 MerchantSite, D-MD-004: MERCHANT 또는 SITES(1개 이상)). GID 상속 없음.
 * SITES의 Site는 모두 [merchantId] 소속이다(D-MD-010: 한 Scope = 한 Merchant). 한 계약의 모든 Revision은 같은 Merchant 대상이다(D-MD-013).
 */
sealed interface ContractScope {
    val merchantId: MerchantId

    fun includes(site: MerchantSite): Boolean

    data class MerchantWide(override val merchantId: MerchantId) : ContractScope {
        override fun includes(site: MerchantSite): Boolean = site.merchantId == merchantId
    }

    data class Sites(override val merchantId: MerchantId, val siteIds: Set<MerchantSiteId>) : ContractScope {
        init {
            require(siteIds.isNotEmpty()) { "a SITES scope lists at least one site" }
        }

        override fun includes(site: MerchantSite): Boolean = site.merchantId == merchantId && site.id in siteIds
    }

    companion object {
        /**
         * Scope 검증: Scope Merchant는 계약 당사자 Business 소속이고 활성, SITES의 Site는 모두 그 Merchant 소속이고 활성이다.
         * [sites]는 Scope가 나열한 Site들(MERCHANT Scope면 비어 있다).
         */
        fun requireValid(scope: ContractScope, party: BusinessId, merchant: Merchant, sites: List<MerchantSite>) {
            require(merchant.id == scope.merchantId) { "scope merchant mismatch" }
            if (merchant.businessId != party) throw MasterRuleViolation(MasterRejection.SCOPE_MERCHANT_OF_OTHER_BUSINESS)
            merchant.requireActive()
            when (scope) {
                is MerchantWide -> require(sites.isEmpty()) { "a MERCHANT scope lists no sites" }
                is Sites -> {
                    require(sites.map { it.id }.toSet() == scope.siteIds) { "scope sites must be provided" }
                    sites.forEach { site ->
                        if (site.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.SCOPE_SITE_OF_OTHER_MERCHANT)
                        site.requireActive()
                    }
                }
            }
        }
    }
}
