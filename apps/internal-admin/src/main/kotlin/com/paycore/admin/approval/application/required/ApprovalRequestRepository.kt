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

    /** 미해결 소유 서비스 승인 수(상태별 — EXECUTING·EXECUTION_UNKNOWN, 지표 `paycore.admin.ops.unresolved{status}`). 비잠금. */
    fun unresolvedCounts(): Map<String, Long>

    /** 가장 오래된 EXECUTION_UNKNOWN 승인의 결정(승인) 시각 — 없으면 null(지표 `unknown.oldest_age_seconds`). 비잠금. */
    fun oldestUnknownDecidedAt(): Instant?

    /**
     * 종료(EXECUTED·OWNER_REJECTED)된 소유 서비스 승인 중 실행 행의 **마지막 응답 시각**(`last_response_at` — 종료를 기록한 응답, decided_at 아님 I-69)이
     * [respondedBefore]보다 이른 것(대사 전체 스캔의 `MISSING_DECISION` 후보 — S7 리뷰 M-1(a), Demo 규모 전제, 운영 규모는 LIVE Gate). 실행 행이 없는 승인은 PAYMENT에 명령을
     * 보낸 적이 없으므로 제외. 비잠금.
     */
    fun terminalOwnerApprovals(respondedBefore: Instant): List<ApprovalRequest>
}

data class VersionedApproval(val value: ApprovalRequest, val version: Long)
