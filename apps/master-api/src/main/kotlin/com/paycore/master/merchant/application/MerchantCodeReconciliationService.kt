package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.MerchantCodeReconciliationQuery
import com.paycore.master.merchant.application.required.MerchantCodeChangeAuditReader
import com.paycore.master.merchant.domain.MerchantId
import org.springframework.stereotype.Service

/** MC-1b-6 사후 대사 질의 use case(jOOQ 읽기 Port에 위임 — 쓰기 없음). */
@Service
class MerchantCodeReconciliationService(private val reader: MerchantCodeChangeAuditReader) : MerchantCodeReconciliationQuery {
    override fun changesAfterContractApproval(): List<MerchantId> = reader.changesAfterContractApproval()
}
