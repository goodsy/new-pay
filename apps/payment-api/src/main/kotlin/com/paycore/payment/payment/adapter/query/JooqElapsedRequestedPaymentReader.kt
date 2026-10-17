package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.payment.application.required.ElapsedRequestedCandidate
import com.paycore.payment.payment.application.required.ElapsedRequestedPaymentReader
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 결제창 만료 후보 조회(CQRS 조회 측, jOOQ — AUTHN-001 §7.5.4): 결제창 시각이 지난 REQUESTED Payment를 키셋 `(checkout_expires_at, payment_id)`로 정렬·LIMIT한다
 * (인덱스 `ix_payment_requested_expiry (status, checkout_expires_at, payment_id)`). 쓰기 없음, 읽기 전용 TX 하나, 비권위(판정은 잠금 재판정). 시각 비교·읽기는
 * [UtcWallClock](저장 UTC 벽시계 그대로 — 관례 §9.1).
 */
@Component
class JooqElapsedRequestedPaymentReader(private val dsl: DSLContext) : ElapsedRequestedPaymentReader {

    @Transactional(readOnly = true)
    override fun findElapsed(now: Instant, after: ElapsedRequestedCandidate?, limit: Int): List<ElapsedRequestedCandidate> = try {
        val p = PAYMENT
        val expiresAt = UtcWallClock.iso(p.CHECKOUT_EXPIRES_AT)
        val due = p.STATUS.eq(REQUESTED).and(p.CHECKOUT_EXPIRES_AT.le(UtcWallClock.literal(now)))
        val page = after?.let {
            DSL.row(p.CHECKOUT_EXPIRES_AT, p.PAYMENT_ID).gt(DSL.row(UtcWallClock.literal(it.checkoutExpiresAt), DSL.inline(it.paymentId.value)))
        } ?: DSL.noCondition()
        dsl.select(p.PAYMENT_ID, p.ENVIRONMENT, p.MERCHANT_ACCOUNT_ID, p.MERCHANT_ORDER_ID, expiresAt)
            .from(p)
            .where(due.and(page))
            .orderBy(p.CHECKOUT_EXPIRES_AT, p.PAYMENT_ID)
            .limit(limit)
            .fetch { r ->
                ElapsedRequestedCandidate(
                    PaymentId(r.get(p.PAYMENT_ID)),
                    OrderKey(Environment.valueOf(r.get(p.ENVIRONMENT)), MerchantAccountId(r.get(p.MERCHANT_ACCOUNT_ID)), MerchantOrderId(r.get(p.MERCHANT_ORDER_ID))),
                    checkNotNull(UtcWallClock.parse(r.get(expiresAt))),
                )
            }
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    private companion object {
        const val REQUESTED = "REQUESTED"
    }
}
