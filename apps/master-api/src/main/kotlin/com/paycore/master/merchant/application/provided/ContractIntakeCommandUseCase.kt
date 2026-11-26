package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus

/**
 * 계약 접수 건 명령(MERCHANT-FLOW-001 §4B.7 — FLOW-2b, OPERATOR 1인). 각 명령은 MASTER 로컬 TX 하나: `admin_request` 멱등 → 사업자 행 잠금(version 증가 없음) → `lockedNow` →
 * 접수 건(·사업자 계약) 판정 → 새 행·갱신 → `master_change_history`(**Outbox 없음** — §4A.2 M-1) → 결과. 성공 reference = businessId. 거절은 저장·재생(같은 키 = 과거 결과, 다른 내용 = 409).
 * 효력 없음(BR-BIZ-313·321): 권리·Snapshot·Outbox·`entitlement_revision`을 바꾸지 않는다.
 */
interface ContractIntakeCommandUseCase {
    /** 새 접수 건(상담 중). 종류는 잠금 뒤 시스템이 판정하고 [OpenContractIntakeCommand.expectedKind]와 다르면 `CONTRACT_INTAKE_KIND_MISMATCH`(BR-BIZ-325). */
    fun open(command: OpenContractIntakeCommand): AdminCommandResult

    /** 열린 건 전이: 계약서 접수·되돌리기(사유 필수)·계약 불가(사유 필수). 계약 완료는 [complete]. */
    fun move(command: MoveContractIntakeCommand): AdminCommandResult

    /** 계약서 접수 → 계약 완료 + 신규 = 사업자 계약·버전 1 / 변경 = 새 버전·직전 버전 닫기(BR-BIZ-314·324). */
    fun complete(command: CompleteContractIntakeCommand): AdminCommandResult
}

data class OpenContractIntakeCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val expectedKind: ContractIntakeKind,
    val items: ContractIntakeItems,
    val reason: BusinessChangeReason?,
)

data class MoveContractIntakeCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val intakeSeq: Int,
    val expectedVersion: Long,
    val target: ContractIntakeStatus,
    val reason: BusinessChangeReason?,
) {
    init {
        require(intakeSeq >= 1 && expectedVersion >= 0) { "intake sequence starts at 1 and versions are not negative" }
        require(target in MOVE_TARGETS) { "move targets are DOCUMENT_RECEIVED, CONSULTING and REJECTED (completion has its own command)" }
    }

    companion object {
        val MOVE_TARGETS = setOf(ContractIntakeStatus.DOCUMENT_RECEIVED, ContractIntakeStatus.CONSULTING, ContractIntakeStatus.REJECTED)
    }
}

data class CompleteContractIntakeCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val intakeSeq: Int,
    val expectedVersion: Long,
    val document: ContractDocumentRef,
    val terms: BusinessContractTerms,
    val reason: BusinessChangeReason?,
) {
    init {
        require(intakeSeq >= 1 && expectedVersion >= 0) { "intake sequence starts at 1 and versions are not negative" }
    }
}
