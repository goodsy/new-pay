package com.paycore.admin.approval.application.required

/**
 * approval 저장 Port의 기술 실패(account `PersistenceFailure`와 같은 관례 — 모듈마다 둔다). adapter가 Port 안에서 flush해 분류한다. 이 예외 뒤 같은 TX는 쓸 수 없다.
 */
sealed class ApprovalPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class DuplicatePending : ApprovalPersistenceFailure("a PENDING request of the same kind and target exists", null)

    /** 같은 Payment에 미해결(PENDING·EXECUTING·EXECUTION_UNKNOWN) 승인이 있다(V5 `unresolved_key` UNIQUE 1062 — ADMIN-PAYMENT-OPS-001 I-14). */
    class DuplicateUnresolved : ApprovalPersistenceFailure("an unresolved approval for the same Payment exists", null)
    /**
     * 같은 MASTER 제안이 이미 다른 승인 요청에 붙어 있다(V17 `uk_admin_business_change_command_proposal` 1062 — BIZ-CRIT-APPROVAL-001 S3 리뷰 MINOR-1). 끝난 요청의 같은 submissionId를 다시 보내
     * MASTER가 같은 제안을 재생한 경우 — 새 제안이 필요하다(새 submissionId). 그 TX는 rollback된다.
     */
    class DuplicateProposal : ApprovalPersistenceFailure("the MASTER proposal is already attached to another approval request", null)

    /** 같은 (kind, approval_id) 대사 발견이 이미 있다(UNIQUE 1062 — 오류 아님, "이미 기록됨"). 그 TX는 rollback된다. */
    class DuplicateFinding : ApprovalPersistenceFailure("the same reconciliation finding is already recorded", null)

    class OptimisticConflict : ApprovalPersistenceFailure("concurrent modification of admin_approval_request", null)
    class Deadlock(cause: Throwable) : ApprovalPersistenceFailure("deadlock (transaction rolled back by the database)", cause)
    class LockTimeout(cause: Throwable) : ApprovalPersistenceFailure("lock wait timeout", cause)
    class Unavailable(cause: Throwable) : ApprovalPersistenceFailure("database unavailable", cause)
}
