package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * Payment 결제창 절대 만료 시각(D-AUTHN-005, AUTHN-001 §7.5): 생성 시각 + 버전 있는 결제 유효기간 설정(Demo 값 — 운영값 아님). 계산은 application이
 * 하고 domain은 값과 그 설정 버전을 보존한다. 재사용·재시도로 연장하지 않는다.
 */
data class CheckoutWindow(val expiresAt: Instant, val validityVersion: String) {
    init {
        require(validityVersion.isNotEmpty() && validityVersion.length <= VERSION_MAX_LENGTH) { "checkout validity version must be 1..$VERSION_MAX_LENGTH characters" }
        Utf8Text.requireWellFormed(validityVersion, "checkoutValidityVersion")
    }

    private companion object {
        const val VERSION_MAX_LENGTH = 64
    }
}
