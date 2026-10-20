package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.PaymentDbAccounts
import com.paycore.payment.payment.application.required.CheckoutValidityPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDateTime

/**
 * payment-api Flyway 적용 전략(AUTHN-001 §7.5.6, 중간 검토 #2 m-2 — Coordinator 판정 option (c), m-A 대체): V8_1(REQUESTED ⇒ 결제창 시각 NOT NULL)이 pending이면
 * **한 번의 기동 안에서** ① V8까지 적용(migration 계정) → ② 채우기 기준 pin(없을 때만 — 설정 버전·유효기간) → ③ 결제창 시각 없는 REQUESTED 행 채우기(`created_at` +
 * pin 유효기간, pin 버전 — runtime 계정·좁은 JDBC 단계, 애플리케이션 단계이지 migration 상수가 아니다 D-AUTHN-007) → ④ 나머지(V8_1과 그 뒤 전부) 적용.
 * 채우기가 실패하면 기동 실패(fail-closed)이고 실패한 migration 기록(success = 0)은 없다. 시각이 이미 지난 행의 만료는 전략이 아니라 이후 만료 작업이 한다.
 *
 * 운영 초기화 스크립트 계정과 운영 migration 위치를 그대로 쓴다(Spring 컨텍스트 없이 전략만). Spring 컨텍스트 전체로 같은 옛 볼륨을 한 번에 기동하는 시험·채우기 →
 * JPA 재조회 왕복(m-C)은 S11 옛 볼륨 시험이 한다.
 */
class CheckoutWindowCheckMigrationStrategySpec : FunSpec({

    val container = PaymentDbAccounts.newContainer().also { it.start() }
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

    /** 앱 datasource와 같은 runtime 계정(표 단위 권한)·UTC 세션. */
    val runtime = DriverManagerDataSource(
        PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE) + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        PaymentDbAccounts.PAYMENT_RUNTIME_USER, PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER),
    )

    fun policy(version: String, duration: Duration) = object : CheckoutValidityPolicy {
        override val version = version
        override val duration = duration
    }

    fun <T> root(block: (Connection) -> T): T = PaymentDbAccounts.root(container).use { it.catalog = PaymentDbAccounts.PAYMENT_DATABASE; block(it) }

    fun history(): List<Pair<String, Boolean>> = root { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getBoolean(2)) }
            }
        }
    }

    fun windowOf(id: String): Triple<LocalDateTime?, String?, Long> = root { c ->
        c.prepareStatement("SELECT checkout_expires_at, checkout_validity_version, version FROM payment WHERE payment_id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.next(); Triple(rs.getObject(1, LocalDateTime::class.java), rs.getString(2), rs.getLong(3)) }
        }
    }

    fun pin(): Pair<String, Long>? = root { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT validity_version, validity_duration_ms FROM payment_checkout_window_pin").use { rs ->
                if (rs.next()) rs.getString(1) to rs.getLong(2) else null
            }
        }
    }

    fun insertLegacy(c: Connection, id: String, status: String, createdAt: String = "2026-10-07 00:00:00.000") {
        c.prepareStatement(
            """INSERT INTO payment (payment_id, payment_key, environment, merchant_account_id, merchant_order_id, merchant_site_id, amount_minor, currency,
               payment_method, order_name, order_intent_fingerprint, slot_generation, status, eligibility_snapshot_version, created_at, version)
               VALUES (?, ?, 'TEST', 'mid-legacy', ?, 'demo-site-001', 10000, 'KRW', 'CARD', NULL, 'v1:fp', 1, ?, 'snap-1', ?, 0)""",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, "key-$id"); ps.setString(3, "order-$id"); ps.setString(4, status); ps.setString(5, createdAt)
            ps.executeUpdate() shouldBe 1
        }
    }

    test("an old volume stopped at V6 with REQUESTED rows lacking a window reaches V8_1 in ONE start: pin, backfill REQUESTED only, then the rest") {
        flyway(target = "6").migrate()
        root { c ->
            insertLegacy(c, "pay-legacy-requested", "REQUESTED", "2026-10-07 00:00:00.123")
            insertLegacy(c, "pay-legacy-requested-elapsed", "REQUESTED", "2020-01-01 00:00:00.000")
            insertLegacy(c, "pay-legacy-authorizing", "AUTHORIZING")
            insertLegacy(c, "pay-legacy-expired", "EXPIRED")
        }

        CheckoutWindowCheckMigrationStrategy(runtime, policy("checkout-validity-demo-1", Duration.ofMinutes(30))).migrate(flyway())

        history().map { it.first } shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "8", "8.1", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20")
        history().all { it.second } shouldBe true // 실패한 migration 기록 없음
        pin() shouldBe ("checkout-validity-demo-1" to 1_800_000L)
        // REQUESTED만 created_at + pin 유효기간·pin 버전(ms 보존), version + 1. 시각이 이미 지난 행도 채우기만 한다(만료는 이후 만료 작업).
        windowOf("pay-legacy-requested") shouldBe Triple(LocalDateTime.parse("2026-10-07T00:30:00.123"), "checkout-validity-demo-1", 1L)
        windowOf("pay-legacy-requested-elapsed") shouldBe Triple(LocalDateTime.parse("2020-01-01T00:30:00"), "checkout-validity-demo-1", 1L)
        root { c -> c.createStatement().use { it.executeQuery("SELECT status FROM payment WHERE payment_id = 'pay-legacy-requested-elapsed'").use { rs -> rs.next(); rs.getString(1) } } } shouldBe
            "REQUESTED"
        windowOf("pay-legacy-authorizing") shouldBe Triple(null, null, 0L)
        windowOf("pay-legacy-expired") shouldBe Triple(null, null, 0L)

        // 다음 기동: 설정이 바뀌어도 pin은 그대로(배포 시점 버전), 적용할 것이 없다.
        CheckoutWindowCheckMigrationStrategy(runtime, policy("checkout-validity-demo-2", Duration.ofMinutes(10))).migrate(flyway())
        pin() shouldBe ("checkout-validity-demo-1" to 1_800_000L)
        history().size shouldBe 21

        val denied = runCatching { root { c -> insertLegacy(c, "pay-new-without-window", "REQUESTED") } }.exceptionOrNull()
        (generateSequence(denied) { it.cause }.filterIsInstance<SQLException>().first()).errorCode shouldBe 3819
    }

    /** 이 시험 전용 새 볼륨(V6 + 옛 REQUESTED 1행). */
    fun freshOldVolume(): Pair<org.testcontainers.mysql.MySQLContainer, org.flywaydb.core.api.configuration.FluentConfiguration> {
        val other = PaymentDbAccounts.newContainer().also { it.start() }
        val config = Flyway.configure()
            .dataSource(
                PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
                PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
            )
            .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
            .initSql("SET time_zone = '+00:00'")
        config.target("6").load().migrate()
        PaymentDbAccounts.root(other).use { c -> c.catalog = PaymentDbAccounts.PAYMENT_DATABASE; insertLegacy(c, "pay-race-first", "REQUESTED") }
        return other to config
    }

    fun historyOf(other: org.testcontainers.mysql.MySQLContainer): Pair<String, Boolean> = PaymentDbAccounts.root(other).use { c ->
        c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
        c.createStatement().use { st ->
            st.executeQuery("SELECT GROUP_CONCAT(version ORDER BY installed_rank), MIN(success) FROM flyway_schema_history WHERE version IS NOT NULL").use { rs ->
                rs.next(); rs.getString(1) to rs.getBoolean(2)
            }
        }
    }

    fun runtimeOf(other: org.testcontainers.mysql.MySQLContainer) = DriverManagerDataSource(
        PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE) + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        PaymentDbAccounts.PAYMENT_RUNTIME_USER, PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER),
    )

    test("review #3 m-B: a pre-AUTHN-001 insert between the fill and V8_1 is filled again (bounded) and the start still reaches V8_1 with no failed row") {
        val (other, config) = freshOldVolume()
        try {
            var injected = 0
            val strategy = object : CheckoutWindowCheckMigrationStrategy(runtimeOf(other), policy("checkout-validity-demo-1", Duration.ofMinutes(30))) {
                override fun beforeCheckRecheck(attempt: Int) {
                    if (injected++ == 0) PaymentDbAccounts.root(other).use { c -> c.catalog = PaymentDbAccounts.PAYMENT_DATABASE; insertLegacy(c, "pay-race-late", "REQUESTED") }
                }
            }
            strategy.migrate(config.target("latest").load())
            historyOf(other) shouldBe ("1,2,3,4,5,6,7,8,8.1,9,10,11,12,13,14,15,16,17,18,19,20" to true)
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM payment WHERE status = 'REQUESTED' AND checkout_expires_at IS NULL").use { rs -> rs.next(); rs.getInt(1) shouldBe 0 } }
            }
        } finally {
            other.stop()
        }
    }

    test("review #3 m-B: if pre-AUTHN-001 inserts keep arriving, start-up is refused cleanly without attempting V8_1 (no success = 0 row)") {
        val (other, config) = freshOldVolume()
        try {
            var n = 0
            val strategy = object : CheckoutWindowCheckMigrationStrategy(runtimeOf(other), policy("checkout-validity-demo-1", Duration.ofMinutes(30))) {
                override fun beforeCheckRecheck(attempt: Int) {
                    PaymentDbAccounts.root(other).use { c -> c.catalog = PaymentDbAccounts.PAYMENT_DATABASE; insertLegacy(c, "pay-race-${n++}", "REQUESTED") }
                }
            }
            shouldThrow<IllegalStateException> { strategy.migrate(config.target("latest").load()) }
            historyOf(other) shouldBe ("1,2,3,4,5,6,7,8" to true)
        } finally {
            other.stop()
        }
    }

    test("if the backfill cannot run, start-up fails before V8_1 and no failed migration row is recorded (fail-closed)") {
        val other = PaymentDbAccounts.newContainer().also { it.start() }
        try {
            val otherFlyway = Flyway.configure()
                .dataSource(
                    PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
                    PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
                )
                .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
                .initSql("SET time_zone = '+00:00'")
            otherFlyway.target("6").load().migrate()
            // 채우기 계정이 접속할 수 없다(잘못된 비밀번호) — 채우기·pin 불가.
            val broken = DriverManagerDataSource(PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_RUNTIME_USER, "wrong")
            shouldThrow<RuntimeException> {
                CheckoutWindowCheckMigrationStrategy(broken, policy("checkout-validity-demo-1", Duration.ofMinutes(30))).migrate(otherFlyway.target("latest").load())
            }
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st ->
                    st.executeQuery("SELECT GROUP_CONCAT(version ORDER BY installed_rank), MIN(success) FROM flyway_schema_history WHERE version IS NOT NULL").use { rs ->
                        rs.next()
                        (rs.getString(1) to rs.getBoolean(2)) shouldBe ("1,2,3,4,5,6,7,8" to true)
                    }
                }
            }
        } finally {
            other.stop()
        }
    }

    // CANCEL-001 §8.6: AUTH-001 배포 상태(V14 적용됨)의 볼륨에 승인 거래 행이 있어도 한 번의 기동에 V15~V19까지 간다 — V17이 승인 열을 NULL 허용으로 바꾸고 type별 CHECK를
    // 새로 걸어도 기존 AUTHORIZATION 행은 그대로 통과한다(취소 열 NULL, 생성 열 cancel_txn_key NULL). 거래 행만 보려고 FK 검사를 끈 root 세션으로 넣는다(시험 준비 — 다른 표 행 없음).
    test("a volume at V14 (AUTH-001 deployed) with an authorization transaction reaches the latest version (V20) in one start; the existing row passes the V17 checks unchanged") {
        val other = PaymentDbAccounts.newContainer().also { it.start() }
        try {
            val config = Flyway.configure()
                .dataSource(
                    PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
                    PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
                )
                .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
                .initSql("SET time_zone = '+00:00'")
            config.target("14").load().migrate()
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st ->
                    st.execute("SET FOREIGN_KEY_CHECKS = 0")
                    st.execute(
                        """INSERT INTO payment_transaction (transaction_id, type, payment_id, authorization_id, amount_minor, currency, plan_kind, installment_months, card_type,
                           approval_number, provider_transaction_id, institution_transaction_date, institution_business_date, source_attempt_id, source_kind, source_result_id,
                           recorded_by, confirmed_at) VALUES ('ptx-v14', 'AUTHORIZATION', 'pay-v14', 'auth-v14', 10000, 'KRW', 'INSTALLMENT', 3, 'CREDIT', 'A0001', 'PTX-V14',
                           '2026-10-07', NULL, 'att-v14', 'RESPONSE', 'dec-1', 'RESOLUTION', '2026-10-07 00:00:01.000')""",
                    )
                }
            }
            CheckoutWindowCheckMigrationStrategy(runtimeOf(other), policy("checkout-validity-demo-1", Duration.ofMinutes(30))).migrate(config.target("latest").load())
            historyOf(other) shouldBe ("1,2,3,4,5,6,7,8,8.1,9,10,11,12,13,14,15,16,17,18,19,20" to true)
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT CONCAT_WS('/', type, plan_kind, installment_months, card_type, approval_number), cancellation_id, cancel_scope, cancel_txn_key " +
                            "FROM payment_transaction WHERE transaction_id = 'ptx-v14'",
                    ).use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "AUTHORIZATION/INSTALLMENT/3/CREDIT/A0001"
                        listOf(rs.getString(2), rs.getString(3), rs.getString(4)) shouldBe listOf(null, null, null)
                    }
                }
            }
        } finally {
            other.stop()
        }
    }

    // AUTH-001 rev 0.3 m-5: AUTHN-001 배포 상태(V8_1 적용됨)의 볼륨이 한 번의 기동에 AUTH-001 migration(V9~V11)까지 간다 — 전략의 "V8_1 이미 적용" 경로.
    // 기존 incident_hold 행은 occurrence 1·여는 관찰 NULL(legacy), 기존 종료 근거는 출처 NULL(legacy), CreatePayment 멱등 행은 새 CHECK를 통과한다.
    test("a volume at V8_1 (AUTHN-001 deployed) with orders, holds and evidence reaches V11 in one start; existing rows become legacy rows") {
        val other = PaymentDbAccounts.newContainer().also { it.start() }
        try {
            val config = Flyway.configure()
                .dataSource(
                    PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE), PaymentDbAccounts.PAYMENT_MIGRATION_USER,
                    PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
                )
                .locations("classpath:db/migration/payment", "classpath:db/runtime-privilege/payment")
                .initSql("SET time_zone = '+00:00'")
            config.target("8.1").load().migrate()
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st ->
                    st.execute(
                        """INSERT INTO payment (payment_id, payment_key, environment, merchant_account_id, merchant_order_id, merchant_site_id, amount_minor, currency,
                           payment_method, order_name, order_intent_fingerprint, slot_generation, status, eligibility_snapshot_version, created_at, version)
                           VALUES ('pay-v81-failed', 'key-v81', 'TEST', 'mid-v81', 'order-v81', 'demo-site-001', 10000, 'KRW', 'CARD', NULL, 'v1:fp', 1, 'FAILED', 'snap-1',
                           '2026-10-07 00:00:00.000', 1)""",
                    )
                    st.execute("INSERT INTO payment_termination_evidence (payment_id, seq, evidence) VALUES ('pay-v81-failed', 0, 'INSTITUTION_CONFIRMED_DECLINE')")
                    st.execute(
                        """INSERT INTO order_payment_control (environment, merchant_account_id, merchant_order_id, fixed_intent_fingerprint, current_payment_id, generation,
                           authorized_payment_id, created_at, updated_at, version) VALUES ('TEST', 'mid-v81', 'order-v81', 'v1:fp', 'pay-v81-failed', 1, NULL,
                           '2026-10-07 00:00:00.000', '2026-10-07 00:00:00.000', 0)""",
                    )
                    st.execute(
                        """INSERT INTO incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, created_at)
                           VALUES ('TEST', 'mid-v81', 'order-v81', 'pay-v81-failed', 'SUCCESS_AFTER_TERMINATION', '2026-10-07 00:00:01.000')""",
                    )
                    st.execute(
                        """INSERT INTO idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id, request_fingerprint, result_kind,
                           result_code, result_payment_id, created_at, expires_at, updated_at, version) VALUES ('TEST', 'mid-v81', 'CreatePayment', 'k-v81', 'rr', 'r1:x',
                           'SUCCESS', 'CREATED', 'pay-v81-failed', '2026-10-07 00:00:00.000', '2026-11-06 00:00:00.000', '2026-10-07 00:00:00.000', 0)""",
                    )
                }
            }
            val runtimeOther = DriverManagerDataSource(
                PaymentDbAccounts.urlFor(other, PaymentDbAccounts.PAYMENT_DATABASE) + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                PaymentDbAccounts.PAYMENT_RUNTIME_USER, PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER),
            )
            CheckoutWindowCheckMigrationStrategy(runtimeOther, policy("checkout-validity-demo-1", Duration.ofMinutes(30))).migrate(config.target("latest").load())
            historyOf(other) shouldBe ("1,2,3,4,5,6,7,8,8.1,9,10,11,12,13,14,15,16,17,18,19,20" to true)
            PaymentDbAccounts.root(other).use { c ->
                c.catalog = PaymentDbAccounts.PAYMENT_DATABASE
                c.createStatement().use { st ->
                    st.executeQuery("SELECT occurrence, source_attempt_id FROM incident_hold WHERE payment_id = 'pay-v81-failed'").use { rs ->
                        rs.next(); rs.getInt(1) shouldBe 1; rs.getString(2) shouldBe null
                    }
                    st.executeQuery("SELECT source_kind FROM payment_termination_evidence WHERE payment_id = 'pay-v81-failed'").use { rs -> rs.next(); rs.getString(1) shouldBe null }
                    st.executeQuery("SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key = 'k-v81'").use { rs -> rs.next(); rs.getInt(1) shouldBe 1 }
                }
            }
        } finally {
            other.stop()
        }
    }
})
