package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.ReacquisitionApproval

/**
 * 재매입 2인 승인 기록 Port(2-G — D-ACQ-011, CONVENTIONS §14 Store ③: 승인 ID가 있어도 이 모듈 Aggregate가 아닌 감사 기록). append-only. 호출자 TX 필수.
 * **이번 작업에서 runtime 계정은 이 표에 INSERT 권한이 없다**(V13 — SELECT만): 입력 경로(ACQ-ADMIN-001)가 생기기 전에는 기록 자체가 DB에서 거부된다.
 */
interface ReacquisitionApprovalStore {
    fun find(approvalId: String): ReacquisitionApproval?

    /** 같은 거래·종류의 가장 높은 generation(레코드·승인 기록 모두에서). 없으면 null. */
    fun latestGeneration(transactionId: String, recordType: AcquisitionRecordType): Int?

    fun append(approval: ReacquisitionApproval)
}
