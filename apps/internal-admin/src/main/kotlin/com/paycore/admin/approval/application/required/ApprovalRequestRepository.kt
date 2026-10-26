package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.ApprovalRequest
import java.time.Instant

/**
 * 승인 요청 Aggregate 저장 Port(§14 — `JpaApprovalRequestRepository`). 호출자 TX 안에서만(MANDATORY).
 * - [insert]: 같은 (종류, 대상) PENDING이면 생성 열 `pending_key` UNIQUE → [ApprovalPersistenceFailure.DuplicatePending](n-6).
 * - [lock]: `SELECT … FOR UPDATE`(최신 커밋 값). [update]: 잠근 행의 기대 version과 다르면 OptimisticConflict. 생성 불변 열은 바꾸지 않는다(열 권한 1143).
 * - [immutableColumns]: 승인 TX 0단계의 **비잠금** 읽기(종류·대상·부여 역할 — §4.7.3 0단계). 영속성 컨텍스트에 행을 올리지 않는다(스칼라 조회) — 1단계 [lock]이 최신 값을 읽는다.
 * - [dueForExpiry]: 만료 정리 job 후보(PENDING·`expires_at <= now`). 권위 판정은 행마다 짧은 TX에서 잠근 행으로 다시 한다(n-5).
 */
interface ApprovalRequestRepository {
    fun insert(request: ApprovalRequest): VersionedApproval

    fun find(id: String): ApprovalRequest?

    fun lock(id: String): VersionedApproval?

    fun update(current: VersionedApproval, next: ApprovalRequest): VersionedApproval

    fun immutableColumns(id: String): ImmutableApprovalColumns?

    fun dueForExpiry(now: Instant, limit: Int): List<String>
}

data class VersionedApproval(val value: ApprovalRequest, val version: Long)
