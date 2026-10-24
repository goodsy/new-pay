package com.paycore.simulator.merchant.application.required

import com.paycore.simulator.merchant.domain.ReceiverScenario
import java.time.Duration

/** 가상 가맹점 수신기 설정 Port(WEBHOOK-001 §9 — Demo, 운영값 아님). 설정이 없으면 수신기 0개(모든 수신 404 — fail-closed). */
interface MerchantWebhookReceivers {
    /** 서명 재전송 창(D-WH-003 Demo 300초). 수신기가 있으면 반드시 설정된다. */
    val signatureWindow: Duration

    fun find(receiverId: String): MerchantReceiver?
}

/** 수신기 하나: 소유 PG client(원장 조회 권한), kid → 비밀(UTF-8 바이트), 응답 시나리오. toString에 비밀을 넣지 않는다. */
class MerchantReceiver(val id: String, val clientId: String, private val secrets: Map<String, ByteArray>, val scenario: ReceiverScenario) {
    val keyIds: Set<String> get() = secrets.keys

    fun secret(keyId: String): ByteArray = secrets.getValue(keyId).copyOf()

    override fun toString(): String = "MerchantReceiver(id=$id, clientId=$clientId, keyIds=$keyIds, scenario=$scenario)"
}
