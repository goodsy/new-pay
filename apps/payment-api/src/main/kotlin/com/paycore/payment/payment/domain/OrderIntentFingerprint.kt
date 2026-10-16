package com.paycore.payment.payment.domain

/**
 * 고정 주문 입력의 fingerprint(버전 접두 포함, 예: `v1:<sha256 hex>`). 계산은 [OrderIntent.fingerprint](D-CPC-001: merchantSiteId +
 * amount + currency, 정의 CP-CONTRACT-001 §6)이며 control·Payment는 값이 같은지만 비교한다.
 * 버전 호환: v1 계산은 동결하고, 요청은 저장된 control의 버전으로 계산해 비교한다(서로 다른 버전 값을 직접 비교하면 불일치 →
 * FixedIntentConflict로 fail-closed). 외화 정산 통화·환율(Q-SETTLE-CURRENCY)은 이 비교와 별개다.
 */
@JvmInline
value class OrderIntentFingerprint(val value: String) {
    init {
        require(value.isNotEmpty()) { "orderIntentFingerprint must not be empty" }
    }
}
