package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.SnapshotPriority

/**
 * MASTER 운영 지표(명세 §11 Observability, MASTER-DOMAIN-001 review m-7·m-8). label에 MID·merchantId 같은 식별자를 쓰지 않는다
 * (연산 코드·결과 분류만).
 */
interface MasterOperationalMetrics {
    fun adminOutcomeUnknown(operation: String)

    fun adminRetriesExhausted(operation: String)

    fun adminIdentifierCollision(operation: String)

    fun snapshotIssued(priority: SnapshotPriority)

    fun relayResult(result: String)

    /** 같은 revision인데 다시 조립한 내용이 다르다(revision 증가 누락 결함 — PAYMENT는 INTEGRITY_CONFLICT로 막는다). */
    fun revisionHashDrift()

    /** 옛 schemaVersion 발행 뒤 갱신 시점에 revision을 올려 현재 버전으로 다시 발행했다(MASTER-SNAPSHOT-002 §5.2). */
    fun schemaPromotion()
}
