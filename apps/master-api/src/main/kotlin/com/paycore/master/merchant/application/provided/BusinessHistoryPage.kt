package com.paycore.master.merchant.application.provided

import java.time.Instant

/**
 * 사업자 변경 이력 한 쪽(BUSINESS-PROFILE-001 §6.5 — `master_change_history` `aggregate_type = 'Business'`). [nextAfter] = 다음 쪽이 있을 때 마지막 항목의 seq.
 * 값은 **저장 시점에 마스킹된 값 그대로**(§9 — 원문 이력 없음). 옛 등록 경로(`BusinessRegistered`) 행은 사유·변경 항목이 없다(빈 목록).
 */
data class BusinessHistoryPage(val items: List<BusinessHistoryEntry>, val nextAfter: Long?) {
    companion object {
        const val PAGE_SIZE = 20
    }
}

/**
 * [reason] = 중요정보 변경 사유 원문(C-13 — 있을 때만), [changes] = 이력 detail의 `changes` 항목(키 순서 고정 — `field`·`before`·`after`·`ended`·`added`, 행 항목은 마스킹 값).
 */
data class BusinessHistoryEntry(
    val seq: Long,
    val changeType: String,
    val actorRef: String,
    val occurredAt: Instant,
    val reason: String?,
    val changes: List<Map<String, Any?>>,
)
