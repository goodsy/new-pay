package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.MERCHANT_LABELS_MAX
import com.paycore.master.merchant.application.provided.MerchantLabel
import com.paycore.master.merchant.application.provided.MerchantLabelQuery
import com.paycore.master.merchant.application.required.MerchantLabelReader
import com.paycore.master.merchant.domain.MerchantId
import org.springframework.stereotype.Service

/** [MerchantLabelQuery](MERCHANT-CODE-001 §5.6.3): 불변식(1..50, 중복 없음) 확인 뒤 읽기 Port에 정렬된 merchantId로 위임한다. 쓰기·잠금·감사 없음. */
@Service
class MerchantLabelQueryService(private val reader: MerchantLabelReader) : MerchantLabelQuery {

    override fun labels(merchantIds: List<MerchantId>): List<MerchantLabel> {
        val ids = merchantIds.map { it.value }
        require(ids.size in 1..MERCHANT_LABELS_MAX && ids.toSet().size == ids.size) { "1..$MERCHANT_LABELS_MAX distinct merchant ids" }
        return reader.labels(ids.sorted())
    }
}
