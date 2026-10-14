package com.paycore.payment.entitlement.adapter.query

import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import com.paycore.payment.entitlement.application.required.EntitlementFreshnessReader
import com.paycore.payment.jooq.generated.Tables.ENTITLEMENT_HEAD
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 권리 투영 신선도 조회(CQRS 조회 측, jOOQ — 명세 §4.8·§11). 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 한 읽기 전용 TX. 판정용이 아니다
 * (판정은 EntitlementEvaluationService가 head FOR SHARE로). 시각 비교 값은 UTC LocalDateTime(저장 값이 UTC 벽시계), 잔여는 SQL TIMESTAMPDIFF로
 * 계산해 JVM 시간대 변환을 끼우지 않는다(관례 §9.1).
 */
@Component
class JooqEntitlementFreshnessReader(private val dsl: DSLContext) : EntitlementFreshnessReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun overview(now: Instant): FreshnessOverview {
        val h = ENTITLEMENT_HEAD
        val at = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        val filled = h.REVISION.gt(0L)
        val usable = filled.and(h.BLOCKED_REASON.isNull)
        val remaining = DSL.field("TIMESTAMPDIFF(MICROSECOND, {0}, {1}) DIV 1000", Long::class.java, DSL.inline(at), h.LEASE_UNTIL)
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
}
