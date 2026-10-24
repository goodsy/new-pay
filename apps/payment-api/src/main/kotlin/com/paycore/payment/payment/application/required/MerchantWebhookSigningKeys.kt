package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 활성 서명 비밀 하나(D-WH-003·D-WH-005). [secret]은 HMAC 키 바이트 = 가맹점에 보이는 비밀 문자열 전체의 UTF-8(WH-19). toString·로그에 비밀을 넣지 않는다.
 */
class WebhookSigningKey(val keyId: String, val secret: ByteArray) {
    override fun toString(): String = "WebhookSigningKey(keyId=$keyId, secret=****)"
}

/** 서명 키 원천 Port. 이 작업 구현은 TEST 설정 키뿐(D-WH-005 — 발급은 WEBHOOK-ENDPOINT-001, LIVE는 빈 목록 = 전송 안 함). 교체 중 최대 2개(순서 = 서명 순서). */
interface MerchantWebhookSigningKeys {
    fun active(environment: Environment, merchantAccountId: MerchantAccountId): List<WebhookSigningKey>
}
