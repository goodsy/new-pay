package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD_RELEASE
import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.jooq.generated.Tables.PAYMENT_AUTHORIZATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_CANCELLATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TERMINATION_EVIDENCE
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TRANSACTION
import com.paycore.payment.jooq.generated.Tables.RETURN_BALANCE
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentListItem
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import com.paycore.payment.payment.application.required.AdminPaymentReader
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 관리자 거래 조회(TXN-OPS-001 §3.2–§3.4 — jOOQ CQRS 조회 측, **쓰기·잠금 없음**, 비권위). 모든 SELECT에 질의 시간 상한 hint([AdminReadLimits] — MySQL `MAX_EXECUTION_TIME`).
 * - 목록: `ORDER BY created_at DESC, payment_id DESC` keyset(`created_at <= c AND (created_at < c OR payment_id < p)` — 동률은 paymentId로 끊는다). 기간·cursor 시각은
 *   [UtcWallClock.literal](바인딩 시간대 변환 없음), 읽기는 `DATE_FORMAT` 문자열. 모드별 색인을 고정한다(FORCE INDEX — 관리 조회가 승인 DB에서 계획에 따라 전체 스캔으로 바뀌지 않게):
 *   MID 기간 검색 `ix_payment_mid_created`, MID 없는 기간 검색 `ix_payment_created`(V31), 주문번호 조회 기존 `uk_payment_order_generation`, 결제 ID는 PK.
 *   주문명은 SQL `LEFT(order_name, 10)`·10자 초과 여부만 읽는다(D-TXOPS-003, I-5 — 전체 이름이 목록 경로 메모리로 오지 않는다).
 * - 상세: **한 읽기 전용 REPEATABLE READ TX**의 4문장(Payment·승인·반환 잔액·hold → 종료 근거 → 거래 사실 → 취소 이력). 첫 문장이 범위 키(environment·MID)를 읽고, 뒤 문장은 모두
 *   `payment`와 join해 **같은 payment_id·environment·merchant_account_id**를 다시 건다(`payment_transaction`에는 범위 열이 없다). InnoDB 일관 읽기 스냅샷은 첫 읽기에 고정된다.
 * - 읽지 않는 열(§3.4): attempt 표(기관 내부 계정 값 — D-MS2-005)는 아예 읽지 않고, 취소의 운영자 사유·근거·요청자·승인자·approvalId·지문, 거래의 기관 결과 원문 키, payment key는 SELECT에 없다.
 */
@Component
class JooqAdminPaymentReader(private val dsl: DSLContext) : AdminPaymentReader {

    @Transactional(readOnly = true)
    override fun search(criteria: AdminPaymentSearchCriteria, rows: Int): List<AdminPaymentListItem> = AdminReadLimits.guard {
        val p = PAYMENT
        val createdAt = UtcWallClock.iso(p.CREATED_AT).`as`("created_at_utc")
        val preview = DSL.field("LEFT({0}, {1})", String::class.java, p.ORDER_NAME, DSL.inline(AdminPaymentSearchCriteria.ORDER_NAME_PREVIEW)).`as`("order_name_preview")
        val truncated = DSL.field("(COALESCE(CHAR_LENGTH({0}), 0) > {1})", Int::class.java, p.ORDER_NAME, DSL.inline(AdminPaymentSearchCriteria.ORDER_NAME_PREVIEW))
            .`as`("order_name_truncated")
        val table = when {
            criteria.paymentId != null -> p
            criteria.merchantOrderId != null -> p.forceIndex(ORDER_INDEX)
            criteria.merchantAccountId != null -> p.forceIndex(MID_INDEX)
            else -> p.forceIndex(PERIOD_INDEX)
        }
        dsl.select(p.PAYMENT_ID, p.ENVIRONMENT, p.MERCHANT_ACCOUNT_ID, p.MERCHANT_ORDER_ID, preview, truncated, p.AMOUNT_MINOR, p.CURRENCY, p.STATUS, createdAt)
            .hint(AdminReadLimits.HINT)
            .from(table)
            .where(conditions(criteria))
            .orderBy(p.CREATED_AT.desc(), p.PAYMENT_ID.desc())
            .limit(rows)
            .fetch { r ->
                AdminPaymentListItem(
                    paymentId = r.get(p.PAYMENT_ID), environment = r.get(p.ENVIRONMENT), merchantAccountId = r.get(p.MERCHANT_ACCOUNT_ID),
                    merchantOrderId = r.get(p.MERCHANT_ORDER_ID), orderNamePreview = r.get(preview), orderNameTruncated = r.get(truncated) == 1,
                    amountMinor = r.get(p.AMOUNT_MINOR), currency = r.get(p.CURRENCY), status = r.get(p.STATUS), createdAt = Instant.parse(r.get(createdAt)),
                )
            }
    }

    private fun conditions(c: AdminPaymentSearchCriteria): List<Condition> {
        val p = PAYMENT
        val where = mutableListOf<Condition>()
        c.paymentId?.let { where += p.PAYMENT_ID.eq(it) }
        if (c.merchantOrderId != null) {
            // 주문 키 색인 앞부분(environment)을 범위로 쓴다 — 환경이 없으면 두 값의 IN.
            where += p.ENVIRONMENT.`in`(c.environment?.let(::listOf) ?: ENVIRONMENTS)
        } else {
            c.environment?.let { where += p.ENVIRONMENT.eq(it) }
        }
        c.merchantAccountId?.let { where += p.MERCHANT_ACCOUNT_ID.eq(it) }
        c.merchantOrderId?.let { where += p.MERCHANT_ORDER_ID.eq(it) }
        c.status?.let { where += p.STATUS.eq(it) }
        c.createdFrom?.let { where += p.CREATED_AT.ge(UtcWallClock.literal(it)) }
        c.createdTo?.let { where += p.CREATED_AT.lt(UtcWallClock.literal(it)) }
        c.after?.let { cursor ->
            val at = UtcWallClock.literal(cursor.createdAt)
            where += p.CREATED_AT.le(at).and(p.CREATED_AT.lt(at).or(p.PAYMENT_ID.lt(cursor.paymentId)))
        }
        return where
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun detail(paymentId: String): AdminPaymentDetail? = AdminReadLimits.guard {
        val p = PAYMENT
        val a = PAYMENT_AUTHORIZATION
        val b = RETURN_BALANCE
        val h = INCIDENT_HOLD
        val r = INCIDENT_HOLD_RELEASE
        val createdAt = UtcWallClock.iso(p.CREATED_AT).`as`("created_at_utc")
        val openedAt = UtcWallClock.iso(a.OPENED_AT).`as`("authorization_opened_at_utc")
        val onHold = DSL.field(
            DSL.exists(
                DSL.selectOne().from(h).where(
                    h.ENVIRONMENT.eq(p.ENVIRONMENT).and(h.MERCHANT_ACCOUNT_ID.eq(p.MERCHANT_ACCOUNT_ID)).and(h.MERCHANT_ORDER_ID.eq(p.MERCHANT_ORDER_ID))
                        .and(h.PAYMENT_ID.eq(p.PAYMENT_ID))
                        .and(DSL.notExists(DSL.selectOne().from(r).where(r.INCIDENT_HOLD_ID.eq(h.ID)))),
                ),
            ),
        ).`as`("admin_on_hold")
        val head = dsl.select(
            p.PAYMENT_ID, p.ENVIRONMENT, p.MERCHANT_ACCOUNT_ID, p.MERCHANT_SITE_ID, p.MERCHANT_ORDER_ID, p.ORDER_NAME, p.AMOUNT_MINOR, p.CURRENCY, p.PAYMENT_METHOD,
            p.STATUS, createdAt, onHold,
            a.AUTHORIZATION_ID, a.STATUS, a.FAILURE_EVIDENCE, a.PLAN_KIND, a.INSTALLMENT_MONTHS, a.AUTHENTICATED_CARD_TYPE, openedAt,
            b.PAYMENT_ID, b.CURRENCY, b.AUTHORIZED_MINOR, b.COMPLETED_MINOR, b.RESERVED_MINOR, b.FORCED_MINOR, b.REVISION,
        )
            .hint(AdminReadLimits.HINT)
            .from(p)
            .leftJoin(a).on(a.PAYMENT_ID.eq(p.PAYMENT_ID).and(a.ENVIRONMENT.eq(p.ENVIRONMENT)).and(a.MERCHANT_ACCOUNT_ID.eq(p.MERCHANT_ACCOUNT_ID)))
            .leftJoin(b).on(b.PAYMENT_ID.eq(p.PAYMENT_ID))
            .where(p.PAYMENT_ID.eq(paymentId))
            .fetchOne() ?: return@guard null
        val environment = head.get(p.ENVIRONMENT)
        val mid = head.get(p.MERCHANT_ACCOUNT_ID)
        // 같은 RR 읽기 전용 TX의 뒤 문장들 — 첫 문장이 연 스냅샷 그대로, 각자 범위(payment_id·environment·MID)를 다시 건다.
        AdminPaymentDetail(
            payment = AdminPaymentDetail.Header(
                paymentId = head.get(p.PAYMENT_ID), environment = environment, merchantAccountId = mid, merchantSiteId = head.get(p.MERCHANT_SITE_ID),
                merchantOrderId = head.get(p.MERCHANT_ORDER_ID), orderName = head.get(p.ORDER_NAME), amountMinor = head.get(p.AMOUNT_MINOR), currency = head.get(p.CURRENCY),
                paymentMethod = head.get(p.PAYMENT_METHOD), status = head.get(p.STATUS), createdAt = Instant.parse(head.get(createdAt)),
            ),
            onHold = head.get(onHold) == true,
            terminationEvidence = evidence(paymentId, environment, mid),
            authorization = head.get(a.AUTHORIZATION_ID)?.let { id ->
                AdminPaymentDetail.Authorization(
                    authorizationId = id, status = head.get(a.STATUS), failureEvidence = head.get(a.FAILURE_EVIDENCE), planKind = head.get(a.PLAN_KIND),
                    installmentMonths = head.get(a.INSTALLMENT_MONTHS), cardType = head.get(a.AUTHENTICATED_CARD_TYPE), openedAt = Instant.parse(head.get(openedAt)),
                )
            },
            transactions = transactions(paymentId, environment, mid),
            cancellations = cancellations(paymentId, environment, mid),
            returnBalance = head.get(b.PAYMENT_ID)?.let {
                AdminPaymentDetail.ReturnBalance(
                    head.get(b.CURRENCY), head.get(b.AUTHORIZED_MINOR), head.get(b.COMPLETED_MINOR), head.get(b.RESERVED_MINOR), head.get(b.FORCED_MINOR), head.get(b.REVISION),
                )
            },
        )
    }

    /** 범위 join: 자식 행의 payment_id가 같은 environment·MID의 그 Payment일 때만. */
    private fun scope(paymentIdField: Field<String>, paymentId: String, environment: String, mid: String): Condition =
        PAYMENT.PAYMENT_ID.eq(paymentIdField).and(PAYMENT.PAYMENT_ID.eq(paymentId)).and(PAYMENT.ENVIRONMENT.eq(environment)).and(PAYMENT.MERCHANT_ACCOUNT_ID.eq(mid))

    private fun evidence(paymentId: String, environment: String, mid: String): List<String> {
        val e = PAYMENT_TERMINATION_EVIDENCE
        return dsl.select(e.EVIDENCE).hint(AdminReadLimits.HINT)
            .from(e).join(PAYMENT).on(scope(e.PAYMENT_ID, paymentId, environment, mid))
            .where(e.PAYMENT_ID.eq(paymentId))
            .orderBy(e.SEQ)
            .fetch(e.EVIDENCE)
    }

    private fun transactions(paymentId: String, environment: String, mid: String): List<AdminPaymentDetail.Transaction> {
        val t = PAYMENT_TRANSACTION
        val confirmedAt = UtcWallClock.iso(t.CONFIRMED_AT).`as`("confirmed_at_utc")
        val txDate = date(t.INSTITUTION_TRANSACTION_DATE).`as`("tx_date")
        val businessDate = date(t.INSTITUTION_BUSINESS_DATE).`as`("business_date")
        return dsl.select(
            t.TRANSACTION_ID, t.TYPE, t.AMOUNT_MINOR, t.CURRENCY, t.PLAN_KIND, t.INSTALLMENT_MONTHS, t.CARD_TYPE, t.APPROVAL_NUMBER, t.PROVIDER_TRANSACTION_ID,
            t.CANCELLATION_ID, t.ORIGINAL_TRANSACTION_ID, t.CANCEL_SCOPE, txDate, businessDate, confirmedAt, t.RECORDED_BY,
        )
            .hint(AdminReadLimits.HINT)
            .from(t).join(PAYMENT).on(scope(t.PAYMENT_ID, paymentId, environment, mid))
            .where(t.PAYMENT_ID.eq(paymentId))
            // 승인 거래 먼저, 그다음 확정 시각 순(동률은 거래 ID).
            .orderBy(DSL.`when`(t.TYPE.eq("AUTHORIZATION"), DSL.inline(0)).otherwise(DSL.inline(1)), t.CONFIRMED_AT, t.TRANSACTION_ID)
            .fetch { r ->
                AdminPaymentDetail.Transaction(
                    transactionId = r.get(t.TRANSACTION_ID), type = r.get(t.TYPE), amountMinor = r.get(t.AMOUNT_MINOR), currency = r.get(t.CURRENCY),
                    planKind = r.get(t.PLAN_KIND), installmentMonths = r.get(t.INSTALLMENT_MONTHS), cardType = r.get(t.CARD_TYPE), approvalNumber = r.get(t.APPROVAL_NUMBER),
                    providerTransactionId = r.get(t.PROVIDER_TRANSACTION_ID), cancellationId = r.get(t.CANCELLATION_ID), originalTransactionId = r.get(t.ORIGINAL_TRANSACTION_ID),
                    cancelScope = r.get(t.CANCEL_SCOPE), institutionTransactionDate = r.get(txDate), institutionBusinessDate = r.get(businessDate),
                    confirmedAt = Instant.parse(r.get(confirmedAt)), recordedBy = r.get(t.RECORDED_BY),
                )
            }
    }

    private fun cancellations(paymentId: String, environment: String, mid: String): List<AdminPaymentDetail.Cancellation> {
        val c = PAYMENT_CANCELLATION
        val openedAt = UtcWallClock.iso(c.OPENED_AT).`as`("cancel_opened_at_utc")
        return dsl.select(c.CANCELLATION_ID, c.INITIATOR_KIND, c.MERCHANT_CANCEL_ID, c.SCOPE, c.STATUS, c.FAILURE_EVIDENCE, c.AMOUNT_MINOR, c.CURRENCY, openedAt)
            .hint(AdminReadLimits.HINT)
            .from(c).join(PAYMENT).on(scope(c.PAYMENT_ID, paymentId, environment, mid))
            .where(c.PAYMENT_ID.eq(paymentId))
            .orderBy(c.OPENED_AT, c.CANCELLATION_ID)
            .fetch { r ->
                AdminPaymentDetail.Cancellation(
                    cancellationId = r.get(c.CANCELLATION_ID), initiatorKind = r.get(c.INITIATOR_KIND), merchantCancelId = r.get(c.MERCHANT_CANCEL_ID), scope = r.get(c.SCOPE),
                    status = r.get(c.STATUS), failureEvidence = r.get(c.FAILURE_EVIDENCE), amountMinor = r.get(c.AMOUNT_MINOR), currency = r.get(c.CURRENCY),
                    openedAt = Instant.parse(r.get(openedAt)),
                )
            }
    }

    private fun date(field: Field<*>): Field<String> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%d')", String::class.java, field)

    private companion object {
        const val PERIOD_INDEX = "ix_payment_created"
        const val MID_INDEX = "ix_payment_mid_created"
        const val ORDER_INDEX = "uk_payment_order_generation"
        val ENVIRONMENTS = listOf("TEST", "LIVE")
    }
}
