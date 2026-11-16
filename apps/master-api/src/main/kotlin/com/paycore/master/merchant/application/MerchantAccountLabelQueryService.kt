package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.MERCHANT_ACCOUNT_LABELS_MAX
import com.paycore.master.merchant.application.provided.MerchantAccountLabel
import com.paycore.master.merchant.application.provided.MerchantAccountLabelQuery
import com.paycore.master.merchant.application.required.MerchantAccountLabelReader
import com.paycore.master.merchant.domain.MerchantAccountId
import org.springframework.stereotype.Service

/** [MerchantAccountLabelQuery](TXN-LIST-ENRICH-001 §4.2): 불변식(1..50, 중복 없음) 확인 뒤 읽기 Port에 정렬된 MID로 위임한다. 쓰기·잠금 없음. */
@Service
class MerchantAccountLabelQueryService(private val reader: MerchantAccountLabelReader) : MerchantAccountLabelQuery {

    override fun labels(merchantAccountIds: List<MerchantAccountId>): List<MerchantAccountLabel> {
        val ids = merchantAccountIds.map { it.value }
        require(ids.size in 1..MERCHANT_ACCOUNT_LABELS_MAX && ids.toSet().size == ids.size) { "1..$MERCHANT_ACCOUNT_LABELS_MAX distinct merchant account ids" }
        return reader.labels(ids.sorted())
    }
}
