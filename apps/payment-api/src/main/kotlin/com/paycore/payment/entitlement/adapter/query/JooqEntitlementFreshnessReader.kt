package com.paycore.payment.entitlement.adapter.query

import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import com.paycore.payment.entitlement.application.required.EntitlementFreshnessReader
import com.paycore.payment.jooq.generated.Tables.ENTITLEMENT_HEAD
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 권리 투영 신선도 조회(CQRS 조회 측, jOOQ — 명세 §4.8·§11). 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 한 읽기 전용 TX. 판정용이 아니다
 * (판정은 EntitlementEvaluationService가 head FOR SHARE로). 시각 비교 값은 UTC 벽시계 문자열 리터럴 `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))`로
 * 묶고(바인딩하지 않는다), 잔여는 SQL TIMESTAMPDIFF로 계산해 JVM 시간대 변환을 끼우지 않는다(관례 §9.1·§13 — `LocalDateTime` 바인딩은 Asia/Seoul JVM에서
 * 9시간 앞선 시각과 비교했다, RED: TZ-FIX-001 `EntitlementFreshnessQuerySpec`). 모듈 경계상 payment 모듈의 `UtcWallClock`을 쓰지 않고 같은 방식을 둔다.
 */
@Component
class JooqEntitlementFreshnessReader(private val dsl: DSLContext) : EntitlementFreshnessReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun overview(now: Instant): FreshnessOverview {
        val h = ENTITLEMENT_HEAD
        val at = utcLiteral(now)
        val filled = h.REVISION.gt(0L)
        val usable = filled.and(h.BLOCKED_REASON.isNull)
        val remaining = DSL.field("TIMESTAMPDIFF(MICROSECOND, {0}, {1}) DIV 1000", Long::class.java, at, h.LEASE_UNTIL)
        val row = dsl.select(
            DSL.count().filterWhere(filled),
            DSL.count().filterWhere(filled.and(h.BLOCKED_REASON.isNotNull)),
            DSL.count().filterWhere(usable.and(h.LEASE_UNTIL.le(at))),
            DSL.min(remaining).filterWhere(usable.and(h.LEASE_UNTIL.gt(at))),
        ).from(h).fetchSingle()
        return FreshnessOverview(
            heads = row.value1().toLong(),
            blockedHeads = row.value2().toLong(),
            expiredHeads = row.value3().toLong(),
            minLeaseRemainingMillis = row.value4(),
        )
    }

    /** 비교용 UTC 벽시계 리터럴(JooqAuthorizationTermsCoverageReader·UtcWallClock과 같은 방식 — 시간대 변환 없음). */
    private fun utcLiteral(now: Instant): Field<LocalDateTime?> =
        DSL.field("CAST({0} AS DATETIME(3))", LocalDateTime::class.java, DSL.inline(UTC_WALL_CLOCK.format(now)))

    private companion object {
        val UTC_WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
