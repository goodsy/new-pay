package com.paycore.master.merchant.domain

@JvmInline
value class PolicyVersionId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "policyVersionId")
    }

    companion object {
        const val PREFIX = "pv"
    }
}
