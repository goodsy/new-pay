package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD_RELEASE
import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.jooq.generated.Tables.PAYMENT_AUTHORIZATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_CANCELLATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TRANSACTION
import com.paycore.payment.jooq.generated.Tables.RETURN_BALANCE
import com.paycore.payment.payment.application.required.AuthorizationStatusRow
import com.paycore.payment.payment.application.required.CancellationHistoryRow
import com.paycore.payment.payment.application.required.PaymentStatusReader
import com.paycore.payment.payment.application.required.PaymentStatusRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRow
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 결제 상태 조회(CQRS 조회 측, jOOQ — CP-QUERY-001 §6). 쓰기·잠금 없음(queryAdapterDoesNotWriteWithJooq), **비권위** 표시값.
 * - 범위: `WHERE payment_id = ? AND environment = ? AND merchant_account_id = ?` — 범위 밖 행은 없음과 같은 null(CQ-02, 존재 비노출).
 * - 일관성(CQ-05b (2) — 명세 §18 편차 D-S2-1): Payment·승인·승인 거래·반환 잔액·hold 여부를 한 문장, 취소 이력을 두 번째 문장으로 읽되 **한 REPEATABLE READ 읽기 전용 TX**에서 읽는다 —
 *   InnoDB 일관 읽기 스냅샷은 TX의 첫 읽기 시점에 고정되므로 두 문장이 같은 시점을 본다(같은 TX2가 함께 커밋한 행들이 한 시점으로 보인다). Use Case TX의 READ COMMITTED와 구분해 명시한다.
 *   jOOQ `multiset`(한 문장)은 MySQL 렌더링이 `group_concat_max_len` 세션 변수를 바꾸는 다중 문장을 만들어 driver에서 거부됐다(RED 기록) — 쓰지 않는다.
 * - 시각은 [UtcWallClock] 문자열 읽기, 날짜는 `DATE_FORMAT('%Y-%m-%d')` 문자열(시간대 해석 없음 — 관례 §9.1·§13, TZ-FIX-001). 시각 비교는 없다(D-CPQ-004 — 조회 기간 제한 없음).
 * - 활성 hold = 해제 기록 없는 그 Payment의 `incident_hold` 행(domain `activeIncidentHolds`와 같은 정의 — D-CPQ-001).
 */
@Component
class JooqPaymentStatusReader(private val dsl: DSLContext) : PaymentStatusReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow? = try {
        query(environment, merchantAccountId, paymentId)
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    @Suppress("LongMethod")
    private fun query(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow? {
        val p = PAYMENT
        val a = PAYMENT_AUTHORIZATION
        val t = PAYMENT_TRANSACTION.`as`("t")
        val b = RETURN_BALANCE
        val h = INCIDENT_HOLD
        val r = INCIDENT_HOLD_RELEASE

        val createdAt = UtcWallClock.iso(p.CREATED_AT).`as`("created_at_utc")
        val approvedAt = UtcWallClock.iso(t.CONFIRMED_AT).`as`("approved_at_utc")
        val txDate = date(t.INSTITUTION_TRANSACTION_DATE).`as`("tx_date")
        val businessDate = date(t.INSTITUTION_BUSINESS_DATE).`as`("business_date")
        val activeHold = DSL.field(
            DSL.exists(
                DSL.selectOne().from(h).where(
                    h.ENVIRONMENT.eq(p.ENVIRONMENT).and(h.MERCHANT_ACCOUNT_ID.eq(p.MERCHANT_ACCOUNT_ID)).and(h.MERCHANT_ORDER_ID.eq(p.MERCHANT_ORDER_ID))
                        .and(h.PAYMENT_ID.eq(p.PAYMENT_ID))
                        .and(DSL.notExists(DSL.selectOne().from(r).where(r.INCIDENT_HOLD_ID.eq(h.ID)))),
                ),
            ),
        ).`as`("active_hold")
        val main = dsl.select(
            p.PAYMENT_ID, p.MERCHANT_ORDER_ID, p.AMOUNT_MINOR, p.CURRENCY, p.STATUS, createdAt, activeHold,
            a.AUTHORIZATION_ID, a.STATUS, a.FAILURE_EVIDENCE,
            t.TRANSACTION_ID, t.AMOUNT_MINOR, t.CURRENCY, t.PLAN_KIND, t.INSTALLMENT_MONTHS, t.CARD_TYPE, t.APPROVAL_NUMBER, t.PROVIDER_TRANSACTION_ID, txDate, businessDate,
            approvedAt,
            b.PAYMENT_ID, b.AUTHORIZED_MINOR, b.COMPLETED_MINOR, b.RESERVED_MINOR, b.FORCED_MINOR,
        )
            .from(p)
            .leftJoin(a).on(a.PAYMENT_ID.eq(p.PAYMENT_ID))
            // 승인 거래만(Authorization당 하나 — 생성 열 UNIQUE). 취소 거래는 이력 쪽.
            .leftJoin(t).on(t.AUTHORIZATION_ID.eq(a.AUTHORIZATION_ID).and(t.TYPE.eq("AUTHORIZATION")))
            .leftJoin(b).on(b.PAYMENT_ID.eq(p.PAYMENT_ID))
            .where(p.PAYMENT_ID.eq(paymentId.value).and(p.ENVIRONMENT.eq(environment.name)).and(p.MERCHANT_ACCOUNT_ID.eq(merchantAccountId.value)))
            .fetchOne() ?: return null
        // 같은 RR 읽기 전용 TX의 두 번째 문장 — 첫 문장이 연 스냅샷 그대로(그 사이 커밋은 보이지 않는다).
        val history = historyRows(environment, merchantAccountId, paymentId)
        return main.let { row ->
                val authorization = row.get(a.AUTHORIZATION_ID)?.let { id ->
                    val terms = row.get(t.TRANSACTION_ID)?.let {
                        ApprovedTerms(
                            money = Money(row.get(t.AMOUNT_MINOR), CurrencyCode(row.get(t.CURRENCY))),
                            plan = if (row.get(t.PLAN_KIND) == "INSTALLMENT") PaymentPlan.Installment(row.get(t.INSTALLMENT_MONTHS)) else PaymentPlan.LumpSum,
                            cardType = CardType.valueOf(checkNotNull(row.get(t.CARD_TYPE))),
                            approvalNumber = checkNotNull(row.get(t.APPROVAL_NUMBER)),
                            providerTransactionId = row.get(t.PROVIDER_TRANSACTION_ID),
                            institutionTransactionDate = LocalDate.parse(row.get(txDate)),
                            institutionBusinessDate = row.get(businessDate)?.let(LocalDate::parse),
                        )
                    }
                    AuthorizationStatusRow(
                        authorizationId = AuthorizationId(id),
                        status = AuthorizationStatus.valueOf(row.get(a.STATUS)),
                        failureEvidence = row.get(a.FAILURE_EVIDENCE)?.let(TerminationEvidence::valueOf),
                        approved = terms,
                        approvedAt = UtcWallClock.parse(row.get(approvedAt)),
                    )
                }
                val balance = row.get(b.PAYMENT_ID)?.let {
                    ReturnBalanceRow(row.get(b.AUTHORIZED_MINOR), row.get(b.COMPLETED_MINOR), row.get(b.RESERVED_MINOR), row.get(b.FORCED_MINOR))
                }
                PaymentStatusRow(
                    paymentId = PaymentId(row.get(p.PAYMENT_ID)),
                    merchantOrderId = MerchantOrderId(row.get(p.MERCHANT_ORDER_ID)),
                    money = Money(row.get(p.AMOUNT_MINOR), CurrencyCode(row.get(p.CURRENCY))),
                    status = PaymentStatus.valueOf(row.get(p.STATUS)),
                    createdAt = checkNotNull(UtcWallClock.parse(row.get(createdAt))),
                    activeHold = row.get(activeHold) == true,
                    authorization = authorization,
                    balance = balance,
                    cancellations = history,
                )
            }
    }

    /**
     * 취소 이력 문장(검토 #1 m-1): 본 행 문장의 결과에 기대지 않고 **스스로** 범위를 건다 — payment와 `payment_id`·`environment`·`merchant_account_id`로 join.
     * [find]의 두 번째 문장이며, 이력 문장만의 범위 시험을 위해 따로 부를 수 있다(같은 읽기 전용 RR TX 규칙). 비권위.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun cancellationHistory(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): List<CancellationHistoryRow> = try {
        historyRows(environment, merchantAccountId, paymentId)
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    private fun historyRows(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): List<CancellationHistoryRow> {
        val p = PAYMENT
        val c = PAYMENT_CANCELLATION
        val ct = PAYMENT_TRANSACTION.`as`("ct")
        val openedAt = UtcWallClock.iso(c.OPENED_AT).`as`("opened_at_utc")
        val cancelledAt = UtcWallClock.iso(ct.CONFIRMED_AT).`as`("cancelled_at_utc")
        return dsl.select(
            c.CANCELLATION_ID, c.INITIATOR_KIND, c.MERCHANT_CANCEL_ID, c.STATUS, c.AMOUNT_MINOR, c.CURRENCY, c.FAILURE_EVIDENCE, openedAt,
            ct.TRANSACTION_ID, ct.APPROVAL_NUMBER, cancelledAt,
        )
            .from(c)
            .join(p).on(
                p.PAYMENT_ID.eq(c.PAYMENT_ID).and(p.ENVIRONMENT.eq(environment.name)).and(p.MERCHANT_ACCOUNT_ID.eq(merchantAccountId.value)),
            )
            .leftJoin(ct).on(ct.CANCELLATION_ID.eq(c.CANCELLATION_ID).and(ct.TYPE.eq("CANCEL")))
            .where(c.PAYMENT_ID.eq(paymentId.value))
            .orderBy(c.OPENED_AT, c.CANCELLATION_ID)
            .fetch { x ->
                CancellationHistoryRow(
                    cancellationId = CancellationId(x.value1()),
                    byOperator = x.value2() == "OPERATOR",
                    merchantCancelId = x.value3()?.let(::MerchantCancelId),
                    status = CancellationStatus.valueOf(x.value4()),
                    money = Money(x.value5(), CurrencyCode(x.value6())),
                    failureEvidence = x.value7()?.let(TerminationEvidence::valueOf),
                    openedAt = checkNotNull(UtcWallClock.parse(x.value8())),
                    cancelTransactionId = x.value9(),
                    cancelApprovalNumber = x.value10(),
                    confirmedAt = UtcWallClock.parse(x.value11()),
                )
            }
    }

    private fun date(field: Field<*>): Field<String> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%d')", String::class.java, field)
}
