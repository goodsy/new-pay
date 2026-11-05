package com.paycore.financial.paymentfact.application.required

/**
 * 저장 Port 기술 실패(payment-api 모듈들과 같은 관례 — 충돌·교착·잠금 timeout·연결 실패). 이 예외가 나면 같은 TX는 더 쓸 수 없다(JPA flush 실패는 TX를
 * rollback 전용으로 만든다). 다시 시도하면 새 TX에서 처음부터 같은 의도로 한다. 메시지에 키 값을 넣지 않는다(MySQL 중복 오류 원문에는 값이 들어 있다).
 */
sealed class PaymentFactPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /** UNIQUE/PK 위반. [constraint] = 제약 이름(값 없음). 원 예외를 붙이지 않는다(원문에 키 값이 있다). */
    class UniqueConflict(val constraint: String) : PaymentFactPersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : PaymentFactPersistenceFailure("concurrent modification of $entity", null)

    /** InnoDB 교착(1213) — DB가 이 TX를 rollback했다. [vendorCode]·[sqlState]는 진단 값만. */
    class Deadlock(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        PaymentFactPersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        PaymentFactPersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable, val vendorCode: Int? = null, val sqlState: String? = null) :
        PaymentFactPersistenceFailure("database unavailable", cause)
}
