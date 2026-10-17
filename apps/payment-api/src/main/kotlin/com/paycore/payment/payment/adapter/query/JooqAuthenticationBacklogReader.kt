package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.AUTHENTICATION_SESSION
import com.paycore.payment.jooq.generated.Tables.CHECKOUT_TOKEN
import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.payment.application.required.AuthenticationBacklogReader
import com.paycore.payment.payment.application.required.CheckoutBacklogCounts
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 결제창·인증 적체 수(CQRS 조회 측, jOOQ — AUTHN-001 §15 Observability gauge). 쓰기 없음, 읽기 전용 TX 하나, 비권위. 시각 비교는 [UtcWallClock] 리터럴(관례 §9.1).
 * 결제창 시각 조건은 인덱스 `ix_payment_requested_expiry`를 쓴다.
 */
@Component
class JooqAuthenticationBacklogReader(private val dsl: DSLContext) : AuthenticationBacklogReader {

    @Transactional(readOnly = true)
    override fun counts(now: Instant): CheckoutBacklogCounts {
        val at = UtcWallClock.literal(now)
        val p = PAYMENT
        val s = AUTHENTICATION_SESSION
        val t = CHECKOUT_TOKEN
        val elapsed = dsl.fetchCount(p, p.STATUS.eq("REQUESTED").and(p.CHECKOUT_EXPIRES_AT.le(at)))
        val unresolved = dsl.fetchCount(s, s.STATUS.`in`("CREATED", "IN_PROGRESS").and(s.EXPIRES_AT.le(at)))
        val tokens = dsl.fetchCount(t, t.STATUS.eq("ISSUED").and(t.EXPIRES_AT.le(at)))
        return CheckoutBacklogCounts(elapsed.toLong(), unresolved.toLong(), tokens.toLong())
    }
}
