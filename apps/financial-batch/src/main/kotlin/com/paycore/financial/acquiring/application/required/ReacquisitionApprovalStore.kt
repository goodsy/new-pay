package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.ReacquisitionApproval
import com.paycore.financial.acquiring.domain.ReacquisitionNotRequired
import java.time.Instant

/**
 * 재매입 2인 승인 기록 Port(D-ACQ-011, CONVENTIONS §14 Store ③: 승인 ID가 있어도 이 모듈 Aggregate가 아닌 감사 기록). append-only. 호출자 TX 필수.
 * ACQ-ADMIN-001 B1: 승인 하나 = 같은 원 파일 레코드 묶음(D-ACQA-003) → 행 키 (승인 ID, 파일, 순번). 각 행은 결정 기록(같은 승인 ID)이 먼저 있어야 한다(FK).
 * runtime INSERT는 V18이 연다(입력 경로 = ADMIN 조작 서명 채널 — D-ACQ-011·D-ACQA-001).
 */
interface ReacquisitionApprovalStore {
    fun find(approvalId: String): List<ReacquisitionApproval>

    /** 같은 거래·종류의 가장 높은 generation(레코드·승인 기록 모두에서). 없으면 null. */
    fun latestGeneration(transactionId: String, recordType: AcquisitionRecordType): Int?

    fun append(approvals: List<ReacquisitionApproval>)

    /** 거래·종류가 "재매입 불요"(D-ACQA-004)로 종결됐는가. */
    fun isNotRequired(transactionId: String, recordType: AcquisitionRecordType): Boolean

    /** "재매입 불요" 종결 기록(거래·종류당 하나 — PK). [decidedAt] = 실행 TX 시각. */
    fun appendNotRequired(records: List<ReacquisitionNotRequired>, decidedAt: Instant)
}
