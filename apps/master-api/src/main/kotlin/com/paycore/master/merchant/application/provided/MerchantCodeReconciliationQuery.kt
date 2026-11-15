package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MerchantId

/** MERCHANT-CODE-001 MC-1b-6(§9 Reconciliation): 첫 계약 승인 뒤에 코드가 바뀐 가맹점(정상이면 0건 — 운영 대사 점검 질의). 읽기 전용. */
interface MerchantCodeReconciliationQuery {
    fun changesAfterContractApproval(): List<MerchantId>
}
