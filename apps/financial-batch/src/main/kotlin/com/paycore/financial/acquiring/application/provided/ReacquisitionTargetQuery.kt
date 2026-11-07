package com.paycore.financial.acquiring.application.provided

import java.time.Instant

/**
 * 재매입 대상 미리 보기(ACQ-ADMIN-001 B1 — ADMIN 요청·승인 A 단계의 TX 밖 재조회, **비권위**: 잠그지 않는다, 판정은 실행 TX가 잠금 아래 다시 한다). 실행과 같은 규칙
 * ([com.paycore.financial.acquiring.domain.ReacquisitionExecutionRule])으로 레코드마다 재매입 가능 여부·거절 사유·예상 순액을 계산한다. 카드사 값은 없다(D-ACQV-002).
 * 파일이 없으면 null.
 */
interface ReacquisitionTargetQuery {
    fun targets(fileId: String, seqs: List<Int>): ReacquisitionTargets?
}

data class ReacquisitionTargets(val fileId: String, val items: List<ReacquisitionTargetItem>, val readAt: Instant)

/**
 * [status] = 레코드 결과 상태(RecordStatus 이름), [generation] = 그 레코드 세대, [eligible] = 지금 재매입 요청 가능, [refusal] = 불가 사유(ReacquisitionRefusal 이름),
 * [cancelledAmountMinor] = 지금까지 받은 취소 합(투영 — 비권위), [expectedNetMinor] = 지금 실행한다면의 순액(eligible일 때만 — 표시용, 실행 TX 값이 권위).
 * 순번이 파일에 없으면 그 항목은 TARGET_NOT_FOUND(다른 값 null).
 */
data class ReacquisitionTargetItem(
    val seq: Int,
    val transactionId: String?,
    val recordType: String?,
    val generation: Int?,
    val status: String?,
    val eligible: Boolean,
    val refusal: String?,
    val approvedAmountMinor: Long?,
    val cancelledAmountMinor: Long?,
    val expectedNetMinor: Long?,
)
