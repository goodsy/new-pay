package com.paycore.payment.payment.application.required

import java.time.Instant

/** 결제창·인증 적체 수(비권위 — 관찰 gauge 전용, AUTHN-001 §15). */
data class CheckoutBacklogCounts(val elapsedRequestedPayments: Long, val unresolvedAuthenticationSessions: Long, val expiredUnexchangedTokens: Long)

/**
 * 적체 조회 Port(CONVENTIONS §14 jOOQ 조회 `…Reader`, AUTHN-001 §11.2): [now] 기준 결제창 시각 지난 REQUESTED Payment, TTL 지난 비종료(CREATED·IN_PROGRESS) 인증 세션,
 * 만료 지난 미교환(ISSUED) 토큰 수. 읽기 전용 TX 하나. 판정에 쓰지 않는다.
 */
interface AuthenticationBacklogReader {
    fun counts(now: Instant): CheckoutBacklogCounts
}
