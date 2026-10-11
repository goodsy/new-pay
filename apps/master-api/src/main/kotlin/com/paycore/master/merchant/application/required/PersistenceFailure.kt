package com.paycore.master.merchant.application.required

/**
 * 저장소 Port가 던지는 기술 실패(payment-api와 같은 관례, ARCHITECTURE-CONVENTIONS §9). persistence adapter가 Port 메서드 안에서 flush해
 * DB/JPA 예외를 이 종류로 바꾼다. application은 `org.springframework.dao`·JDBC 예외를 알지 않는다. 메시지에 키 값을 넣지 않는다.
 * 이 예외가 나면 같은 TX는 더 쓸 수 없다 — 호출자는 TX 전체를 rollback하고, 다시 시도한다면 새 TX에서 처음부터 같은 의도로 한다.
 */
sealed class PersistenceFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {

    /** UNIQUE/PK 위반. [constraint]가 어떤 업무 키인지 알려준다(원문 값은 담지 않는다). */
    class UniqueConflict(val constraint: StoreConstraint) : PersistenceFailure("unique constraint conflict: $constraint", null)

    class OptimisticConflict(val entity: String) : PersistenceFailure("concurrent modification of $entity", null)

    class Deadlock(cause: Throwable) : PersistenceFailure("deadlock (transaction rolled back by the database)", cause)

    class LockTimeout(cause: Throwable) : PersistenceFailure("lock wait timeout", cause)

    class Unavailable(cause: Throwable) : PersistenceFailure("database unavailable", cause)
}

/** UNIQUE/PK 제약의 의미. [businessKey]가 참이면 업무 유일성(동시 요청 경합 → 새 TX에서 재판정하면 업무 거절로 수렴), 거짓이면 서버 생성 ID 충돌. */
enum class StoreConstraint(val businessKey: Boolean) {
    BUSINESS_REGISTRATION_NUMBER(true),
    SITE_ACTIVE_CHANNEL(true),
    DEFAULT_DESIGNATION(true),
    ASSIGNMENT_OPEN(true),
    AGREEMENT_SERVICE_KIND(true),
    ACTIVATION_METHOD(true),
    CONTRACT_REVISION(true),
    REVISION_APPROVAL(true),
    ADMIN_REQUEST_SCOPE(true),
    IDENTIFIER(false),
    OUTBOX_EVENT_ID(false),
    OUTBOX_EFFECT_KEY(false),
    OTHER(false),
}
