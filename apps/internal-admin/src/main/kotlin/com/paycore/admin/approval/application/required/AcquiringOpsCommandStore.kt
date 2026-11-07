package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.ReacquisitionRequestSnapshot

/**
 * 재매입 요청 고정 값 저장 Port(ACQ-ADMIN-001 B1-A `admin_acquiring_ops_command` — INSERT-only, §14 기록 Store: 승인 Aggregate의 생성 불변 부속 기록). 호출자 TX 안에서만(MANDATORY).
 * - [insert]: 승인 행 INSERT와 같은 TX. 같은 승인에 두 번째면 PK 1062(기술 실패 그대로).
 * - [find]: 승인 TX1의 `pinned_target` 비교·실행 본문 재료, 회복 job.
 */
interface AcquiringOpsCommandStore {
    fun insert(command: ReacquisitionRequestSnapshot)

    fun find(approvalId: String): ReacquisitionRequestSnapshot?
}
