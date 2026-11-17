package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 변경 이력 읽기 Port(BUSINESS-PROFILE-001 §6.5). 이력 detail에서 `reason`·`changes`만 읽는다(저장된 마스킹 값 그대로). 사업자가 없으면 null.
 */
interface BusinessHistoryReader {
    fun page(businessId: BusinessId, afterSeq: Long): BusinessHistoryPage?
}
