package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 상세 읽기 Port(BUSINESS-PROFILE-001 §6.4 — 한 읽기 전용 REPEATABLE READ TX). [summary]는 중복 거절 요약(§6.7 — D-BIZ-003, existingSummary)의 근거다.
 * 없으면 null.
 */
interface BusinessDetailReader {
    fun read(businessId: BusinessId): BusinessDetailView?

    fun summary(businessId: BusinessId): BusinessSummary?
}
