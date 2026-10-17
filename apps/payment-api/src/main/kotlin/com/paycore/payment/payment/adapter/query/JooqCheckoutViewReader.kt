package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.CHECKOUT_SESSION
import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.payment.application.required.CheckoutViewReader
import com.paycore.payment.payment.application.required.CheckoutViewRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.Environment
import org.jooq.DSLContext
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 결제창 화면 조회(CQRS 조회 측, jOOQ — AUTHN-001 §6.2·§16): checkout_session(handle UNIQUE) + payment 조인 한 줄. 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 읽기 전용 TX
 * 하나. 비권위 — 모든 상태 변경은 JPA 잠금 재판정. 시각은 [UtcWallClock](`DATE_FORMAT` 문자열 → Instant — 관례 §9.1).
 */
@Component
class JooqCheckoutViewReader(private val dsl: DSLContext) : CheckoutViewReader {

    /** 저장소 오류는 [PersistenceFailure.Unavailable](application이 Spring DAO 예외에 의존하지 않게 — 화면은 503 "일시적인 오류"). */
    @Transactional(readOnly = true)
    override fun findByHandle(handle: CheckoutHandle): CheckoutViewRow? = try {
        query(handle)
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    private fun query(handle: CheckoutHandle): CheckoutViewRow? {
        val s = CHECKOUT_SESSION
        val p = PAYMENT
        val sessionExpiresAt = UtcWallClock.iso(s.EXPIRES_AT)
        val checkoutExpiresAt = UtcWallClock.iso(p.CHECKOUT_EXPIRES_AT)
        return dsl.select(
            s.CHECKOUT_SESSION_ID, s.SESSION_DIGEST, s.STATUS, s.CLOSE_REASON, sessionExpiresAt, s.SITE_HOST, s.FAIL_URL,
            p.PAYMENT_ID, p.STATUS, checkoutExpiresAt, p.ENVIRONMENT, p.MERCHANT_ACCOUNT_ID, p.MERCHANT_SITE_ID, p.ORDER_NAME, p.AMOUNT_MINOR, p.CURRENCY,
        )
            .from(s).join(p).on(p.PAYMENT_ID.eq(s.PAYMENT_ID))
            .where(s.HANDLE.eq(handle.value))
            .fetchOne { r ->
                CheckoutViewRow(
                    checkoutSessionId = r.get(s.CHECKOUT_SESSION_ID),
                    sessionDigest = r.get(s.SESSION_DIGEST),
                    sessionStatus = r.get(s.STATUS),
                    sessionCloseReason = r.get(s.CLOSE_REASON),
                    sessionExpiresAt = checkNotNull(UtcWallClock.parse(r.get(sessionExpiresAt))),
                    paymentId = r.get(p.PAYMENT_ID),
                    paymentStatus = r.get(p.STATUS),
                    checkoutExpiresAt = UtcWallClock.parse(r.get(checkoutExpiresAt)),
                    environment = Environment.valueOf(r.get(p.ENVIRONMENT)),
                    merchantAccountId = r.get(p.MERCHANT_ACCOUNT_ID),
                    merchantSiteId = r.get(p.MERCHANT_SITE_ID),
                    orderName = r.get(p.ORDER_NAME),
                    amountMinor = r.get(p.AMOUNT_MINOR),
                    currency = r.get(p.CURRENCY),
                    siteHost = r.get(s.SITE_HOST),
                    failUrl = r.get(s.FAIL_URL),
                )
            }
    }
}
