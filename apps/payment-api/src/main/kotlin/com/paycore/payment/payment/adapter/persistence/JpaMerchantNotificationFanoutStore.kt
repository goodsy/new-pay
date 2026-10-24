package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.MerchantNotificationFanoutStore
import com.paycore.payment.payment.application.required.NotificationSubject
import com.paycore.payment.payment.application.required.NotifiedPayment
import com.paycore.payment.payment.application.required.OutboxNotificationCandidate
import com.paycore.payment.payment.application.required.OutboxNotificationMark
import com.paycore.payment.payment.application.required.PendingOutboxNotification
import com.paycore.payment.payment.application.required.UnreadableNotificationSourceException
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantNotificationPolicy
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [MerchantNotificationFanoutStore] 구현(WEBHOOK-001 §5.0 rev 0.2 — H-3·m-3·m-7, D-WH-009). 모두 native SQL이다:
 *
 * - **Outbox는 잠금 읽기하지 않는다.** 후보·행 읽기는 비잠금(READ COMMITTED 최신 커밋), 표시는 `merchant_notification_*` 두 열만 바꾸는 PK UPDATE(runtime 권한도 그 두 열뿐 — 나머지 열 1143).
 *   `JpaOutboxEventEntity`는 `@Immutable`이라 JPA 경로로는 Outbox를 바꿀 수 없다. 업무 TX의 Outbox INSERT는 이 Store를 기다리지 않는다(시험 C-F2).
 * - fan-out 쓰기 직렬화는 webhook 전용 guard 행 `FOR UPDATE`([lockGuard]). Payment·취소 행은 비잠금으로 읽는다(업무 잠금 순서와 교차 없음 — webhook 표는 업무 표로 FK를 두지 않는다, WH-23).
 * - 시각 열은 `DATE_FORMAT` 문자열로 읽는다(JVM 시간대 무관 — 관례 §9.1). 바인딩 Instant는 `hibernate.jdbc.time_zone = UTC`.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantNotificationFanoutStore(private val entityManager: EntityManager) : MerchantNotificationFanoutStore {

    /** 자기 읽기 전용 TX(fan-out 회차 시작 — 행 TX 밖). */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    override fun candidates(now: Instant, limit: Int): List<OutboxNotificationCandidate> = translate(ENTITY) {
        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(CANDIDATES_SQL)
            .setParameter("now", now)
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>
        rows.map { OutboxNotificationCandidate((it[0] as Number).toLong(), it[1] as String?) }
    }

    override fun lockGuard(): Unit = translate(GUARD) {
        val found = entityManager.createNativeQuery("SELECT guard_name FROM merchant_webhook_fanout_guard WHERE guard_name = 'FANOUT' FOR UPDATE").resultList
        check(found.size == 1) { "the fan-out guard row must exist (V21 seed)" }
    }

    override fun hasEarlierOpenRow(outboxId: Long, paymentKey: String): Boolean = translate(ENTITY) {
        entityManager.createNativeQuery(EARLIER_OPEN_SQL).setParameter("id", outboxId).setParameter("p", paymentKey).resultList.isNotEmpty()
    }

    override fun load(outboxId: Long, now: Instant): PendingOutboxNotification? = translate(ENTITY) {
        @Suppress("UNCHECKED_CAST")
        val row = (entityManager.createNativeQuery(LOAD_SQL).setParameter("id", outboxId).resultList as List<Array<Any?>>).singleOrNull() ?: return@translate null
        val status = row[4] as String
        val retryAt = (row[5] as String?)?.let(Instant::parse)
        val due = status == PENDING || (status == FANOUT_FAILED && retryAt != null && !retryAt.isAfter(now))
        if (!due) return@translate null
        val eventType = row[1] as String
        val payload = row[3] as String
        val subject = if (eventType in MerchantNotificationPolicy.CATALOGUED_EVENT_TYPES) subject(eventType, payload) else null
        PendingOutboxNotification(outboxId, row[0] as String, eventType, Instant.parse(row[2] as String), subject)
    }

    override fun mark(outboxId: Long, mark: OutboxNotificationMark, at: Instant): Unit = translate(ENTITY) {
        val changed = entityManager.createNativeQuery(
            "UPDATE outbox_event SET merchant_notification_status = :mark, merchant_notified_at = :at " +
                "WHERE id = :id AND merchant_notification_status IN ('PENDING', 'FANOUT_FAILED')",
        ).setParameter("mark", mark.name).setParameter("at", at).setParameter("id", outboxId).executeUpdate()
        // guard가 fan-out 쓰기를 직렬화하므로 load 뒤 상태가 바뀔 수 없다 — 바뀌었으면 결함(TX rollback).
        check(changed == 1) { "the outbox row was not pending under the fan-out guard" }
    }

    override fun markFailed(outboxId: Long, failureCode: String, at: Instant, retryAt: Instant): Unit = translate(ENTITY) {
        MerchantWebhookDelivery.requireFailureCode(failureCode)
        val changed = entityManager.createNativeQuery(
            "UPDATE outbox_event SET merchant_notification_status = 'FANOUT_FAILED' WHERE id = :id AND merchant_notification_status IN ('PENDING', 'FANOUT_FAILED')",
        ).setParameter("id", outboxId).executeUpdate()
        if (changed == 0) return@translate // 이미 종결(예: 커밋 결과 불명확 뒤 실제로는 커밋됨) — 실패로 되돌리지 않는다.
        entityManager.createNativeQuery(
            "INSERT INTO merchant_notification_failure (outbox_id, failures, failure_code, retry_at, first_failed_at, last_failed_at) " +
                "VALUES (:id, 1, :code, :retry, :at, :at) AS fresh " +
                "ON DUPLICATE KEY UPDATE failures = merchant_notification_failure.failures + 1, failure_code = fresh.failure_code, " +
                "retry_at = fresh.retry_at, last_failed_at = fresh.last_failed_at",
        ).setParameter("id", outboxId).setParameter("code", failureCode).setParameter("retry", retryAt).setParameter("at", at).executeUpdate()
    }

    override fun nextSequence(paymentId: PaymentId): Int = translate(STREAM) {
        entityManager.createNativeQuery(
            "INSERT INTO merchant_webhook_stream (payment_id, last_sequence, updated_at) VALUES (:p, 1, UTC_TIMESTAMP(3)) AS fresh " +
                "ON DUPLICATE KEY UPDATE last_sequence = merchant_webhook_stream.last_sequence + 1, updated_at = fresh.updated_at",
        ).setParameter("p", paymentId.value).executeUpdate()
        (entityManager.createNativeQuery("SELECT last_sequence FROM merchant_webhook_stream WHERE payment_id = :p").setParameter("p", paymentId.value).singleResult as Number).toInt()
    }

    /** 통지 판정 대상 이벤트의 주체: payload 신원 + 불변 Payment 행(상태·생성 금액) + (취소면) 불변 취소 행. 신원이 서로 다르면 해석 불가(fail-closed). */
    private fun subject(eventType: String, payload: String): NotificationSubject {
        val identity = OutboxNotificationPayloads.identity(eventType, payload)
        @Suppress("UNCHECKED_CAST")
        val p = (entityManager.createNativeQuery(PAYMENT_SQL).setParameter("p", identity.paymentId).resultList as List<Array<Any?>>).singleOrNull()
        if (p != null && (p[3] != identity.environment.name || p[4] != identity.merchantAccountId || p[5] != identity.merchantOrderId)) {
            throw UnreadableNotificationSourceException("IDENTITY_MISMATCH")
        }
        val cancellation = identity.cancellationId?.let { id ->
            @Suppress("UNCHECKED_CAST")
            val c = (entityManager.createNativeQuery(CANCELLATION_SQL).setParameter("c", id).resultList as List<Array<Any?>>).singleOrNull()
            if (c != null && c[2] != identity.paymentId) throw UnreadableNotificationSourceException("IDENTITY_MISMATCH")
            c?.let { OutboxNotificationPayloads.CancellationRow(it[0] as String, it[1] as String?) }
        }
        val facts = OutboxNotificationPayloads.facts(eventType, payload, cancellation)
        return NotificationSubject(
            environment = identity.environment, merchantAccountId = MerchantAccountId(identity.merchantAccountId), paymentId = PaymentId(identity.paymentId),
            merchantOrderId = MerchantOrderId(identity.merchantOrderId),
            payment = p?.let { NotifiedPayment(PaymentStatus.valueOf(it[0] as String), (it[1] as Number).toLong(), it[2] as String) },
            facts = facts,
        )
    }

    private companion object {
        const val ENTITY = "outbox_event"
        const val GUARD = "merchant_webhook_fanout_guard"
        const val STREAM = "merchant_webhook_stream"
        const val PENDING = "PENDING"
        const val FANOUT_FAILED = "FANOUT_FAILED"

        /**
         * PENDING ∪ 재시도 시각이 된 FANOUT_FAILED, Outbox id 순. 같은 Payment(payload paymentId)에 이 행보다 앞선 FANOUT_FAILED가 아직 재시도 시각 전이면 제외(순서 보존 — m-3).
         * 인덱스 `ix_outbox_event_merchant_notification(merchant_notification_status, id)`로 미종결 행만 본다.
         */
        val CANDIDATES_SQL = """
            SELECT o.id, JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')) AS payment_key
            FROM outbox_event o LEFT JOIN merchant_notification_failure f ON f.outbox_id = o.id
            WHERE o.merchant_notification_status IN ('PENDING', 'FANOUT_FAILED')
              AND (o.merchant_notification_status = 'PENDING' OR f.retry_at <= :now)
              AND NOT EXISTS (
                SELECT 1 FROM outbox_event e JOIN merchant_notification_failure ef ON ef.outbox_id = e.id
                WHERE e.merchant_notification_status = 'FANOUT_FAILED' AND ef.retry_at > :now AND e.id < o.id
                  AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.paymentId')) = JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')))
            ORDER BY o.id
            LIMIT :limit
        """.trimIndent()

        /** 같은 Payment의 앞선 미종결 행(H-1). 인덱스로 미종결 행만 보고 그 중 같은 paymentId를 찾는다(미종결 행은 적다 — 적체 gauge로 감시). */
        val EARLIER_OPEN_SQL = """
            SELECT e.id FROM outbox_event e
            WHERE e.merchant_notification_status IN ('PENDING', 'FANOUT_FAILED') AND e.id < :id
              AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.paymentId')) = :p
            LIMIT 1
        """.trimIndent()

        val LOAD_SQL = """
            SELECT o.event_id, o.event_type, DATE_FORMAT(o.occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ'), CAST(o.payload AS CHAR), o.merchant_notification_status,
                   DATE_FORMAT(f.retry_at, '%Y-%m-%dT%H:%i:%s.%fZ')
            FROM outbox_event o LEFT JOIN merchant_notification_failure f ON f.outbox_id = o.id
            WHERE o.id = :id
        """.trimIndent()

        const val PAYMENT_SQL =
            "SELECT status, amount_minor, currency, environment, merchant_account_id, merchant_order_id FROM payment WHERE payment_id = :p"

        const val CANCELLATION_SQL = "SELECT initiator_kind, merchant_cancel_id, payment_id FROM payment_cancellation WHERE cancellation_id = :c"
    }
}
