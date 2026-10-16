package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId

interface PolicyVersionRepository {
    fun find(id: PolicyVersionId): PolicyVersion?

    fun findAll(ids: Collection<PolicyVersionId>): List<PolicyVersion>

    fun listByCode(kind: PolicyKind, code: PolicyCode): List<PolicyVersion>

    fun insert(version: PolicyVersion, requestRecordId: String)
}
