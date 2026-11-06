package com.paycore.financial.acquiring.application.provided

import com.paycore.financial.acquiring.domain.ReacquisitionApprovalCommand

/**
 * 재매입 2인 승인 기록(2-G — D-ACQ-011, D-ADMIN-007 양쪽 검사). 명령은 ADMIN 서명 채널로만 받는다: 서명 검증 → 대상 레코드 상태·버전 확인(파일 행 잠금 아래) →
 * 요청자 ≠ 승인자 → append-only 승인 기록(generation + 1, 원 레코드 참조). **ADMIN 서명 채널은 ACQ-ADMIN-001에서 생긴다** — 지금은 검증이 언제나 실패해
 * CHANNEL_NOT_VERIFIED로 끝나고 아무것도 기록하지 않는다(runtime 계정도 승인 표 INSERT 권한이 없다 — V13).
 */
interface RecordReacquisitionApprovalUseCase {
    fun record(command: ReacquisitionApprovalCommand, signature: String): ReacquisitionApprovalResult
}

/** [refusal] = 거절 사유(CHANNEL_NOT_VERIFIED·TARGET_NOT_FOUND 또는 `ReacquisitionRefusal` 이름). */
data class ReacquisitionApprovalResult(val recorded: Boolean, val refusal: String?)
