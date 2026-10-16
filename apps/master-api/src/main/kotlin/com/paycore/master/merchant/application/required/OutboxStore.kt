package com.paycore.master.merchant.application.required

import java.time.Instant

/**
 * MASTER 통합 이벤트(Outbox, 같은 TX로 append — A02 "MASTER 변경 + 설정 이벤트 Outbox는 한 로컬 TX"). 금융 사실이 아니다
 * (eventCategory INTEGRATION_MASTER_REFERENCE). 소비 계약(Snapshot·순서·watermark)은 MASTER-SNAPSHOT-001이 정한다. payload에 등록번호·
 * Secret·개인정보를 넣지 않는다. [effectKey]는 업무 효과당 하나(UNIQUE).
 */
data class OutboxEvent(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val eventCategory: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val causationId: String,
    val effectKey: String,
    val payload: Map<String, Any?>,
)

interface OutboxStore {
    fun append(event: OutboxEvent)
}
