package com.paycore.payment.payment.domain

import java.security.MessageDigest
import java.util.HexFormat

/**
 * 같은 OrderKey에서 바뀌면 안 되는 고정 주문 입력(D-CPC-001, 사용자 확정 2026-10-06) = merchantSiteId + amount + currency.
 * 결제수단·주문명 등 표시 정보는 넣지 않는다(최초 Payment 값으로 저장만). MID·environment는 OrderKey 자체라 넣지 않는다.
 *
 * [fingerprint]는 CP-CONTRACT-001 §6.2 v1 정의다. API·DB·domain이 같은 함수를 써서 동일성이 갈라지지 않게 domain에 둔다(관례).
 * v1은 동결한다: 규칙을 바꾸면 새 버전 함수를 추가하고 v1을 지우지 않는다(control의 고정 입력은 영구 보존).
 */
data class OrderIntent(val merchantSiteId: MerchantSiteId, val money: Money) {

    /** v1 정규 표현: UTF-8 byte 길이 접두 토큰을 고정 순서(domain tag, version, site, amount, currency)로 이어 붙인다. */
    internal fun canonicalV1(): String =
        token(DOMAIN_TAG) + token(V1.toString()) + token(merchantSiteId.value) +
            token(money.amountMinor.toString()) + token(money.currency.value)

    /** v1 fingerprint = "v1:" + lowercase hex(SHA-256(UTF-8(canonicalV1))). UTF-8은 엄격 인코딩(malformed 거부, §6.2)이다. */
    fun fingerprint(): OrderIntentFingerprint {
        val digest = MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(canonicalV1()))
        return OrderIntentFingerprint("v$V1:" + HexFormat.of().formatHex(digest))
    }

    private companion object {
        const val DOMAIN_TAG = "paycore.order-intent"
        const val V1 = 1

        fun token(s: String): String = "${Utf8Text.encode(s).size}:$s"
    }
}
