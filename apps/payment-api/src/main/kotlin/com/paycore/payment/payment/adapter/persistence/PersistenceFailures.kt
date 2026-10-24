package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * DB/JPA 예외 → [PersistenceFailure] 분류(CP-DB-001 관례, FOUNDATION round4 L-2). Port 메서드는 본문을 [translate]로 감싸고 쓰기 뒤에는
 * 같은 블록 안에서 flush해 제약 위반이 commit 시점이 아니라 Port 안에서 드러나게 한다.
 *
 * 판단은 예외 클래스 이름이 아니라 원인 사슬의 MySQL vendor 오류 코드·SQLState로 한다(Spring 번역 여부와 무관하게 같은 결과):
 * 1062 중복 → UniqueConflict(키 이름만, 값은 버림) · 1213 교착 → Deadlock · 1205 잠금 대기 timeout → LockTimeout ·
 * SQLState 08xxx/연결 예외 → Unavailable · JPA/Hibernate version 충돌 → OptimisticConflict.
 * 그 밖(CHECK 위반 3819, 길이 초과 1406 등)은 업무 충돌이 아니라 결함이므로 분류하지 않고 원래 예외를 그대로 던진다.
 */
internal object PersistenceFailures {

    fun <T> translate(entity: String, block: () -> T): T =
        try {
            block()
        } catch (e: PersistenceFailure) {
            throw e
        } catch (e: RuntimeException) {
            throw classify(entity, e) ?: e
        }

    fun classify(entity: String, failure: Throwable): PersistenceFailure? {
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            return PersistenceFailure.OptimisticConflict(entity)
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: return null
        // 진단 값(n-3): vendor 코드·SQLState만. 메시지 원문(키 값이 들어 있을 수 있다)은 담지 않는다.
        val diagnostic = StoreDiagnostic(vendorCode = sql.errorCode, sqlState = sql.sqlState)
        return when {
            sql.errorCode == MYSQL_DUPLICATE_KEY -> PersistenceFailure.UniqueConflict(constraintOf(sql.message))
            sql.errorCode == MYSQL_DEADLOCK -> PersistenceFailure.Deadlock(failure, diagnostic)
            sql.errorCode == MYSQL_LOCK_WAIT_TIMEOUT -> PersistenceFailure.LockTimeout(failure, diagnostic)
            isConnectionFailure(sql) -> PersistenceFailure.Unavailable(failure, diagnostic)
            else -> null
        }
    }

    private fun isConnectionFailure(sql: SQLException): Boolean =
        sql.sqlState?.startsWith("08") == true ||
            sql is SQLTransientConnectionException ||
            sql is SQLNonTransientConnectionException ||
            sql is SQLRecoverableException ||
            sql.errorCode in MYSQL_CONNECTION_LOST

    /**
     * MySQL 8 중복 오류 원문 `Duplicate entry '<값>' for key '<table>.<index>'`에서 마지막 key 이름만 읽는다. 값 부분은 쓰지 않는다
     * (paymentKey 등 키 값이 예외 필드·로그로 새지 않게).
     */
    fun constraintOf(message: String?): StoreConstraint {
        val key = message?.let { KEY_NAME.find(it)?.groupValues?.get(1) } ?: return StoreConstraint.OTHER
        return CONSTRAINTS[key] ?: StoreConstraint.OTHER
    }

    private const val MAX_CAUSE_DEPTH = 20
    private const val MYSQL_DUPLICATE_KEY = 1062
    private const val MYSQL_DEADLOCK = 1213
    private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205

    /** 2006 server has gone away, 2013 lost connection during query. */
    private val MYSQL_CONNECTION_LOST = setOf(2006, 2013)

    private val KEY_NAME = Regex("for key '([^']+)'\\s*$")

    private val CONSTRAINTS = mapOf(
        "payment.PRIMARY" to StoreConstraint.PAYMENT_ID,
        "payment.uk_payment_key" to StoreConstraint.PAYMENT_KEY,
        "payment.uk_payment_order_generation" to StoreConstraint.PAYMENT_ORDER_GENERATION,
        "payment_termination_evidence.PRIMARY" to StoreConstraint.TERMINATION_EVIDENCE,
        "order_payment_control.PRIMARY" to StoreConstraint.ORDER_CONTROL,
        "incident_hold.uk_incident_hold_order_payment_reason" to StoreConstraint.INCIDENT_HOLD,
        "incident_hold.uk_incident_hold_order_payment_reason_occurrence" to StoreConstraint.INCIDENT_HOLD,
        "idempotency_request.uk_idempotency_request_scope" to StoreConstraint.IDEMPOTENCY_SCOPE,
        "outbox_event.uk_outbox_event_event_id" to StoreConstraint.OUTBOX_EVENT_ID,
        "outbox_event.uk_outbox_event_effect_key" to StoreConstraint.OUTBOX_EFFECT_KEY,
        "checkout_token.PRIMARY" to StoreConstraint.CHECKOUT_TOKEN_ID,
        "checkout_token.uk_checkout_token_digest" to StoreConstraint.CHECKOUT_TOKEN_DIGEST,
        "checkout_token.uk_checkout_token_outstanding" to StoreConstraint.CHECKOUT_TOKEN_OUTSTANDING,
        "checkout_session.PRIMARY" to StoreConstraint.CHECKOUT_SESSION_ID,
        "checkout_session.uk_checkout_session_handle" to StoreConstraint.CHECKOUT_SESSION_HANDLE,
        "checkout_session.uk_checkout_session_digest" to StoreConstraint.CHECKOUT_SESSION_DIGEST,
        "checkout_session.uk_checkout_session_token" to StoreConstraint.CHECKOUT_SESSION_TOKEN,
        "checkout_session.uk_checkout_session_active" to StoreConstraint.CHECKOUT_SESSION_ACTIVE,
        "authentication_session.PRIMARY" to StoreConstraint.AUTHENTICATION_SESSION_ID,
        "authentication_session.uk_authentication_session_state" to StoreConstraint.AUTHENTICATION_SESSION_STATE,
        "authentication_session.uk_authentication_session_result_ref" to StoreConstraint.AUTHENTICATION_SESSION_RESULT_REF,
        "authentication_session.uk_authentication_session_ref" to StoreConstraint.AUTHENTICATION_SESSION_REF,
        "authentication_session.uk_authentication_session_open" to StoreConstraint.AUTHENTICATION_SESSION_OPEN,
        "authentication_session.uk_authentication_session_bound" to StoreConstraint.AUTHENTICATION_SESSION_BOUND,
        "authentication_session.uk_authentication_session_bound_authorization" to StoreConstraint.AUTHENTICATION_SESSION_BOUND_AUTHORIZATION,
        "authentication_session_event.PRIMARY" to StoreConstraint.AUTHENTICATION_SESSION_EVENT,
        "authentication_session_event.uk_authentication_session_event_repeat" to StoreConstraint.AUTHENTICATION_SESSION_EVENT,
        "payment_authorization.PRIMARY" to StoreConstraint.PAYMENT_AUTHORIZATION_ID,
        "payment_authorization.uk_payment_authorization_payment" to StoreConstraint.PAYMENT_AUTHORIZATION_PAYMENT,
        "authorization_attempt.PRIMARY" to StoreConstraint.AUTHORIZATION_ATTEMPT_ID,
        "authorization_attempt.uk_authorization_attempt_namespace" to StoreConstraint.AUTHORIZATION_ATTEMPT_NAMESPACE,
        "authorization_attempt.uk_authorization_attempt_in_flight" to StoreConstraint.AUTHORIZATION_ATTEMPT_IN_FLIGHT,
        "institution_observation.uk_institution_observation_key" to StoreConstraint.INSTITUTION_OBSERVATION,
        "payment_transaction.PRIMARY" to StoreConstraint.PAYMENT_TRANSACTION_ID,
        "payment_transaction.uk_payment_transaction_authorization" to StoreConstraint.PAYMENT_TRANSACTION_AUTHORIZATION,
        "daily_limit_usage.PRIMARY" to StoreConstraint.DAILY_LIMIT_USAGE,
        "limit_reservation.PRIMARY" to StoreConstraint.LIMIT_RESERVATION,
        "payment_cancellation.PRIMARY" to StoreConstraint.PAYMENT_CANCELLATION_ID,
        "payment_cancellation.uk_payment_cancellation_merchant_key" to StoreConstraint.PAYMENT_CANCELLATION_MERCHANT_KEY,
        "payment_cancellation.uk_payment_cancellation_approval" to StoreConstraint.PAYMENT_CANCELLATION_APPROVAL,
        "payment_cancellation.uk_payment_cancellation_in_flight" to StoreConstraint.PAYMENT_CANCELLATION_IN_FLIGHT,
        "cancel_attempt.PRIMARY" to StoreConstraint.CANCEL_ATTEMPT_ID,
        "cancel_attempt.uk_cancel_attempt_cancellation" to StoreConstraint.CANCEL_ATTEMPT_CANCELLATION,
        "cancel_attempt.uk_cancel_attempt_namespace" to StoreConstraint.CANCEL_ATTEMPT_NAMESPACE,
        "cancel_observation.uk_cancel_observation_key" to StoreConstraint.CANCEL_OBSERVATION,
        "return_balance.PRIMARY" to StoreConstraint.RETURN_BALANCE,
        "return_balance.uk_return_balance_authorization" to StoreConstraint.RETURN_BALANCE,
        "return_reservation.PRIMARY" to StoreConstraint.RETURN_RESERVATION,
        "return_reservation.uk_return_reservation_seq" to StoreConstraint.RETURN_RESERVATION,
        "return_reservation.uk_return_reservation_in_flight" to StoreConstraint.RETURN_RESERVATION_IN_FLIGHT,
        "return_reservation.uk_return_reservation_completion" to StoreConstraint.RETURN_RESERVATION_SEQUENCE,
        "payment_transaction.uk_payment_transaction_cancellation" to StoreConstraint.PAYMENT_TRANSACTION_CANCELLATION,
        "cancellation_recovery_schedule.PRIMARY" to StoreConstraint.CANCELLATION_RECOVERY_SCHEDULE,
        "merchant_webhook_event.PRIMARY" to StoreConstraint.MERCHANT_WEBHOOK_EVENT_ID,
        "merchant_webhook_event.uk_merchant_webhook_event_source" to StoreConstraint.MERCHANT_WEBHOOK_EVENT_SOURCE,
        "merchant_webhook_event.uk_merchant_webhook_event_sequence" to StoreConstraint.MERCHANT_WEBHOOK_EVENT_SEQUENCE,
        "merchant_webhook_delivery.PRIMARY" to StoreConstraint.MERCHANT_WEBHOOK_DELIVERY_ID,
        "merchant_webhook_delivery.uk_merchant_webhook_delivery_event" to StoreConstraint.MERCHANT_WEBHOOK_DELIVERY_EVENT,
        "merchant_webhook_attempt.uk_merchant_webhook_attempt_no" to StoreConstraint.MERCHANT_WEBHOOK_ATTEMPT,
    )
}
