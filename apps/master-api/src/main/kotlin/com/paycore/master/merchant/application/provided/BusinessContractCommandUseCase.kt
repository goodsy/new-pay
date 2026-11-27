package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef

/**
 * 사업자 계약 명령(MERCHANT-FLOW-001 §4B.7 — FLOW-2c, OPERATOR 1인): 정지·정지 해제·해지. '사유 및 비고' 필수, 근거 참조 선택(BR-BIZ-339, D-GOV-022 — 세 명령 같은 규칙).
 * 각 명령 = MASTER 로컬 TX 하나(`admin_request` 멱등 → 사업자 행 잠금 → `lockedNow` → 최신 계약 판정 → 사건 INSERT → `master_change_history`, **Outbox 없음**). 성공 reference = businessId.
 * 효력: 새 가맹점 등록 자격만(BR-BIZ-321) — 기존 가맹점의 결제·정산·계약·MID·Snapshot은 바뀌지 않는다.
 */
interface BusinessContractCommandUseCase {
    fun suspend(command: BusinessContractEventCommand): AdminCommandResult

    fun resume(command: BusinessContractEventCommand): AdminCommandResult

    fun terminate(command: BusinessContractEventCommand): AdminCommandResult
}

/** [contractSeq] = 화면이 본 최신 계약 순번(아니면 `BUSINESS_CONTRACT_NOT_FOUND`), [expectedEventCount] = 화면이 본 그 계약의 사건 수(다르면 `BUSINESS_CONTRACT_STATE_CONFLICT`). */
data class BusinessContractEventCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val contractSeq: Int,
    val expectedEventCount: Int,
    val reason: BusinessChangeReason,
    val evidence: ContractDocumentRef?,
) {
    init {
        require(contractSeq >= 1 && expectedEventCount >= 0) { "contract sequence starts at 1 and event counts are not negative" }
    }
}
