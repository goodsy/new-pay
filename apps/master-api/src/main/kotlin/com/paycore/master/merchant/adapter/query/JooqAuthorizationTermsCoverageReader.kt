package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.CARD_ROUTE_ACTIVATION
import com.paycore.master.jooq.generated.Tables.CURRENCY_ENABLEMENT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.jooq.generated.Tables.PAYMENT_METHOD_ACTIVATION
import com.paycore.master.jooq.generated.Tables.POLICY_ASSIGNMENT
import com.paycore.master.jooq.generated.Tables.TRANSACTION_LIMIT_POLICY
import com.paycore.master.merchant.application.provided.TermsCoverageOverview
import com.paycore.master.merchant.application.required.AuthorizationTermsCoverageReader
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
 * 승인 시작 조건 누락 점검(CQRS 조회 측, jOOQ — MASTER-SNAPSHOT-002 명세 §9 Reconciliation·Observability). 쓰기 없음, 읽기 전용 REPEATABLE READ 한 TX.
 * 결과는 운영 점검용(비권위)이다 — 승인 판정은 PAYMENT 로컬 투영으로만 한다.
 *
 * - ACTIVE MID의 지금 유효한 통화 활성화 (MID, 통화) 중 지금 적용 한도 정책(그 MID Override 또는 발급 CSA 기본)이 없는 수
 * - 같은 (MID, 통화) 중 지금 유효한 수수료 / 정산 정책 Assignment가 없는 수
 * - ACTIVE 결제수단 개통이 있는데 ACTIVE 카드사 개통이 하나도 없는 (MID, 수단) 수
 */
@Component
class JooqAuthorizationTermsCoverageReader(private val dsl: DSLContext) : AuthorizationTermsCoverageReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun overview(now: Instant): TermsCoverageOverview {
        // 비교 시각은 UTC 벽시계 문자열 리터럴로 묶는다: LocalDateTime 바인딩은 드라이버가 JVM 기본 시간대로 해석해 connectionTimeZone(UTC)으로
        // 옮길 수 있어(JVM이 UTC가 아니면 9시간 어긋남) 저장 UTC 벽시계와 직접 비교한다.
        val at = utcLiteral(now)
        val ce = CURRENCY_ENABLEMENT
        val ma = MERCHANT_ACCOUNT
        val tlp = TRANSACTION_LIMIT_POLICY
        val pas = POLICY_ASSIGNMENT

        val enabledNow = effective(ce.EFFECTIVE_FROM, ce.EFFECTIVE_TO, at).and(ma.LIFECYCLE.eq("ACTIVE"))
        val limitNow = DSL.exists(
            DSL.selectOne().from(tlp).where(
                tlp.CURRENCY_CODE.eq(ce.CURRENCY_CODE).and(effective(tlp.EFFECTIVE_FROM, tlp.EFFECTIVE_TO, at)).and(
                    tlp.SCOPE_KIND.eq("MID_OVERRIDE").and(tlp.MERCHANT_ACCOUNT_ID.eq(ce.MERCHANT_ACCOUNT_ID))
                        .or(tlp.SCOPE_KIND.eq("CSA_DEFAULT").and(tlp.AGREEMENT_ID.eq(ma.ISSUING_AGREEMENT_ID))),
                ),
            ),
        )
        fun assignmentNow(kind: String): Condition = DSL.exists(
            DSL.selectOne().from(pas).where(
                pas.MERCHANT_ACCOUNT_ID.eq(ce.MERCHANT_ACCOUNT_ID).and(pas.CURRENCY_CODE.eq(ce.CURRENCY_CODE)).and(pas.POLICY_KIND.eq(kind))
                    .and(effective(pas.EFFECTIVE_FROM, pas.EFFECTIVE_TO, at)),
            ),
        )
        fun countEnabled(missing: Condition): Long =
            dsl.selectCount().from(ce).join(ma).on(ma.MERCHANT_ACCOUNT_ID.eq(ce.MERCHANT_ACCOUNT_ID)).where(enabledNow.and(missing)).fetchSingle().value1().toLong()

        val pma = PAYMENT_METHOD_ACTIVATION
        val cra = CARD_ROUTE_ACTIVATION
        val methodsWithoutRoute = dsl.selectCount().from(pma).join(ma).on(ma.MERCHANT_ACCOUNT_ID.eq(pma.MERCHANT_ACCOUNT_ID))
            .where(
                pma.STATUS.eq("ACTIVE").and(ma.LIFECYCLE.eq("ACTIVE")).and(
                    DSL.notExists(
                        DSL.selectOne().from(cra).where(
                            cra.MERCHANT_ACCOUNT_ID.eq(pma.MERCHANT_ACCOUNT_ID).and(cra.PAYMENT_METHOD.eq(pma.PAYMENT_METHOD)).and(cra.STATUS.eq("ACTIVE")),
                        ),
                    ),
                ),
            ).fetchSingle().value1().toLong()

        return TermsCoverageOverview(
            enabledCurrenciesWithoutLimitPolicy = countEnabled(DSL.not(limitNow)),
            enabledCurrenciesWithoutFeeAssignment = countEnabled(DSL.not(assignmentNow("FEE_SCHEDULE"))),
            enabledCurrenciesWithoutSettlementAssignment = countEnabled(DSL.not(assignmentNow("SETTLEMENT_POLICY"))),
            activeMethodsWithoutCardRoute = methodsWithoutRoute,
        )
    }

    private fun effective(from: Field<LocalDateTime?>, to: Field<LocalDateTime?>, at: Field<LocalDateTime?>): Condition =
        from.le(at).and(to.isNull.or(to.gt(at)))

    private fun utcLiteral(now: Instant): Field<LocalDateTime?> =
        DSL.field("CAST({0} AS DATETIME(3))", LocalDateTime::class.java, DSL.inline(UTC_WALL_CLOCK.format(now)))

    private companion object {
        val UTC_WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
