package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutBacklog
import com.paycore.payment.payment.application.provided.CheckoutBacklogQuery
import com.paycore.payment.payment.application.required.AuthenticationBacklogReader
import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit

/** 결제창·인증 적체 관찰(AUTHN-001 §15): 지금 시각(서버 clock) 기준 jOOQ 수 — 판정이 아니라 gauge 값이다(주기 작업 지연·만료 누락 관찰). */
@Service
class CheckoutBacklogService(private val clock: PaymentClock, private val reader: AuthenticationBacklogReader) : CheckoutBacklogQuery {
    override fun backlog(): CheckoutBacklog {
        val c = reader.counts(clock.now().truncatedTo(ChronoUnit.MILLIS))
        return CheckoutBacklog(c.elapsedRequestedPayments, c.unresolvedAuthenticationSessions, c.expiredUnexchangedTokens)
    }
}
