package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId

interface PolicyAssignmentRepository {
    fun find(id: PolicyAssignmentId): Versioned<PolicyAssignment>?

    fun lock(id: PolicyAssignmentId): Versioned<PolicyAssignment>?

    fun listByAccount(accountId: MerchantAccountId): List<PolicyAssignment>

    fun insert(assignment: PolicyAssignment, requestRecordId: String)

    fun update(assignment: PolicyAssignment, expectedVersion: Long)
}
