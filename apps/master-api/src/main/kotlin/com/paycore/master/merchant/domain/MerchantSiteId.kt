package com.paycore.master.merchant.domain

/** PG가 발급하는 Site 식별자(PAYMENT의 merchantSiteId와 같은 값). */
@JvmInline
value class MerchantSiteId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantSiteId")
    }

    companion object {
        const val PREFIX = "site"
    }
}
