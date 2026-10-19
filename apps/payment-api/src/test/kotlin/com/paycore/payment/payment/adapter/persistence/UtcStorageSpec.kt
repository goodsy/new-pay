package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentStatus
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import javax.sql.DataSource

/**
 * CP-DB-001 M-2: DB에 저장되는 DATETIME(3) 원문이 **JVM 기본 시간대와 무관하게 UTC**인지 실제 MySQL 8.4로 확인한다.
 *
 * - 쓰기 경로 두 가지를 모두 지난다: native `INSERT … ON DUPLICATE KEY UPDATE`(멱등 예약 created_at/expires_at, control created_at/updated_at,
 *   `*JpaRepository`)와 Entity 경로(Payment persist, 만료 갱신 `renewExpired`, 완료 `complete`, control update, Outbox, hold).
 * - 원문 확인은 애플리케이션 pool이 아닌 별도 연결에서 `CAST(col AS CHAR)`로 읽는다(드라이버 시각 변환을 거치지 않은 DB 저장 문자열).
 * - JVM 기본 시간대를 UTC가 아닌 값(Asia/Seoul, +09:00 — 차이가 커서 날짜까지 바뀐다)으로 바꾸고, 애플리케이션 pool 연결을 모두
 *   교체한 뒤 실행한다(Connector/J는 `connectionTimeZone=LOCAL`일 때 연결을 만들 때의 JVM 기본 시간대를 세션 시간대로 잡으므로,
 *   교체하지 않으면 바꾼 시간대가 드라이버에 반영되지 않는다). 같은 시나리오를 UTC JVM에서도 실행해 두 결과가 같음을 보인다.
 * - 시간대 변경은 이 spec의 각 시나리오 안에서만 하고 끝나면 원래 값으로 되돌린 뒤 pool을 다시 교체한다(Kotest spec은 순차 실행).
 *
 * 결과(2026-10-07): JPA 경로(native upsert 포함)는 `hibernate.jdbc.time_zone=UTC` 덕분에 처음부터 UTC였다(Hibernate가 UTC Calendar로
 * 바인딩). Hibernate를 거치지 않는 plain JDBC(jOOQ·raw SQL이 쓰는 경로)는 Asia/Seoul JVM에서 `2026-10-07 10:02:03.456`으로 쓰고
 * `01:02`을 `2026-10-06T16:02Z`로 읽었다(RED, 세션 time_zone=SYSTEM). driver 속성 `connectionTimeZone=UTC` +
 * `forceConnectionTimeZoneToSession=true`(application.yml)로 모든 JDBC 경로와 세션 time_zone(`+00:00`)을 UTC로 고정했다.
 */
@PaymentDbTest
class UtcStorageSpec(
    private val service: CreatePaymentUseCase,
    private val incidents: IncidentReporter,
    private val payments: JpaPaymentRepository,
    private val controls: JpaOrderPaymentControlRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: DataSource,
    private val db: DbAccess,
) : BehaviorSpec({

    val seoul = ZoneId.of("Asia/Seoul")
    val utc = ZoneId.of("UTC")
    val tx = TransactionTemplate(transactionManager)

    fun evictPool() = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()

    fun <T> withJvmTimeZone(zone: ZoneId, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        evictPool()
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
            evictPool()
        }
    }

    /** DB가 저장한 DATETIME(3) 원문(드라이버 변환 없음). */
    fun raw(sql: String, vararg args: Any?): String = db.withApp { c -> with(db) { c.scalar(sql, *args) as String } }

    /** 한 주문의 생성·만료 갱신·업무 거절·사건 보류까지 지나며 모든 시각 컬럼 원문을 모은다. */
    fun scenario(): Map<String, String> {
        val order: OrderKey = newOrder()
        val cmd = command(order, at = t0)
        val first = service.create(cmd)
            .shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        // 응답(Entity 읽기 경로)도 같은 Instant다.
        first.payment.paymentCreatedAt shouldBe t0
        val paymentId = first.payment.paymentId.value
        val key = cmd.idempotencyKey.value

        val created = linkedMapOf(
            "idempotency.created_at" to raw("SELECT CAST(created_at AS CHAR) FROM idempotency_request WHERE idempotency_key = ?", key),
            "idempotency.expires_at" to raw("SELECT CAST(expires_at AS CHAR) FROM idempotency_request WHERE idempotency_key = ?", key),
            "idempotency.updated_at" to raw("SELECT CAST(updated_at AS CHAR) FROM idempotency_request WHERE idempotency_key = ?", key),
            "control.created_at" to raw(
                "SELECT CAST(created_at AS CHAR) FROM order_payment_control WHERE merchant_account_id = ?", order.merchantAccountId.value,
            ),
            "payment.created_at" to raw("SELECT CAST(created_at AS CHAR) FROM payment WHERE payment_id = ?", paymentId),
            "outbox.occurred_at" to raw("SELECT CAST(occurred_at AS CHAR) FROM outbox_event WHERE aggregate_id = ?", paymentId),
            "outbox.payload.requestedAt" to raw("SELECT payload->>'$.requestedAt' FROM outbox_event WHERE aggregate_id = ?", paymentId),
        )

        // 30일 경계: 1ms 전은 재생(읽은 expires_at과 요청 시각 비교가 시간대에 흔들리지 않음), 정확히 30일은 같은 행 갱신(Entity 경로).
        service.create(cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)).minusMillis(1)))
            .shouldBeInstanceOf<CreatePaymentResult.Accepted>().replayed shouldBe true
        val renewed = service.create(cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)), newIdentifiers = newIdentifiers()))
            .shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        renewed.replayed shouldBe false
        // AUTHN-001(U-3, §24.12): 30일 된 REQUESTED는 inline 만료 → generation 2. 이전 기대: EXISTING_ACTIVE_PAYMENT. 만료 시각·PaymentExpired 시각도 UTC로 저장된다.
        renewed.outcome shouldBe CreatePaymentOutcome.CREATED

        val afterRenewal = linkedMapOf(
            "renewed.idempotency.created_at" to raw("SELECT CAST(created_at AS CHAR) FROM idempotency_request WHERE idempotency_key = ?", key),
            "renewed.idempotency.expires_at" to raw("SELECT CAST(expires_at AS CHAR) FROM idempotency_request WHERE idempotency_key = ?", key),
            "payment.checkout_expires_at" to raw("SELECT CAST(checkout_expires_at AS CHAR) FROM payment WHERE payment_id = ?", paymentId),
            "expired.outbox.occurred_at" to raw("SELECT CAST(occurred_at AS CHAR) FROM outbox_event WHERE effect_key = ?", "PaymentExpired:$paymentId"),
            "expired.outbox.payload.expiredAt" to raw("SELECT payload->>'$.expiredAt' FROM outbox_event WHERE effect_key = ?", "PaymentExpired:$paymentId"),
        )

        // 종료 뒤 늦은 성공 보고: control update(Entity dirty checking, updated_at)와 incident hold insert(Entity 경로). 첫 Payment는 위 갱신 요청의 inline 만료로
        // 이미 EXPIRED다(이전: 시험이 직접 FAILED로 바꿨다 — AUTHN-001 U-3로 준비 단계가 필요 없어졌다, §24.12).
        tx.executeWithoutResult { payments.lock(first.payment.paymentId)!!.value.status shouldBe PaymentStatus.EXPIRED }
        incidents.report(order, first.payment.paymentId, t0.plus(Duration.ofDays(2)))
        val later = linkedMapOf(
            "control.updated_at" to raw(
                "SELECT CAST(updated_at AS CHAR) FROM order_payment_control WHERE merchant_account_id = ?", order.merchantAccountId.value,
            ),
            "hold.created_at" to raw("SELECT CAST(created_at AS CHAR) FROM incident_hold WHERE merchant_account_id = ?", order.merchantAccountId.value),
        )
        return created + afterRenewal + later
    }

    val expected = mapOf(
        "idempotency.created_at" to "2026-10-07 01:02:03.456",
        "idempotency.expires_at" to "2026-11-06 01:02:03.456",
        "idempotency.updated_at" to "2026-10-07 01:02:03.456",
        "control.created_at" to "2026-10-07 01:02:03.456",
        "payment.created_at" to "2026-10-07 01:02:03.456",
        "outbox.occurred_at" to "2026-10-07 01:02:03.456",
        "outbox.payload.requestedAt" to "2026-10-07T01:02:03.456Z",
        "renewed.idempotency.created_at" to "2026-11-06 01:02:03.456",
        "renewed.idempotency.expires_at" to "2026-12-06 01:02:03.456",
        "payment.checkout_expires_at" to "2026-10-07 01:32:03.456",
        "expired.outbox.occurred_at" to "2026-11-06 01:02:03.456",
        "expired.outbox.payload.expiredAt" to "2026-11-06T01:02:03.456Z",
        "control.updated_at" to "2026-10-09 01:02:03.456",
        "hold.created_at" to "2026-10-09 01:02:03.456",
    )

    Given("M-2: the JVM default time zone is not UTC (Asia/Seoul, +09:00)") {
        Then("every DATETIME column (native upsert and Entity paths) stores the UTC wall-clock value, and the 30-day boundary holds") {
            withJvmTimeZone(seoul) {
                // 시간대가 실제로 바뀌었다는 대조: 같은 Instant의 JVM 기본 시간대 표기가 UTC 표기와 다르다.
                LocalDateTime.ofInstant(t0, ZoneId.systemDefault()).toString() shouldBe "2026-10-07T10:02:03.456"
                scenario() shouldBe expected
            }
        }
    }

    Given("M-2: plain JDBC on the application DataSource (the path jOOQ/raw SQL use, without Hibernate's jdbc.time_zone)") {
        Then("Instant parameters are written and DATETIME values are read as UTC even on a non-UTC JVM") {
            val order = newOrder()
            val first = service.create(command(order, at = t0))
                .shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            withJvmTimeZone(seoul) {
                dataSource.connection.use { c ->
                    val written = c.prepareStatement("SELECT CAST(? AS CHAR)").use { ps ->
                        ps.setTimestamp(1, Timestamp.from(t0))
                        ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                    }
                    val read = c.prepareStatement("SELECT created_at FROM payment WHERE payment_id = ?").use { ps ->
                        ps.setString(1, first.payment.paymentId.value)
                        ps.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1).toInstant() }
                    }
                    val sessionZone = c.prepareStatement("SELECT @@session.time_zone").use { ps ->
                        ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                    }
                    mapOf("written" to written, "read" to read.toString(), "session" to sessionZone) shouldBe
                        mapOf("written" to "2026-10-07 01:02:03.456", "read" to "2026-10-07T01:02:03.456Z", "session" to "+00:00")
                }
            }
        }
    }

    Given("M-2: the same scenario on a UTC JVM") {
        Then("the stored values are identical to the non-UTC run (storage does not depend on the JVM time zone)") {
            withJvmTimeZone(utc) { scenario() } shouldBe expected
        }
    }
})
