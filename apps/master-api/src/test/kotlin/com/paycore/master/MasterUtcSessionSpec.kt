package com.paycore.master

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.persistence.EntityManagerFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import javax.sql.DataSource

/**
 * MASTER DB 시각 저장 기준(UTC) — payment-api와 같은 관례(CP-DB-001 M-2)를 master-api 설정에 적용했는지 실제 MySQL로 확인한다.
 *
 * 시험 컨테이너 OS 시간대는 Asia/Seoul이라 MySQL 세션 기본값(SYSTEM)은 +09:00이다. 따라서 아래 '+00:00'은 설정의 결과다.
 * - 앱 pool: driver 속성 `connectionTimeZone=UTC` + `forceConnectionTimeZoneToSession=true`(JPA 밖 jOOQ·raw JDBC 경로 포함).
 * - Hibernate: `hibernate.jdbc.time_zone=UTC`.
 * - Flyway: 계정 분리로 Flyway는 migration 계정의 **별도 연결**을 만든다(앱 pool의 driver 속성이 넘어가지 않는다).
 *   `spring.flyway.init-sqls`로 그 세션도 UTC로 맞춘다(시험 전용 V900이 migration 세션의 time_zone을 기록).
 */
@MasterDbTest
class MasterUtcSessionSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val entityManagerFactory: EntityManagerFactory,
) : FunSpec({

    val t0: Instant = Instant.parse("2026-10-08T01:02:03.456Z")

    fun evictPool() = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()

    test("control: the MySQL server default time zone is not UTC (container OS time zone Asia/Seoul)") {
        jdbcTemplate.queryForObject("SELECT @@global.time_zone", String::class.java) shouldBe "SYSTEM"
        jdbcTemplate.queryForObject("SELECT @@system_time_zone", String::class.java) shouldBe "KST"
    }

    test("application pool sessions run with time_zone +00:00") {
        jdbcTemplate.queryForObject("SELECT @@session.time_zone", String::class.java) shouldBe "+00:00"
    }

    test("Flyway migration session (migration account, separate connection) runs with time_zone +00:00") {
        jdbcTemplate.queryForObject(
            "SELECT session_zone FROM tech_session_probe WHERE probe_id = 'flyway-migration'",
            String::class.java,
        ) shouldBe "+00:00"
    }

    test("Hibernate binds and reads time values as UTC") {
        entityManagerFactory.properties["hibernate.jdbc.time_zone"].toString() shouldBe "UTC"
    }

    test("plain JDBC on the application DataSource writes and reads Instant as UTC even on a non-UTC JVM") {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(MasterDbAccounts.CONTAINER_TIME_ZONE)))
        evictPool()
        try {
            ZoneId.systemDefault() shouldNotBe ZoneId.of("UTC")
            dataSource.connection.use { c ->
                val written = c.prepareStatement("SELECT CAST(? AS CHAR)").use { ps ->
                    ps.setTimestamp(1, Timestamp.from(t0))
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
                val read = c.prepareStatement("SELECT CAST('2026-10-08 01:02:03.456' AS DATETIME(3))").use { ps ->
                    ps.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1).toInstant() }
                }
                mapOf("written" to written, "read" to read) shouldBe
                    mapOf("written" to "2026-10-08 01:02:03.456", "read" to t0)
            }
        } finally {
            TimeZone.setDefault(original)
            evictPool()
        }
    }
})
