package com.paycore.payment.entitlement.application.required

/**
 * 저장 Port 기술 실패(entitlement 모듈 — 다른 모듈의 required 타입을 쓰지 않는다, Modulith 공개 범위). 분류는 payment 모듈과 같은 관례:
 * 충돌(UNIQUE·version)·교착·잠금 timeout·연결 실패. 메시지에 키 값을 넣지 않는다.
 */
sealed class EntitlementPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class UniqueConflict(val constraint: String) : EntitlementPersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : EntitlementPersistenceFailure("concurrent modification of $entity", null)

    /** [vendorCode]·[sqlState]: 진단 값(MySQL 코드·SQLState만, 메시지 원문 없음 — CP-API-001 n-6). */
    class Deadlock(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        EntitlementPersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        EntitlementPersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        EntitlementPersistenceFailure("database unavailable", cause)
}
