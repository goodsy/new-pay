package com.paycore.payment.payment.application.provided

/** 결제창 시각 지난 REQUESTED·TTL 지난 비종료 인증 세션·만료 지난 미교환 토큰 수(ID 없음 — AUTHN-001 §15 gauge 값). */
data class CheckoutBacklog(val elapsedRequestedPayments: Long, val unresolvedAuthenticationSessions: Long, val expiredUnexchangedTokens: Long)
