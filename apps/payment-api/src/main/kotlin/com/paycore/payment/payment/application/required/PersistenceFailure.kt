package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.provided.FailureKindSource
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.provided.StoreDiagnosticSource

/**
 * 저장소 Port가 던지는 기술 실패(CP-DB-001 관례, FOUNDATION round4 L-2). persistence adapter가 Port 메서드 안에서 flush해
 * DB/JPA 예외를 이 종류로 바꾼다. application은 `org.springframework.dao`·JDBC 예외를 알지 않는다.
 *
 * 분류 원칙: **충돌**(UNIQUE·낙관적 version)만 업무 재판정/기존 시도 수렴의 근거가 될 수 있고, 교착·잠금 timeout·연결 오류는 결과를
 * 바꾸지 않는 일시 실패다. 이 예외가 나면 같은 TX는 더 쓸 수 없다(JPA flush 실패는 TX를 rollback 전용으로 만든다) — 호출자는
 * TX 전체를 rollback하고, 다시 시도한다면 새 TX에서 처음부터 같은 의도로 한다.
 * 메시지·필드에 키 값(paymentKey 등)을 넣지 않는다(MySQL 중복 오류 원문에는 값이 들어 있다).
 */
sealed class PersistenceFailure(message: String, cause: Throwable?, override val diagnostic: StoreDiagnostic?) :
    RuntimeException(message, cause), FailureKindSource, StoreDiagnosticSource {

    /** UNIQUE/PK 위반. [constraint]가 어떤 업무 키인지 알려준다(원문 값은 담지 않는다 — cause도 붙이지 않는다). */
    class UniqueConflict(val constraint: StoreConstraint) :
        PersistenceFailure("unique constraint conflict: $constraint", null, StoreDiagnostic(vendorCode = 1062, sqlState = "23000", constraint = constraint.name)) {
        override val failureKind: FailureKind = FailureKind.OTHER
    }

    /** 읽은 뒤 다른 TX가 같은 행을 바꿨다(JPA @Version). 오래된 domain으로 덮어쓰지 않는다. */
    class OptimisticConflict(val entity: String) : PersistenceFailure("concurrent modification of $entity", null, null) {
        override val failureKind: FailureKind = FailureKind.RETRY_EXHAUSTED
    }

    /** InnoDB 교착(1213). DB가 이 TX를 이미 rollback했다. 새 TX로 같은 의도를 다시 실행할 수 있다. */
    class Deadlock(cause: Throwable, diagnostic: StoreDiagnostic? = null) :
        PersistenceFailure("deadlock (transaction rolled back by the database)", cause, diagnostic) {
        override val failureKind: FailureKind = FailureKind.DEADLOCK
    }

    /** 잠금 대기 timeout(1205). 기본 설정에서 MySQL은 문장만 되돌리므로 호출자가 TX 전체를 rollback해야 한다. */
    class LockTimeout(cause: Throwable, diagnostic: StoreDiagnostic? = null) : PersistenceFailure("lock wait timeout", cause, diagnostic) {
        override val failureKind: FailureKind = FailureKind.LOCK_TIMEOUT
    }

    /** 연결 실패·끊김·pool 고갈. 커밋 여부를 이 예외만으로 알 수 없을 수 있다(호출자가 같은 키로 다시 확인). */
    class Unavailable(cause: Throwable, diagnostic: StoreDiagnostic? = null) : PersistenceFailure("database unavailable", cause, diagnostic) {
        override val failureKind: FailureKind = FailureKind.DB_UNAVAILABLE
    }
}

/** UNIQUE/PK 제약의 업무 의미. */
enum class StoreConstraint {
    PAYMENT_ID,
    PAYMENT_KEY,
    PAYMENT_ORDER_GENERATION,
    TERMINATION_EVIDENCE,
    ORDER_CONTROL,
    INCIDENT_HOLD,
    IDEMPOTENCY_SCOPE,
    OUTBOX_EVENT_ID,
    OUTBOX_EFFECT_KEY,

    // AUTHN-001 V7(명세 §10.1): 비밀 digest·생성 컬럼 UNIQUE.
    CHECKOUT_TOKEN_ID,
    CHECKOUT_TOKEN_DIGEST,
    CHECKOUT_TOKEN_OUTSTANDING,
    CHECKOUT_SESSION_ID,
    CHECKOUT_SESSION_HANDLE,
    CHECKOUT_SESSION_DIGEST,
    CHECKOUT_SESSION_TOKEN,
    CHECKOUT_SESSION_ACTIVE,
    AUTHENTICATION_SESSION_ID,
    AUTHENTICATION_SESSION_STATE,
    AUTHENTICATION_SESSION_RESULT_REF,
    AUTHENTICATION_SESSION_REF,
    AUTHENTICATION_SESSION_OPEN,
    AUTHENTICATION_SESSION_BOUND,

    /** 승인 하나에 세션 하나(`bound_authorization_id` UNIQUE — AUTH-001 귀속, 검토 #2 n-1). */
    AUTHENTICATION_SESSION_BOUND_AUTHORIZATION,
    AUTHENTICATION_SESSION_EVENT,

    // AUTH-001 V9(명세 §7.1): 승인·Attempt·관찰·거래·일 한도.
    PAYMENT_AUTHORIZATION_ID,

    /** Payment당 논리 승인 하나(`uk_payment_authorization_payment`). */
    PAYMENT_AUTHORIZATION_PAYMENT,
    AUTHORIZATION_ATTEMPT_ID,

    /** 기관 요청 namespace(기관·환경·계약계정·operation·요청 ID) UNIQUE. */
    AUTHORIZATION_ATTEMPT_NAMESPACE,

    /** Authorization당 동시 실행 Attempt 하나(생성 열 UNIQUE). */
    AUTHORIZATION_ATTEMPT_IN_FLIGHT,
    INSTITUTION_OBSERVATION,
    PAYMENT_TRANSACTION_ID,

    /** Authorization당 승인 거래 하나(생성 열 UNIQUE). */
    PAYMENT_TRANSACTION_AUTHORIZATION,
    DAILY_LIMIT_USAGE,
    LIMIT_RESERVATION,

    // CANCEL-001 V15·V17(명세 §8.1): 취소·Attempt·관찰·반환 잔액·예약·CANCEL 거래·회복 일정.
    PAYMENT_CANCELLATION_ID,

    /** 가맹점 취소 업무 키 (payment_id, merchant_cancel_id) — 영구(D-CANCEL-002). */
    PAYMENT_CANCELLATION_MERCHANT_KEY,

    /** 운영자 2인 승인 ID — 전역 영구 키(CC-12). application은 새 TX에서 재판정해 CONFLICTING_CONTENT로 돌려준다(rev 0.3 m-3). */
    PAYMENT_CANCELLATION_APPROVAL,

    /** Payment당 진행 중(OPEN·UNKNOWN) 취소 하나(생성 열 UNIQUE — D-CANCEL-003). */
    PAYMENT_CANCELLATION_IN_FLIGHT,
    CANCEL_ATTEMPT_ID,
    CANCEL_ATTEMPT_CANCELLATION,
    CANCEL_ATTEMPT_NAMESPACE,
    CANCEL_OBSERVATION,
    RETURN_BALANCE,
    RETURN_RESERVATION,

    /** Payment당 진행 중(RESERVED) 반환 예약 하나(생성 열 UNIQUE). */
    RETURN_RESERVATION_IN_FLIGHT,
    RETURN_RESERVATION_SEQUENCE,

    /** 취소당 CANCEL 거래 하나(생성 열 UNIQUE). */
    PAYMENT_TRANSACTION_CANCELLATION,
    CANCELLATION_RECOVERY_SCHEDULE,
    OTHER,
}
