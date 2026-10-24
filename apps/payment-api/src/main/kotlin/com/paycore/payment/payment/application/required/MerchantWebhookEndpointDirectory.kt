package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/** 가맹점 Webhook endpoint(URL은 비밀이 아니지만 로그에는 [endpointRef]만 — WEBHOOK-001 §11). */
data class MerchantWebhookEndpoint(val endpointRef: String, val url: String) {
    override fun toString(): String = "MerchantWebhookEndpoint(endpointRef=$endpointRef)"
}

/**
 * endpoint 원천 Port(D-WH-004). 이 작업의 구현은 TEST 환경 Demo 설정뿐이며 LIVE는 항상 null(fail-closed — 등록 경로는 WEBHOOK-ENDPOINT-001).
 */
interface MerchantWebhookEndpointDirectory {
    fun find(environment: Environment, merchantAccountId: MerchantAccountId): MerchantWebhookEndpoint?
}
