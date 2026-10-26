package com.paycore.admin.account.application.required

/**
 * 저장소 Port의 기술 실패(master-api·payment-api와 같은 관례 — ARCHITECTURE-CONVENTIONS §9). persistence adapter가 Port 안에서 flush해 분류한다.
 * application은 `org.springframework.dao`·JDBC 예외를 모른다. 메시지에 키 값을 넣지 않는다. 이 예외 뒤 같은 TX는 쓸 수 없다(전체 rollback).
 */
sealed class PersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class UniqueConflict(val constraint: StoreConstraint) : PersistenceFailure("unique constraint conflict: $constraint", null)
    class OptimisticConflict(val entity: String) : PersistenceFailure("concurrent modification of $entity", null)
    class Deadlock(cause: Throwable) : PersistenceFailure("deadlock (transaction rolled back by the database)", cause)
    class LockTimeout(cause: Throwable) : PersistenceFailure("lock wait timeout", cause)
    class Unavailable(cause: Throwable) : PersistenceFailure("database unavailable", cause)
}

/** UNIQUE/PK 제약의 의미. [businessKey]가 참이면 업무 유일성(동시 요청 경합 → 새 TX에서 재판정하면 업무 거절로 수렴). */
enum class StoreConstraint(val businessKey: Boolean) {
    LOGIN_NAME(true),
    ACTOR_ID(false),
    ENROLLMENT_CODE(false),
    BOOTSTRAP(false),
    RECOVERY_EPISODE(false),
    OTHER(false),
}
