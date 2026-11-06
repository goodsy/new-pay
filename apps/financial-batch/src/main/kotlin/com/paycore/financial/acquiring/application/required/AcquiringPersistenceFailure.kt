package com.paycore.financial.acquiring.application.required

/**
 * 매입 저장 Port 기술 실패(paymentfact `PaymentFactPersistenceFailure`와 같은 관례). 이 예외 뒤 같은 TX는 쓸 수 없다. 메시지에 키 값을 넣지 않는다.
 */
sealed class AcquiringPersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    class UniqueConflict(val constraint: String) : AcquiringPersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : AcquiringPersistenceFailure("concurrent modification of $entity", null)

    class Deadlock(cause: Throwable) : AcquiringPersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable) : AcquiringPersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable) : AcquiringPersistenceFailure("database unavailable", cause)
}
