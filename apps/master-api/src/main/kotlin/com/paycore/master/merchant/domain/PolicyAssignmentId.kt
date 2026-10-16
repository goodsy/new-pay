package com.paycore.master.merchant.domain

@JvmInline
value class PolicyAssignmentId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "policyAssignmentId")
    }

    companion object {
        const val PREFIX = "pas"
    }
}
