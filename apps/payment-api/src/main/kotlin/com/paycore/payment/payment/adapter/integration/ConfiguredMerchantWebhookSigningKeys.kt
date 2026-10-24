package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.MerchantWebhookSigningKeys
import com.paycore.payment.payment.application.required.WebhookSigningKey
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component

/** [MerchantWebhookSigningKeys] TEST 설정 구현(`paycore.webhook.test-signing-keys` — D-WH-005: 발급 경로 전에는 TEST 시험 키만). LIVE는 빈 목록(전송 안 함 — fail-closed). */
@Component
class ConfiguredMerchantWebhookSigningKeys(settings: MerchantWebhookSettings) : MerchantWebhookSigningKeys {
    private val byMid: Map<String, List<WebhookSigningKey>> = settings.testSigningKeys.groupBy({ it.merchantAccountId }) { WebhookSigningKey(it.keyId, it.secret.toByteArray(Charsets.UTF_8)) }

    override fun active(environment: Environment, merchantAccountId: MerchantAccountId): List<WebhookSigningKey> =
        if (environment == Environment.TEST) byMid[merchantAccountId.value].orEmpty() else emptyList()
}
