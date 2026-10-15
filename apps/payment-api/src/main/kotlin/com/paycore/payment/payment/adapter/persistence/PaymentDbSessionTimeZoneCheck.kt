package com.paycore.payment.payment.adapter.persistence

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * PAYMENT DB 세션·JDBC 시각 변환 검사(CP-DB-001 review2 L-10, CP-API-001 명세 §11.3, review round1 m-1).
 *
 * 모든 시각 컬럼(`DATETIME(3)`)은 UTC 벽시계다. Hikari driver 속성(`connectionTimeZone=UTC`, `forceConnectionTimeZoneToSession=true`)이 JDBC 변환과
 * 세션 `time_zone`을 맞춘다(관례 §9.1). 설정이 어디서 오든(URL 질의·URL host 부분 `address=(…)(connectionTimeZone=…)`·퍼센트 인코딩·Hikari
 * data-source 속성) **실제 동작**을 기동 때 확인한다:
 * 1. 효과 검사(우회 불가): 풀 연결에서 (a) `@@session.time_zone` = `+00:00`, (b) 왕복 probe — 알려진 순간을 plain JDBC(`Timestamp`)로 바인딩해
 *    서버가 그 값을 UTC 벽시계(`2000-01-02 03:04:05.678`)로 받는지, 같은 벽시계 문자열을 읽으면 같은 순간이 되는지. 어느 하나라도 다르면 기동 실패.
 *    (b)는 세션이 +00:00이어도 driver 변환 시간대가 다르면(예: `connectionTimeZone`을 다른 값으로, 세션은 `sessionVariables`로 UTC) 잡는다.
 * 2. 조기 거부: datasource URL에 시간대 관련 이름이 있으면(디코딩 뒤 URL 전체에서 대소문자 무시) 기동 실패 — 배포 규칙(TECH-BASELINE §8) 위반을
 *    분명한 메시지로 알린다. 이것은 보조 검사이며 보장은 1이 한다.
 * 같은 효과 검사를 health(`paymentDbSessionTimeZone`)로도 노출한다(세부는 health 세부 비공개 설정으로 외부에 보이지 않는다).
 */
@Component("paymentDbSessionTimeZone")
class PaymentDbSessionTimeZoneCheck(
    private val dataSource: DataSource,
    private val properties: DataSourceProperties,
) : SmartInitializingSingleton, HealthIndicator {

    private val log = LoggerFactory.getLogger(PaymentDbSessionTimeZoneCheck::class.java)

    override fun afterSingletonsInstantiated() {
        val forbidden = forbiddenUrlParameters(properties.url)
        check(forbidden.isEmpty()) { "PAYMENT datasource URL must not carry time-zone parameters: $forbidden (the driver properties set UTC)" }
        val problem = probe()
        if (problem != null) {
            log.error("operation=PaymentDbSessionTimeZoneCheck result=FAILED problem={}", problem)
            error(problem)
        }
    }

    override fun health(): Health {
        val problem = probe()
        return if (problem == null) Health.up().build() else Health.down().withDetail("problem", problem).build()
    }

    /** 문제 설명(값만, 비밀 없음) 또는 null(정상). */
    private fun probe(): String? {
        dataSource.connection.use { c ->
            val zone = c.scalar("SELECT @@session.time_zone", emptyList())
            if (zone != UTC_SESSION) return "PAYMENT DB session time_zone must be $UTC_SESSION but was $zone"
            val written = c.scalar("SELECT DATE_FORMAT(?, '%Y-%m-%d %H:%i:%s.%f')", listOf(Timestamp.from(PROBE_INSTANT)))
            if (written != PROBE_WALL_CLOCK_WRITE) return "PAYMENT DB JDBC time conversion is not UTC: bound $PROBE_INSTANT arrived as $written"
            val read = c.prepareStatement("SELECT CAST('$PROBE_WALL_CLOCK' AS DATETIME(3))").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
            }
            if (read != PROBE_INSTANT) return "PAYMENT DB JDBC time conversion is not UTC: $PROBE_WALL_CLOCK was read as $read"
        }
        return null
    }

    private fun Connection.scalar(sql: String, args: List<Any>): String? = prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    companion object {
        const val UTC_SESSION = "+00:00"
        private val PROBE_INSTANT: Instant = Instant.parse("2000-01-02T03:04:05.678Z")
        private const val PROBE_WALL_CLOCK = "2000-01-02 03:04:05.678"
        private const val PROBE_WALL_CLOCK_WRITE = "2000-01-02 03:04:05.678000"

        /** Connector/J 시간대 관련 속성 이름(소문자). `sessionvariables`는 time_zone을 담을 때만. */
        private val TIME_ZONE_PARAMETERS = listOf("connectiontimezone", "servertimezone", "forceconnectiontimezonetosession", "preserveinstants")

        /**
         * URL 전체(질의·host 부분)에서 시간대 관련 속성 이름을 찾는다. 퍼센트 인코딩은 풀어서 본다(풀 수 없으면 원문으로). 보조 검사다 —
         * 실제 보장은 기동 효과 검사([probe])다.
         */
        fun forbiddenUrlParameters(url: String?): List<String> {
            if (url == null) return emptyList()
            val decoded = try {
                URLDecoder.decode(url, StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                url
            }.lowercase()
            val found = TIME_ZONE_PARAMETERS.filter { it in decoded }.toMutableList()
            if (Regex("sessionvariables\\s*=[^&]*time_zone").containsMatchIn(decoded)) found += "sessionvariables"
            return found
        }
    }
}
