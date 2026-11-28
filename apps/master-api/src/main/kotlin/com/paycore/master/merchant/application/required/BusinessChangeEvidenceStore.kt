package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.EvidenceRef
import java.time.Instant

/**
 * 1인 경로 선택 근거 원문 한 건(BIZ-CRIT-APPROVAL-001 §3.5·R3-m2 — BR-BIZ-391). 그 저장의 변경 이력 행([changeHistoryId])에 연결된다. 화면·이력 응답에는 나가지 않고
 * 별도 조회로만 본다. toString 비노출.
 */
data class BusinessChangeEvidenceRecord(
    val changeHistoryId: Long,
    val businessId: BusinessId,
    val requestRecordId: String,
    val evidenceRef: EvidenceRef,
    val recordedBy: ActorRef,
    val recordedAt: Instant,
) {
    override fun toString(): String = "BusinessChangeEvidenceRecord(changeHistoryId=$changeHistoryId, businessId=${businessId.value})"
}

/** 1인 경로 근거 원문 저장 Port(기록 Store ③ — INSERT 전용, runtime SELECT·INSERT만). 같은 명령 TX 안에서 이력 행 뒤에 쓴다(rollback이면 함께 없음). */
interface BusinessChangeEvidenceStore {
    fun insert(record: BusinessChangeEvidenceRecord)
}
