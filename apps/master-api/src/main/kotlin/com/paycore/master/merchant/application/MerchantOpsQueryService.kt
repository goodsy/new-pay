package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import com.paycore.master.merchant.application.provided.MerchantHistoryPage
import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.application.required.EntitlementPropagationReader
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MerchantDetailReader
import com.paycore.master.merchant.application.required.MerchantDirectoryReader
import com.paycore.master.merchant.application.required.MerchantHistoryReader
import com.paycore.master.merchant.domain.MerchantId
import org.springframework.stereotype.Service

/**
 * [MerchantOpsQuery](MERCHANT-OPS-001): 읽기 Port(jOOQ adapter.query)에 위임한다. 쓰기·잠금 없음. 상세의 전파 상태는 기존 전파 조회 Port로 따로 읽어 붙인다
 * (상세 스냅샷과 같은 TX가 아니다 — 전파 상태는 ACK·lease 시각 기준의 별도 판정이며 화면에 "별도 읽기"로 표시한다).
 */
@Service
class MerchantOpsQueryService(
    private val directory: MerchantDirectoryReader,
    private val details: MerchantDetailReader,
    private val histories: MerchantHistoryReader,
    private val propagation: EntitlementPropagationReader,
    private val clock: MasterClock,
) : MerchantOpsQuery {

    override fun search(filter: MerchantSearchFilter): MerchantDirectoryPage = directory.search(filter)

    override fun detail(merchantId: MerchantId): MerchantDetailView? =
        details.read(merchantId)?.copy(propagation = propagation.merchant(merchantId, clock.now()))

    override fun history(merchantId: MerchantId, afterSeq: Long): MerchantHistoryPage? {
        require(afterSeq >= 0) { "afterSeq must not be negative" }
        return histories.page(merchantId, afterSeq)
    }
}
