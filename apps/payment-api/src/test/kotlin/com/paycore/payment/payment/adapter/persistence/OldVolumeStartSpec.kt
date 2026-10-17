package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.PaymentDbAccounts
import com.paycore.payment.payment.application.CardAuthenticationTestConfig
import com.paycore.payment.payment.application.EligibilityTestConfig
import com.paycore.payment.payment.application.SiteWebHostTestConfig
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CheckoutWindow
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 옛 볼륨 기동(AUTHN-001 §7.5.6, 중간 검토 #2 m-2 — S11 종료 기준, m-C 왕복): V6까지 적용되고 결제창 시각 없는 REQUESTED 행이 있는 DB에서 **애플리케이션 컨텍스트 한 번의
 * 기동**이 V8 → pin → 채우기(runtime 계정, 애플리케이션 단계) → V8_1과 나머지를 적용한다. 채운 시각은 JPA Repository 재조회로 domain 값이 된다(m-C). 시각이 이미 지난 행은
 * 채우기만 되고 다음 만료 작업이 EXPIRED + PaymentExpired 1건으로 만든다. 다른 상태의 V7 이전 행은 NULL로 남는다. 그 뒤 DB는 REQUESTED + NULL을 3819로 막는다.
 * 컨테이너는 이 시험 전용이다(컨텍스트 기동 전에 V6 schema와 옛 행을 만든다 — 운영 초기화 스크립트 계정·운영 migration 위치 그대로).
 */
@SpringBootTest
@Import(OldVolumeStartSpec.OldVolumeConfig::class, EligibilityTestConfig::class, SiteWebHostTestConfig::class, CardAuthenticationTestConfig::class)
class OldVolumeStartSpec(
    private val payments: PaymentRepository,
    private val job: ExpireElapsedRequestedPaymentsUseCase,
    private val container: MySQLContainer,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val json = JsonMapper.builder().build()

    fun <T> root(block: (Connection) -> T): T = PaymentDbAccounts.root(container).use { it.catalog = PaymentDbAccounts.PAYMENT_DATABASE; block(it) }

    fun scalar(sql: String, vararg args: Any?): Any? = root { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null } }
    }

    Given("a volume stopped at V6 with pre-V7 REQUESTED rows, started once") {
        Then("the start applied V8, pinned the setting, back-filled REQUESTED rows only and then applied V8_1 — all in one start, no failed migration row") {
            root { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT GROUP_CONCAT(version ORDER BY installed_rank), MIN(success) FROM flyway_schema_history WHERE version IS NOT NULL").use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "1,2,3,4,5,6,7,8,8.1"
                        rs.getBoolean(2) shouldBe true
                    }
                }
            }
            scalar("SELECT CONCAT(validity_version, '/', validity_duration_ms) FROM payment_checkout_window_pin") shouldBe "checkout-validity-demo-1/1800000"
        }

        Then("m-C round trip: the back-filled window reloads through the JPA repository as the domain value; other statuses keep no window") {
            val pending = tx.execute { payments.lock(PaymentId(PENDING))!!.value }
            pending.status shouldBe PaymentStatus.REQUESTED
            pending.checkoutWindow shouldBe CheckoutWindow(PENDING_CREATED.plusSeconds(1800), "checkout-validity-demo-1")
            tx.execute { payments.lock(PaymentId(ELAPSED))!!.value }.checkoutWindow shouldBe CheckoutWindow(ELAPSED_CREATED.plusSeconds(1800), "checkout-validity-demo-1")
            listOf(AUTHORIZING, EXPIRED).forEach { id ->
                scalar("SELECT CONCAT_WS('/', checkout_expires_at, checkout_validity_version) FROM payment WHERE payment_id = ?", id) shouldBe ""
            }
        }

        Then("the next expiry run expires the already-elapsed back-filled row with one PaymentExpired; the pending row stays REQUESTED") {
            val runId = UUID.randomUUID().toString()
            job.expireElapsed(ExpireElapsedRequestedPaymentsCommand(runId, 50))
            scalar("SELECT status FROM payment WHERE payment_id = ?", ELAPSED) shouldBe "EXPIRED"
            scalar("SELECT status FROM payment WHERE payment_id = ?", PENDING) shouldBe "REQUESTED"
            scalar("SELECT status FROM payment WHERE payment_id = ?", AUTHORIZING) shouldBe "AUTHORIZING"
            val payload = json.readTree(scalar("SELECT payload FROM outbox_event WHERE effect_key = ?", "PaymentExpired:$ELAPSED").toString())
            payload.get("checkoutValidityVersion").stringValue() shouldBe "checkout-validity-demo-1"
            payload.get("checkoutExpiresAt").stringValue() shouldBe "2020-01-01T00:30:00.000Z"
            scalar("SELECT COUNT(*) FROM outbox_event WHERE effect_key = ?", "PaymentExpired:$PENDING").toString() shouldBe "0"
        }

        Then("afterwards the database refuses a REQUESTED row without a window (3819)") {
            val denied = runCatching { root { c -> insertLegacy(c, "pay-after-v81", "REQUESTED", Instant.parse("2026-10-16T00:00:00Z")) } }.exceptionOrNull()
            generateSequence(denied) { it.cause }.filterIsInstance<SQLException>().first().errorCode shouldBe 3819
        }
    }
}) {
    @TestConfiguration(proxyBeanMethods = false)
    class OldVolumeConfig {
        /** 이 시험 전용 컨테이너: 운영 초기화 스크립트 계정 → Flyway target 6(migration 계정) → V7 이전 행(root) — 그 뒤 컨텍스트가 기동한다. */
        @Bean
        fun oldVolumeContainer(): MySQLContainer = PaymentDbAccounts.newContainer().also { container ->
            container.start()
            Flyway.configure()
                .dataSource(
                    PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
                    PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
                )
                .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
                .initSql("SET time_zone = '+00:00'")
                .target("6")
                .load()
                .migrate()
            PaymentDbAccounts.root(container).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { it.execute("SET time_zone = '+00:00'") }
                insertLegacy(c, PENDING, "REQUESTED", PENDING_CREATED)
                insertLegacy(c, ELAPSED, "REQUESTED", ELAPSED_CREATED)
                insertLegacy(c, AUTHORIZING, "AUTHORIZING", ELAPSED_CREATED)
                insertLegacy(c, EXPIRED, "EXPIRED", ELAPSED_CREATED)
            }
        }

        @Bean
        fun oldVolumeDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
            registry.add("PAYCORE_PAYMENT_DB_URL") { PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE) }
            registry.add("PAYCORE_PAYMENT_DB_USERNAME") { PaymentDbAccounts.PAYMENT_RUNTIME_USER }
            registry.add("PAYCORE_PAYMENT_DB_PASSWORD") { PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER) }
            registry.add("PAYCORE_PAYMENT_DB_MIGRATION_USERNAME") { PaymentDbAccounts.PAYMENT_MIGRATION_USER }
            registry.add("PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD") { PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER) }
        }
    }

    companion object {
        const val PENDING = "pay-oldvol-pending"
        const val ELAPSED = "pay-oldvol-elapsed"
        const val AUTHORIZING = "pay-oldvol-authorizing"
        const val EXPIRED = "pay-oldvol-expired"

        /** 결제창 시각이 아직 남은 V7 이전 REQUESTED(지금 − 5분 생성 → 채운 시각 = 생성 + 30분). */
        val PENDING_CREATED: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(300)
        val ELAPSED_CREATED: Instant = Instant.parse("2020-01-01T00:00:00Z")

        /** V6 schema 그대로의 행(결제창 시각 컬럼 없음) + 주문 control(만료 작업이 control → payment로 잠근다). */
        fun insertLegacy(c: Connection, id: String, status: String, createdAt: Instant) {
            val at = LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC)
            c.prepareStatement(
                """INSERT INTO payment (payment_id, payment_key, environment, merchant_account_id, merchant_order_id, merchant_site_id, amount_minor, currency,
                   payment_method, order_name, order_intent_fingerprint, slot_generation, status, eligibility_snapshot_version, created_at, version)
                   VALUES (?, ?, 'TEST', 'mid-oldvol', ?, 'demo-site-001', 10000, 'KRW', 'CARD', NULL, 'v1:fp-oldvol', 1, ?, 'snap-1', ?, 0)""",
            ).use { ps ->
                ps.setString(1, id); ps.setString(2, "key-$id"); ps.setString(3, "order-$id"); ps.setString(4, status); ps.setObject(5, at)
                check(ps.executeUpdate() == 1)
            }
            c.prepareStatement(
                """INSERT INTO order_payment_control (environment, merchant_account_id, merchant_order_id, fixed_intent_fingerprint, current_payment_id, generation,
                   authorized_payment_id, created_at, updated_at, version) VALUES ('TEST', 'mid-oldvol', ?, 'v1:fp-oldvol', ?, 1, NULL, ?, ?, 0)""",
            ).use { ps ->
                ps.setString(1, "order-$id"); ps.setString(2, id); ps.setObject(3, at); ps.setObject(4, at)
                check(ps.executeUpdate() == 1)
            }
        }
    }
}
