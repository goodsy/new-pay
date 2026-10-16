package com.paycore.master.merchant.domain

@JvmInline
value class MerchantId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantId")
    }

    companion object {
        const val PREFIX = "mer"
    }
}
