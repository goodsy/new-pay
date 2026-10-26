package com.paycore.admin.approval.application.required

/**
 * approval 저장 Port의 기술 실패(account `PersistenceFailure`와 같은 관례 — 모듈마다 둔다). adapter가 Port 안에서 flush해 분류한다. 이 예외 뒤 같은 TX는 쓸 수 없다.
 */
sealed class ApprovalPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class DuplicatePending : ApprovalPersistenceFailure("a PENDING request of the same kind and target exists", null)
    class OptimisticConflict : ApprovalPersistenceFailure("concurrent modification of admin_approval_request", null)
    class Deadlock(cause: Throwable) : ApprovalPersistenceFailure("deadlock (transaction rolled back by the database)", cause)
    class LockTimeout(cause: Throwable) : ApprovalPersistenceFailure("lock wait timeout", cause)
    class Unavailable(cause: Throwable) : ApprovalPersistenceFailure("database unavailable", cause)
}
