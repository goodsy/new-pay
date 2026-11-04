package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantHistoryPage
import com.paycore.master.merchant.domain.MerchantId

/** 가맹점 변경 이력 읽기 Port(MERCHANT-OPS-001 §3.3). 가맹점이 없으면 null. `detail` 열은 읽지 않는다(D-MS2-005). */
interface MerchantHistoryReader {
    fun page(merchantId: MerchantId, afterSeq: Long): MerchantHistoryPage?
}
