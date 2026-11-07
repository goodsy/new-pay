package com.paycore.financial.acquiring.domain

/**
 * 재매입 배지 3상태(ACQ-ADMIN-001 §5.1 L-9 배지 규칙·AC9, I-9 — 표시 분류, 상태를 바꾸지 않는다). 레코드 단계([recordStage]):
 * - 거절로 끝난 레코드(REJECTED·FILE_REJECTED)만 본다. UNKNOWN은 대사 먼저(D-ACQ-014), CONFLICT는 격리, AWAITING·ACCEPTED는 재매입 대상이 아니다.
 * - 그 거래·종류가 어느 세대에서든 매입 확정(표지 — D-ACQ-014) 또는 "재매입 불요" 종결(D-ACQA-004)이면 끝난 일 → 단계 없음.
 * - 더 높은 세대 레코드가 있으면 IN_PROGRESS("재매입 진행 중" — 새 세대가 전송·결과 대기이거나 다시 거절돼 그 세대 파일이 검토 대상), 없으면 CANDIDATE("재매입 검토 대상").
 * 파일 배지([fileBadge]) = CANDIDATE가 하나라도 있으면 CANDIDATE, 아니면 IN_PROGRESS가 있으면 IN_PROGRESS, 아니면 NONE(새 세대 ACCEPTED 뒤 배지가 사라진다 — MINOR-6).
 * 버튼 가능 여부(eligible — 미해결 승인 등)는 이 규칙이 아니라 미리 보기 판정(ReacquisitionExecutionRule.preview)이 정한다(I-8).
 */
object ReacquisitionBadgeRule {
    fun recordStage(status: RecordStatus, accepted: Boolean, notRequired: Boolean, higherGeneration: Boolean): ReacquisitionStage? = when {
        status != RecordStatus.REJECTED && status != RecordStatus.FILE_REJECTED -> null
        accepted || notRequired -> null
        higherGeneration -> ReacquisitionStage.IN_PROGRESS
        else -> ReacquisitionStage.CANDIDATE
    }

    fun fileBadge(stages: Collection<ReacquisitionStage?>): ReacquisitionBadge =
        fileBadge(stages.count { it == ReacquisitionStage.CANDIDATE }, stages.count { it == ReacquisitionStage.IN_PROGRESS })

    /** Reader 집계(파일별 단계 건수)에서 같은 규칙. */
    fun fileBadge(candidates: Int, inProgress: Int): ReacquisitionBadge = when {
        candidates > 0 -> ReacquisitionBadge.CANDIDATE
        inProgress > 0 -> ReacquisitionBadge.IN_PROGRESS
        else -> ReacquisitionBadge.NONE
    }
}

/** 레코드 하나의 재매입 단계. */
enum class ReacquisitionStage { CANDIDATE, IN_PROGRESS }

/** 파일 배지. */
enum class ReacquisitionBadge { CANDIDATE, IN_PROGRESS, NONE }
