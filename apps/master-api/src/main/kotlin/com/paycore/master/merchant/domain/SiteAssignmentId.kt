package com.paycore.master.merchant.domain

@JvmInline
value class SiteAssignmentId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "assignmentId")
    }

    companion object {
        const val PREFIX = "asg"
    }
}
