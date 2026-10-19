package com.paycore.payment.payment.application.provided

/**
 * 멱등 요청 fingerprint(CP-CONTRACT-001 §7, `r<버전>:` + SHA-256 lowercase hex). 계산은 호출자(CP-API-001 web/application)가 하고
 * 이 값은 저장·비교만 한다. 같은 멱등키의 재요청은 이 값이 같아야 저장 결과를 재생한다(다르면 IDEMPOTENCY_KEY_REUSED).
 */
@JvmInline
value class RequestFingerprint(val value: String) {
    init {
        require(PATTERN.matches(value)) { "requestFingerprint must be r<version>:<64 lowercase hex>" }
    }

    private companion object {
        /** `r<n>:` = CreatePayment(CP-CONTRACT-001 §7), `c<n>:` = confirm(AUTH-001 AU-03). 멱등 범위에 operation이 있어 두 계열은 섞이지 않는다. */
        val PATTERN = Regex("^[rc][1-9][0-9]{0,2}:[0-9a-f]{64}$")
    }
}
