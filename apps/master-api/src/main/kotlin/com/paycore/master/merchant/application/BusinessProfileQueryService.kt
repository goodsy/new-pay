package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.BusinessSearchFilter
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.application.required.BusinessDetailReader
import com.paycore.master.merchant.application.required.BusinessDirectoryReader
import com.paycore.master.merchant.application.required.BusinessHistoryReader
import com.paycore.master.merchant.application.required.BusinessLookupReader
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import org.springframework.stereotype.Service

/**
 * [BusinessProfileQueryUseCase](BUSINESS-PROFILE-001 BIZ-2 — Application Query Service): jOOQ 읽기 Port에 위임한다. 쓰기·잠금·Aggregate 로드 없음(CQRS — 명령 측
 * `BusinessRepository`를 쓰지 않는다). 중복 거절 요약([existingSummary])도 같은 상세 읽기 Port로 TX 밖(명령 commit 뒤)에서 읽는다(m-1 — 실패는 호출자가 503).
 */
@Service
class BusinessProfileQueryService(
    private val directory: BusinessDirectoryReader,
    private val lookups: BusinessLookupReader,
    private val details: BusinessDetailReader,
    private val histories: BusinessHistoryReader,
) : BusinessProfileQueryUseCase {

    override fun search(filter: BusinessSearchFilter): BusinessDirectoryPage = directory.search(filter)

    override fun lookup(number: BusinessRegistrationNumber): List<BusinessSummary> = listOfNotNull(lookups.lookup(number))

    override fun detail(businessId: BusinessId): BusinessDetailView? = details.read(businessId)

    override fun history(businessId: BusinessId, afterSeq: Long): BusinessHistoryPage? {
        require(afterSeq >= 0) { "afterSeq must not be negative" }
        return histories.page(businessId, afterSeq)
    }

    override fun existingSummary(businessId: BusinessId): BusinessSummary? = details.summary(businessId)
}
