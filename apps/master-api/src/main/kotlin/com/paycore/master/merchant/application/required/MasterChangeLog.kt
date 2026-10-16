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
    fun append(record: ChangeRecord)
}
