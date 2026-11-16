package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TRANSACTION
import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.required.HomeKpiReader
import com.paycore.payment.payment.domain.HomeKpiFigures
import com.paycore.payment.payment.domain.HomeKpiWindow
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 운영 홈 KPI 집계(ADMIN-HOME-KPI-001 §3·§9 — jOOQ CQRS 조회 측, **쓰기·잠금 없음**, 비권위). **한 읽기 전용 REPEATABLE READ TX**의 2문장이 같은 InnoDB 스냅샷을 본다(첫 읽기에 고정 —
 * T-K1-10). 두 문장 모두 [AdminReadLimits.HINT](MySQL `MAX_EXECUTION_TIME(2000)` — 문장별, 최악 ~4초)와 [AdminReadLimits.guard](시간 초과 → `QueryTimedOut`).
 * - S1 합계: `payment_transaction t FORCE INDEX(ix_payment_transaction_confirmed)`(V35 — `type, confirmed_at` 범위, covering) STRAIGHT_JOIN `payment p`(PK eq_ref — environment·status) →
 *   `GROUP BY environment, currency, type`의 `SUM(amount_minor)`. 조건 `p.status = 'AUTHORIZED'`가 미확정·종료 Payment와 종료 Payment의 늦은 승인·그 보정 취소를 함께 뺀다
 *   (D-ADMIN-HOME-002 ①). CANCEL 거래만 차감하므로 취소 OPEN·UNKNOWN·FAILED·FORCED(거래 없음)는 자동 제외(INV-C2, D-ADMIN-HOME-002 ②).
 * - S2 건수: 같은 범위의 AUTHORIZATION 중 `amount_minor > COALESCE((asOf 이전 CANCEL 합 — c2 FORCE INDEX(fk_payment_transaction_payment) 상관 subquery), 0)`인 행 수(§3.3 — INV-C8).
 * - 시각은 [UtcWallClock.literal](바인딩 시간대 변환 없음, ARCHITECTURE-CONVENTIONS §9.1). SUM은 MySQL DECIMAL 그대로 `BigDecimal` → [HomeKpiDecimal.exact](CAST·float·Long 없음 — T-K1-14).
 * - SQL ORDER BY 없음(정렬은 application — filesort 없음, §9). GROUP BY의 임시 표는 행 수(환경 × 통화 × 타입)가 작아 허용.
 */
@Component
class JooqHomeKpiReader(private val dsl: DSLContext) : HomeKpiReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun read(window: HomeKpiWindow): List<HomeKpiItem> = AdminReadLimits.guard {
        val t = PAYMENT_TRANSACTION.`as`("t")
        val p = PAYMENT.`as`("p")
        val from = UtcWallClock.literal(window.from)
        val to = UtcWallClock.literal(window.asOf)
        val sum = DSL.sum(t.AMOUNT_MINOR).`as`(SUM_ALIAS)
        val sums = dsl.select(p.ENVIRONMENT, t.CURRENCY, t.TYPE, sum)
            .hint(AdminReadLimits.HINT)
            .from(t.forceIndex(CONFIRMED_INDEX))
            .straightJoin(p).on(p.PAYMENT_ID.eq(t.PAYMENT_ID))
            .where(t.TYPE.`in`(AUTHORIZATION, CANCEL)).and(t.CONFIRMED_AT.ge(from)).and(t.CONFIRMED_AT.lt(to)).and(p.STATUS.eq(AUTHORIZED))
            .groupBy(p.ENVIRONMENT, t.CURRENCY, t.TYPE)
            .fetch()
        val c2 = PAYMENT_TRANSACTION.`as`("c2")
        val cancelledBefore = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(c2.AMOUNT_MINOR), DSL.inline(BigDecimal.ZERO)))
                .from(c2.forceIndex(PAYMENT_FK_INDEX))
                .where(c2.PAYMENT_ID.eq(t.PAYMENT_ID)).and(c2.TYPE.eq(CANCEL)).and(c2.CONFIRMED_AT.lt(to)),
        ).coerce(SQLDataType.BIGINT)
        val count = DSL.count().`as`(COUNT_ALIAS)
        val counts = dsl.select(p.ENVIRONMENT, t.CURRENCY, count)
            .hint(AdminReadLimits.HINT)
            .from(t.forceIndex(CONFIRMED_INDEX))
            .straightJoin(p).on(p.PAYMENT_ID.eq(t.PAYMENT_ID))
            .where(t.TYPE.eq(AUTHORIZATION)).and(t.CONFIRMED_AT.ge(from)).and(t.CONFIRMED_AT.lt(to)).and(p.STATUS.eq(AUTHORIZED))
            .and(t.AMOUNT_MINOR.gt(cancelledBefore))
            .groupBy(p.ENVIRONMENT, t.CURRENCY)
            .fetch()
            .associate { (it.get(p.ENVIRONMENT) to it.get(t.CURRENCY)) to it.get(count).toLong() }
        val approved = mutableMapOf<Pair<String, String>, BigInteger>()
        val cancelled = mutableMapOf<Pair<String, String>, BigInteger>()
        sums.forEach { r ->
            val key = r.get(p.ENVIRONMENT) to r.get(t.CURRENCY)
            val total = HomeKpiDecimal.exact(checkNotNull(r.get(sum)) { "SUM of a group is never null" })
            when (r.get(t.TYPE)) {
                AUTHORIZATION -> approved[key] = total
                CANCEL -> cancelled[key] = total
                else -> error("unexpected payment_transaction type")
            }
        }
        val keys = approved.keys + cancelled.keys
        check(keys.containsAll(counts.keys)) { "a counted approval must be in the approved sum" }
        keys.map { key ->
            HomeKpiItem(key.first, key.second, HomeKpiFigures(approved[key] ?: BigInteger.ZERO, cancelled[key] ?: BigInteger.ZERO, counts[key] ?: 0L))
        }
    }

    private companion object {
        const val CONFIRMED_INDEX = "ix_payment_transaction_confirmed"
        const val PAYMENT_FK_INDEX = "fk_payment_transaction_payment"
        const val AUTHORIZATION = "AUTHORIZATION"
        const val CANCEL = "CANCEL"
        const val AUTHORIZED = "AUTHORIZED"
        const val SUM_ALIAS = "home_kpi_sum"
        const val COUNT_ALIAS = "home_kpi_count"
    }
}

/** SQL `SUM(BIGINT)`(MySQL 결과 형식 DECIMAL) → 정확한 정수(T-K1-14). 소수부가 있으면 `ArithmeticException`(fail-closed) — float·Long 변환 경로 없음. */
internal object HomeKpiDecimal {
    fun exact(sum: BigDecimal): BigInteger = sum.toBigIntegerExact()
}
