package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.MerchantStructureQuery
import com.paycore.master.merchant.application.provided.MerchantStructureView
import com.paycore.master.merchant.application.required.MerchantStructureReader
import com.paycore.master.merchant.domain.MerchantId
import org.springframework.stereotype.Service

/** 조회 use case: 읽기 Port(jOOQ adapter.query)에 위임한다. 쓰기·잠금 없음(읽기 일관성은 adapter의 읽기 전용 TX가 맡는다). */
@Service
class MerchantStructureQueryService(private val reader: MerchantStructureReader) : MerchantStructureQuery {
    override fun find(merchantId: MerchantId): MerchantStructureView? = reader.read(merchantId)
}
