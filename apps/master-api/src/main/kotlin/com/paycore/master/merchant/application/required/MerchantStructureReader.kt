package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantStructureView
import com.paycore.master.merchant.domain.MerchantId

/** Merchant 구조 읽기 Port(CQRS 조회 측). 구현은 jOOQ `adapter.query`(쓰기 금지 — ArchUnit queryAdapterDoesNotWriteWithJooq). */
interface MerchantStructureReader {
    fun read(merchantId: MerchantId): MerchantStructureView?
}
