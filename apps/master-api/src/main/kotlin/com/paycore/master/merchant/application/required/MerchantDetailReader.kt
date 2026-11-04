package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.domain.MerchantId

/**
 * 가맹점 상세 읽기 Port(MERCHANT-OPS-001 §3.2). 구현은 jOOQ `adapter.query` — 모든 문장을 한 읽기 전용 REPEATABLE READ TX에서 읽는다(찢어진 상세 금지).
 * 전파 상태(propagation)는 이 Port가 아니라 [EntitlementPropagationReader]가 채운다(null로 돌려준다).
 */
interface MerchantDetailReader {
    fun read(merchantId: MerchantId): MerchantDetailView?
}
