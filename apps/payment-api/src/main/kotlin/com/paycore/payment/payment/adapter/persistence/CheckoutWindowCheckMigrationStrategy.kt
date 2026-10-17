package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.CheckoutValidityPolicy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * payment-api Flyway 적용 전략(AUTHN-001 §7.5.6, 중간 검토 #2 m-2 — Coordinator 판정 option (c), 이전 m-A "다음 기동이 V8_1 적용" 판정을 대체).
 *
 * V8_1(`CHECK (status <> 'REQUESTED' OR checkout_expires_at IS NOT NULL)`)이 pending이면 **한 번의 기동 안에서, EntityManagerFactory 기동 전에**:
 * 1. V8까지 적용(migration 계정 — Flyway 설정 그대로).
 * 2. 채우기 기준 pin: `payment_checkout_window_pin`에 행이 없을 때만 지금 활성 설정의 버전·유효기간을 INSERT(여러 인스턴스는 PK 한 행 — 먼저 들어간 값이 유일, m-B).
 * 3. 결제창 시각 없는 REQUESTED 행 채우기: `created_at + pin 유효기간`, pin 버전, `version + 1`(D-AUTHN-007 — 애플리케이션 단계, migration 상수 아님). 2·3은 앱 runtime
 *    계정(표 단위 권한: pin SELECT·INSERT, payment UPDATE — 새 권한 없음, 관례 §9.2)의 좁은 JDBC 단계이며 한 로컬 TX다.
 * 4. 나머지 적용(V8_1과 그 뒤 migration 전부 — 뒤 migration이 V8_1에 막혀 보류되지 않는다, AUTH-001 인계 §22).
 *
 * 채우기·pin이 실패하면 예외로 기동이 실패한다(fail-closed). Flyway 기록에는 성공한 V8까지만 남는다(실패 행 success = 0 없음 — MySQL DDL은 TX가 아니라 실패 기록은
 * `flyway repair`가 필요해진다). 시각이 이미 지난 행은 채우기만 하고 만료는 이후 만료 작업이 한다(전략은 Payment 상태를 바꾸지 않는다).
 * V8_1이 이미 적용된 볼륨은 그대로 migrate한 뒤 pin만 확인한다(없으면 고정 — "V7 뒤 첫 기동이 고정", 검토 #2 n-2).
 */
@Component
open class CheckoutWindowCheckMigrationStrategy(
    private val runtimeDataSource: DataSource,
    private val validity: CheckoutValidityPolicy,
) : FlywayMigrationStrategy {

    private val log = LoggerFactory.getLogger(CheckoutWindowCheckMigrationStrategy::class.java)

    override fun migrate(flyway: Flyway) {
        val checkPending = flyway.info().pending().any { it.version?.version == CHECK_VERSION }
        if (!checkPending) {
            flyway.migrate()
            if (pinTableExists(flyway)) inTransaction { c -> ensurePin(c) }
            return
        }
        Flyway.configure().configuration(flyway.configuration).target(MigrationVersion.fromVersion(LAST_BEFORE_CHECK)).load().migrate()
        val (pinVersion, filled) = inTransaction { c ->
            val pin = ensurePin(c)
            pin.first to fill(c, pin)
        }
        log.info("operation=CheckoutWindowBackfill result=filled rows={} validityVersion={} then=recheck", filled, pinVersion)
        // 검토 #3 m-B: 채우기 커밋과 V8_1 ALTER 사이에 AUTHN-001 이전 인스턴스가 시각 없는 REQUESTED를 넣으면 ALTER가 3819로 실패해 success = 0 기록이 남는다.
        // V8_1 직전에 다시 확인해 다시 채우고(최대 [MAX_REFILLS]회), 그래도 남으면 V8_1을 시도하지 않고 기동을 거부한다(실패 기록 없음). 배포 조건: §20 R13.
        var attempt = 1
        while (true) {
            beforeCheckRecheck(attempt)
            if (!inTransaction { c -> requestedWithoutWindowExists(c) }) break
            check(attempt <= MAX_REFILLS) {
                "REQUESTED payments without a checkout window keep appearing (a pre-AUTHN-001 instance is still running?); start-up refused before V$CHECK_VERSION"
            }
            val refilled = inTransaction { c -> fill(c, ensurePin(c)) }
            log.warn("operation=CheckoutWindowBackfill result=refilled rows={} attempt={} cause=REQUESTED_WITHOUT_WINDOW_AFTER_FILL", refilled, attempt)
            attempt++
        }
        flyway.migrate()
    }

    /** V8_1 직전 재확인 지점(시험이 채우기와 ALTER 사이의 경합을 주입하는 hook — 운영 구현은 아무것도 하지 않는다). */
    protected open fun beforeCheckRecheck(attempt: Int) = Unit

    private fun requestedWithoutWindowExists(c: Connection): Boolean =
        c.prepareStatement("SELECT EXISTS (SELECT 1 FROM payment WHERE status = 'REQUESTED' AND checkout_expires_at IS NULL)").use { ps ->
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    private fun pinTableExists(flyway: Flyway): Boolean =
        flyway.info().applied().any { v -> v.version?.let { it.isAtLeast(PIN_TABLE_VERSION) } == true }

    /** pin을 읽고, 없으면 지금 설정으로 INSERT한다. 다른 인스턴스가 먼저 넣었으면(1062) 그 값을 읽는다. (version, duration ms). */
    private fun ensurePin(c: Connection): Pair<String, Long> {
        readPin(c)?.let { return it }
        val inserted = try {
            c.prepareStatement(
                "INSERT INTO payment_checkout_window_pin (pin_id, validity_version, validity_duration_ms, pinned_at) VALUES (1, ?, ?, UTC_TIMESTAMP(3))",
            ).use { ps ->
                ps.setString(1, validity.version)
                ps.setLong(2, validity.duration.toMillis())
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.errorCode != MYSQL_DUPLICATE_KEY) throw e
            0
        }
        if (inserted == 1) log.info("operation=CheckoutWindowPin result=pinned validityVersion={}", validity.version)
        return checkNotNull(readPin(c)) { "the checkout window pin must exist after insert" }
    }

    private fun readPin(c: Connection): Pair<String, Long>? =
        c.prepareStatement("SELECT validity_version, validity_duration_ms FROM payment_checkout_window_pin WHERE pin_id = 1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getLong(2) else null }
        }

    private fun fill(c: Connection, pin: Pair<String, Long>): Int =
        c.prepareStatement(
            "UPDATE payment SET checkout_expires_at = DATE_ADD(created_at, INTERVAL ? MICROSECOND), checkout_validity_version = ?, version = version + 1 " +
                "WHERE status = 'REQUESTED' AND checkout_expires_at IS NULL",
        ).use { ps ->
            ps.setLong(1, Math.multiplyExact(pin.second, MICROS_PER_MILLI))
            ps.setString(2, pin.first)
            ps.executeUpdate()
        }

    /** runtime 계정 로컬 TX 하나. 실패는 기동 실패다 — 원인 분류(vendor 코드)만 남기고 메시지 원문은 원인 사슬에 둔다. */
    private fun <T> inTransaction(block: (Connection) -> T): T = try {
        runtimeDataSource.connection.use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                runCatching { c.rollback() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        }
    } catch (e: SQLException) {
        throw IllegalStateException("checkout window pin/backfill failed (vendorCode=${e.errorCode}); start-up refused before V$CHECK_VERSION (D-AUTHN-007)", e)
    }

    private companion object {
        const val CHECK_VERSION = "8.1"
        const val LAST_BEFORE_CHECK = "8"
        const val PIN_TABLE_VERSION = "7"
        const val MYSQL_DUPLICATE_KEY = 1062
        const val MICROS_PER_MILLI = 1_000L
        const val MAX_REFILLS = 3
    }
}
