package com.paycore.payment.payment.domain

/*
 * 결제창 프로토콜 기록 식별자(AUTHN-001 §10.1, AN-22). 토큰·결제창 세션은 Aggregate가 아니라 보안 프로토콜 기록이지만(AN-14) 식별자는 저장 Port 계약에서
 * 타입으로 구분한다. 생성은 application의 비밀·식별자 생성 Port 책임이다.
 */

private val TOKEN_ID = Regex("^ctk_[0-9A-Za-z]{1,60}$")
private val SESSION_ID = Regex("^cks_[0-9A-Za-z]{1,60}$")
private val HANDLE = Regex("^[A-Za-z0-9_-]{22}$")

/** 결제창 토큰 행 식별자(`ctk_` + ULID). 토큰 원문이 아니다. */
@JvmInline
value class CheckoutTokenId(val value: String) {
    init {
        require(TOKEN_ID.matches(value)) { "checkoutTokenId must be ctk_ followed by alphanumerics" }
    }
}

/** 결제창 세션 행 식별자(`cks_` + ULID). 쿠키 비밀이 아니다. */
@JvmInline
value class CheckoutSessionId(val value: String) {
    init {
        require(SESSION_ID.matches(value)) { "checkoutSessionId must be cks_ followed by alphanumerics" }
    }
}

/** 결제창 경로 handle(128bit base64url 22자, 판정 m-1). 자격 증명이 아니라 경로 식별자다 — 접근은 handle과 짝인 쿠키 비밀이 통제한다. */
@JvmInline
value class CheckoutHandle(val value: String) {
    init {
        require(HANDLE.matches(value)) { "checkout handle must be 22 base64url characters" }
    }
}
