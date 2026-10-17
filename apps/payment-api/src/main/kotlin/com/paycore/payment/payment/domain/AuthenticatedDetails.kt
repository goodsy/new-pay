package com.paycore.payment.payment.domain

import java.time.Instant

/** AUTHENTICATED 결과의 증거 필드(m-E: 모두 필수). [cardType]은 계약의 열린 토큰 — 지원 판정은 R10. */
data class AuthenticatedDetails(
    val cardType: String,
    val issuerCode: String,
    val acquirerCode: String,
    val providerCode: String,
    val evidenceRef: String,
    val authenticatedAt: Instant,
) {
    init {
        require(listOf(cardType, issuerCode, acquirerCode, providerCode, evidenceRef).all { it.isNotEmpty() }) { "evidence fields must not be empty" }
    }
}
