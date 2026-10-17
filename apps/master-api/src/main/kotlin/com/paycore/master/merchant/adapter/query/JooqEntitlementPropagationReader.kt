package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.ENTITLEMENT_SNAPSHOT_ACK
import com.paycore.master.jooq.generated.Tables.ENTITLEMENT_SNAPSHOT_ISSUE
import com.paycore.master.jooq.generated.Tables.OUTBOX_EVENT
import com.paycore.master.merchant.application.provided.EffectBasis
import com.paycore.master.merchant.application.provided.MerchantPropagationView
import com.paycore.master.merchant.application.provided.PropagationOverview
import com.paycore.master.merchant.application.required.EntitlementPropagationReader
import com.paycore.master.merchant.domain.MerchantId
import org.jooq.Condition
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
 * 권리 전파·효력 확인 조회(CQRS 조회 측, jOOQ — MASTER-SNAPSHOT-001 명세 §3.4-5, §4.8). 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 한 읽기 전용
 * REPEATABLE READ TX에서 여러 문장이 같은 스냅샷을 본다.
 *
 * revision r(가장 최근 변경 발행)의 **전체 효력 확인** = 다음 중 이른 시각:
 * - ACK: 그 Merchant 이벤트의 ACK 중 applied_revision ≥ r, 또는 r 이상 revision 이벤트의 UNSUPPORTED(PAYMENT가 fail-closed가 됨)인 첫 ACK 시각
 * - LEASE_EXPIRY: r 미만 revision으로 발행된 lease의 최댓값(없으면 r의 발행 시각 — 이전 권리가 없다)이 지금 이전이면 그 시각
 * 발행(PUBLISHED)만으로는 확인하지 않는다(R6). 시각은 저장 UTC 벽시계를 `DATE_FORMAT` 문자열로 읽고, 비교 값은 UTC 벽시계 문자열 리터럴
 * `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))`로 묶는다(관례 §9.1·§13 — `LocalDateTime` 바인딩은 Asia/Seoul JVM에서 9시간 앞선 시각과 비교해 끝난 lease를
 * 열림으로 봤다, RED: TZ-FIX-001 `EntitlementPropagationQuerySpec`).
 */
@Component
class JooqEntitlementPropagationReader(private val dsl: DSLContext) : EntitlementPropagationReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun merchant(merchantId: MerchantId, now: Instant): MerchantPropagationView? {
        val i = ENTITLEMENT_SNAPSHOT_ISSUE
        val a = ENTITLEMENT_SNAPSHOT_ACK
        val latest = dsl.select(i.REVISION, instant(i.ISSUED_AT))
            .from(i)
            .where(i.MERCHANT_ID.eq(merchantId.value).and(i.LEASE_SEQ.eq(0L)))
            .orderBy(i.REVISION.desc())
            .limit(1)
            .fetchOne() ?: return null
        val revision = latest.value1()
        val issuedAt = Instant.parse(latest.value2())

        val latestAck = dsl.select(a.APPLIED_REVISION, instant(a.ACKED_AT))
            .from(a).join(i).on(a.OUTBOX_EVENT_ID.eq(i.OUTBOX_EVENT_ID))
            .where(i.MERCHANT_ID.eq(merchantId.value))
            .orderBy(a.APPLIED_REVISION.desc(), a.ACKED_AT.desc())
            .limit(1)
            .fetchOne()

        val ackConfirmedAt = dsl.select(instant(DSL.min(a.ACKED_AT)))
            .from(a).join(i).on(a.OUTBOX_EVENT_ID.eq(i.OUTBOX_EVENT_ID))
            .where(i.MERCHANT_ID.eq(merchantId.value).and(confirmsRevision(DSL.inline(revision))))
            .fetchOne()?.value1()?.let(Instant::parse)

        val priorLeaseMax = dsl.select(instant(DSL.max(i.LEASE_UNTIL)))
            .from(i)
            .where(i.MERCHANT_ID.eq(merchantId.value).and(i.REVISION.lt(revision)))
            .fetchOne()?.value1()?.let(Instant::parse)

        val leaseBasis = (priorLeaseMax ?: issuedAt).takeIf { !it.isAfter(now) }
        val (confirmedAt, basis) = when {
            ackConfirmedAt != null && (leaseBasis == null || !ackConfirmedAt.isAfter(leaseBasis)) -> ackConfirmedAt to EffectBasis.ACK
            leaseBasis != null -> leaseBasis to EffectBasis.LEASE_EXPIRY
            else -> null to null
        }
        return MerchantPropagationView(
            merchantId = merchantId.value,
            latestRevision = revision,
            latestRevisionIssuedAt = issuedAt,
            latestAckRevision = latestAck?.value1(),
            latestAckAt = latestAck?.value2()?.let(Instant::parse),
            priorLeaseMaxUntil = priorLeaseMax,
            effectConfirmedAt = confirmedAt,
            effectBasis = basis,
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun overview(now: Instant): PropagationOverview {
        val o = OUTBOX_EVENT
        val i = ENTITLEMENT_SNAPSHOT_ISSUE
        val a = ENTITLEMENT_SNAPSHOT_ACK
        val snapshots = o.EVENT_CATEGORY.eq(EVENT_CATEGORY)
        val pending = dsl.select(DSL.count(), instant(DSL.min(o.CREATED_AT)))
            .from(o).where(snapshots.and(o.PUBLISH_STATUS.eq("PENDING")))
            .fetchSingle()
        val quarantined = dsl.selectCount().from(o).where(snapshots.and(o.PUBLISH_STATUS.eq("QUARANTINED"))).fetchSingle().value1()

        // 각 Merchant의 최신 변경 revision 중 ACK로도, 이전 lease 만료로도 확인되지 않은 것의 수.
        val latest = dsl.select(i.MERCHANT_ID.`as`("m"), DSL.max(i.REVISION).`as`("r")).from(i).groupBy(i.MERCHANT_ID).asTable("latest")
        val m = latest.field("m", String::class.java)!!
        val r = latest.field("r", Long::class.java)!!
        val i2 = ENTITLEMENT_SNAPSHOT_ISSUE.`as`("i2")
        val i3 = ENTITLEMENT_SNAPSHOT_ISSUE.`as`("i3")
        val acked = DSL.exists(
            DSL.selectOne().from(a).join(i2).on(a.OUTBOX_EVENT_ID.eq(i2.OUTBOX_EVENT_ID))
                .where(i2.MERCHANT_ID.eq(m).and(a.APPLIED_REVISION.ge(r).or(unsupported(a).and(i2.REVISION.ge(r))))),
        )
        val priorLeaseOpen = DSL.exists(
            DSL.selectOne().from(i3).where(i3.MERCHANT_ID.eq(m).and(i3.REVISION.lt(r)).and(i3.LEASE_UNTIL.gt(utcLiteral(now)))),
        )
        val unconfirmed = dsl.selectCount().from(latest).where(DSL.not(acked).and(priorLeaseOpen)).fetchSingle().value1()

        return PropagationOverview(
            pendingSnapshots = pending.value1().toLong(),
            oldestPendingCreatedAt = pending.value2()?.let(Instant::parse),
            quarantinedSnapshots = quarantined.toLong(),
            unconfirmedLatestRevisions = unconfirmed.toLong(),
        )
    }

    private fun confirmsRevision(revision: Field<Long>): Condition {
        val i = ENTITLEMENT_SNAPSHOT_ISSUE
        val a = ENTITLEMENT_SNAPSHOT_ACK
        return a.APPLIED_REVISION.ge(revision).or(unsupported(a).and(i.REVISION.ge(revision)))
    }

    /** PAYMENT가 그 이벤트를 UNSUPPORTED로 처리했다(처음 응답이든, 재전송 DUPLICATE의 original_outcome이든 — review m-3). */
    private fun unsupported(a: com.paycore.master.jooq.generated.tables.EntitlementSnapshotAck): Condition =
        a.OUTCOME.eq("UNSUPPORTED").or(a.ORIGINAL_OUTCOME.eq("UNSUPPORTED"))

    /** 비교용 UTC 벽시계 리터럴(JooqAuthorizationTermsCoverageReader와 같은 방식 — 바인딩하지 않아 시간대 변환 없음). */
    private fun utcLiteral(at: Instant): Field<LocalDateTime?> =
        DSL.field("CAST({0} AS DATETIME(3))", LocalDateTime::class.java, DSL.inline(UTC_WALL_CLOCK.format(at)))

    /** DATETIME(3) 컬럼(또는 집계)의 저장 UTC 벽시계를 ISO-8601 `…Z` 문자열로 읽는다(JooqMerchantStructureReader와 같은 이유). */
    private fun instant(field: Field<LocalDateTime?>): Field<String?> =
        DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    private companion object {
        const val EVENT_CATEGORY = "ENTITLEMENT_SNAPSHOT"
        val UTC_WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
