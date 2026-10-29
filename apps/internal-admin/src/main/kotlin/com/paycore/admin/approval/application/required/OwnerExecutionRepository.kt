package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.OwnerExecution

/**
 * 소유 서비스 실행 행 저장 Port(ADMIN-PAYMENT-OPS-001 §4.11 `admin_owner_execution`, §14 Aggregate 판정 — 승인 ID로 load/save하고 변경 가능 → `Versioned`).
 * 호출자 TX 안에서만(MANDATORY). 잠금 순서(§4.14): 승인 행 → 계정 행 → **실행 행**.
 * - [insert]: 승인 TX1(§4.13 B — 승인 행 EXECUTING과 같은 TX).
 * - [lock]: `SELECT … FOR UPDATE`(TX2·회복 — 승인 행을 먼저 잠근 뒤).
 * - [update]: 잠근 행의 기대 version과 다르면 [ApprovalPersistenceFailure.OptimisticConflict]. 변경 열만 SET(dirty checking — 저장 본문·지문·기한·실행자는 열 권한 1143).
 * 회복 선점 `claimDue`(`FOR UPDATE SKIP LOCKED`)는 S6에서 더한다.
 */
interface OwnerExecutionRepository {
    fun insert(execution: OwnerExecution): VersionedOwnerExecution

    fun find(approvalId: String): OwnerExecution?

    fun lock(approvalId: String): VersionedOwnerExecution?

    fun update(current: VersionedOwnerExecution, next: OwnerExecution): VersionedOwnerExecution

    /**
     * 회복 선점(§4.16 — native `FOR UPDATE SKIP LOCKED`, 관례 §16): 승인 행 상태 ∈ {EXECUTING, EXECUTION_UNKNOWN} ⋈ 실행 행 `next_attempt_at ≤ now`를 다음 시도 순으로
     * 최대 [limit]행 잠근다(다른 인스턴스가 잡은 행은 건너뛴다). 잠금 순서 승인 행 → 실행 행. 호출자 TX 안에서만.
     */
    fun lockDue(now: java.time.Instant, limit: Int): List<VersionedOwnerExecution>
}

data class VersionedOwnerExecution(val value: OwnerExecution, val version: Long)
