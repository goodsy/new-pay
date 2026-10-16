package com.paycore.master.merchant.domain

@JvmInline
value class BusinessId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "businessId")
    }

    companion object {
        const val PREFIX = "biz"
    }
}
