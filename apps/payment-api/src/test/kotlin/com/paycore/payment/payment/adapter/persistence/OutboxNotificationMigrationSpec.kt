package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.PaymentDbAccounts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException

/**
 * T-M1(WEBHOOK-001 §5.0 rev 0.2 m-9, D-WH-009): V22까지 적용된 볼륨(이 작업 이전 배포 + V21·V22)에 Outbox 행이 있는 상태에서 V23·V24를 적용하면
 * 기존 행은 `SKIPPED_PRE_DEPLOY`(알림 시각 없음)이고, 이후 업무 경로처럼 알림 열 없이 INSERT한 행은 `PENDING`이다. 운영 초기화 스크립트 계정·운영 migration 위치를 그대로 쓴다.
 */
class OutboxNotificationMigrationSpec : FunSpec({

    val container = PaymentDbAccounts.startedContainer()
    afterSpec { container.stop() }

    fun flyway(target: String? = null): Flyway = Flyway.configure()
        .dataSource(
            PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
            PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
        )
        .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
        .initSql("SET time_zone = '+00:00'")
        .also { c -> target?.let { c.target(it) } }
        .load()

    fun <T> root(block: (Connection) -> T): T = PaymentDbAccounts.root(container).use { it.catalog = PaymentDbAccounts.PAYMENT_DATABASE; block(it) }

    fun <T> app(block: (Connection) -> T): T =
        PaymentDbAccounts.connect(container, PaymentDbAccounts.PAYMENT_DATABASE, PaymentDbAccounts.PAYMENT_RUNTIME_USER).use(block)

    /** 업무 경로(JpaOutboxStore)와 같은 열 목록 — 알림 열은 넣지 않는다(DEFAULT). */
    fun insertOutbox(c: Connection, eventId: String) {
        c.prepareStatement(
            """INSERT INTO outbox_event (event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, aggregate_version,
               occurred_at, correlation_id, causation_id, payload, publish_status, published_at, created_at)
               VALUES (?, ?, 'PaymentRequested', 1, 'INTEGRATION_NON_FINANCIAL', 'payment-api/payment', 'Payment', ?, 0, '2026-10-21 00:00:00.000', 'corr', 'cause', '{}', 'PENDING', NULL,
               '2026-10-21 00:00:00.000')""",
        ).use { ps ->
            ps.setString(1, eventId); ps.setString(2, "PaymentRequested:$eventId"); ps.setString(3, "pay-$eventId")
            ps.executeUpdate() shouldBe 1
        }
    }

    fun notification(eventId: String): Pair<String, Any?> = root { c ->
        c.prepareStatement("SELECT merchant_notification_status, merchant_notified_at FROM outbox_event WHERE event_id = ?").use { ps ->
            ps.setString(1, eventId)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to rs.getObject(2) }
        }
    }

    fun Connection.codeOf(sql: String): Int? = try {
        createStatement().use { it.execute(sql) }
        null
    } catch (e: SQLException) {
        e.errorCode
    }

    test("an existing volume at V22 with Outbox rows: V23/V24 mark the old rows SKIPPED_PRE_DEPLOY and new inserts become PENDING") {
        flyway(target = "22").migrate()
        val old = (1..3).map { "00000000-0000-4000-8000-00000000000$it" }
        root { c -> old.forEach { insertOutbox(c, it) } }

        flyway().migrate()

        old.forEach { notification(it) shouldBe ("SKIPPED_PRE_DEPLOY" to null) }
        val fresh = "00000000-0000-4000-8000-000000000009"
        app { c -> insertOutbox(c, fresh) }
        notification(fresh) shouldBe ("PENDING" to null)
        // 같은 시점에 열 권한도 적용됐다(D-WH-009): 알림 열 UPDATE. 최신 migration까지 적용했으므로 D-FFR-001(V34 — publish_status·published_at 열 UPDATE)도 반영돼
        // publish는 허용, payload 등 나머지 열은 1143(기대값 변경 근거: D-FFR-001 사용자 결정 — 완화 아님).
        app { c ->
            mapOf(
                "notification" to c.codeOf("UPDATE outbox_event SET merchant_notification_status = merchant_notification_status WHERE id = -1"),
                "publish" to c.codeOf("UPDATE outbox_event SET publish_status = 'PUBLISHED' WHERE id = -1"),
                "payload" to c.codeOf("UPDATE outbox_event SET payload = payload WHERE id = -1"),
            )
        } shouldBe mapOf("notification" to null, "publish" to null, "payload" to 1143)
    }

    test("the notification CHECK keeps a time exactly on terminal marks") {
        root { c ->
            mapOf(
                "terminal without time" to c.codeOf("UPDATE outbox_event SET merchant_notification_status = 'NOTIFIED' WHERE event_id LIKE '00000000-%'"),
                "pending with time" to c.codeOf("UPDATE outbox_event SET merchant_notified_at = '2026-10-21 00:00:00.000' WHERE event_id LIKE '00000000-%'"),
                "unknown status" to c.codeOf("UPDATE outbox_event SET merchant_notification_status = 'HELD_DECISION' WHERE event_id LIKE '00000000-%'"),
            )
        } shouldBe mapOf("terminal without time" to 3819, "pending with time" to 3819, "unknown status" to 3819)
    }
})
