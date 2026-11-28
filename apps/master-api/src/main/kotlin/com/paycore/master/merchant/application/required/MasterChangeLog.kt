package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.domain.ActorRef
import java.time.Instant

/** 변경 이력(append-only 감사). [detail]은 변경 후 값(등록번호는 마스킹 값만, D-MD-008). */
data class ChangeRecord(
    val requestRecordId: String,
    val operation: AdminOperation,
    val aggregateType: String,
    val aggregateId: String,
    val changeType: String,
    val actor: ActorRef,
    val occurredAt: Instant,
    val detail: Map<String, Any?>,
)

interface MasterChangeLog {
    /** 이력 한 행을 append하고(flush) 생성된 행 ID를 돌려준다(BIZ-CRIT-APPROVAL-001 S2 — 1인 근거 원문 행이 이 ID에 연결된다). 다른 호출자는 무시한다. */
    fun append(record: ChangeRecord): Long
}
