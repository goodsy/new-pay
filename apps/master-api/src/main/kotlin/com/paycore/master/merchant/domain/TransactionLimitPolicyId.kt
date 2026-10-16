package com.paycore.master.merchant.domain

@JvmInline
value class TransactionLimitPolicyId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "limitPolicyId")
    }

    companion object {
        const val PREFIX = "tlp"
    }
}
