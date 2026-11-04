package com.paycore.master.merchant.application.provided

import java.time.Instant

/**
 * 가맹점 변경 이력 한 쪽(MERCHANT-OPS-001 §3.3) — `master_change_history`의 허용 열 투영. **`detail`(기관 가맹점 번호 평문 포함 가능 — D-MS2-005)과 요청 기록 ID는 없다.**
 * [nextCursor]는 다음 쪽이 있을 때 마지막 항목의 [MerchantHistoryEntry.seq].
 */
data class MerchantHistoryPage(val items: List<MerchantHistoryEntry>, val nextCursor: Long?) {
    companion object {
        const val PAGE_SIZE = 50
    }
}

data class MerchantHistoryEntry(
    val seq: Long,
    val occurredAt: Instant,
    val operation: String,
    val aggregateType: String,
    val aggregateId: String,
    val changeType: String,
    val actorRef: String,
)
