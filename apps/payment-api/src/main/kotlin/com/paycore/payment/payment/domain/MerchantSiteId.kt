package com.paycore.payment.payment.domain

/**
 * 가맹점 Site 식별자(MASTER가 발급). MID와 같은 구현 관례로 입력 그대로 비교하며 형식·길이는 MASTER 발급 규칙(MASTER-DOMAIN-001)이라
 * domain은 빈 값과 짝 없는 UTF-16 서로게이트(기술 불변식, CP-CONTRACT-001 §3.1 — UTF-8 `?` 치환으로 다른 Site와 같은
 * fingerprint가 되는 것을 막는다)만 거부한다. Site가 MID와 같은 Merchant 소속·유효 Assignment인지는 eligibility Port(§13)가 검사한다.
 */
@JvmInline
value class MerchantSiteId(val value: String) {
    init {
        require(value.isNotEmpty()) { "merchantSiteId must not be empty" }
        Utf8Text.requireWellFormed(value, "merchantSiteId")
    }
}
