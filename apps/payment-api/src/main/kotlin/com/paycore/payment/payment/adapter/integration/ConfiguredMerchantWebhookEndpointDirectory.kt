package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component

/** [MerchantWebhookEndpointDirectory] 설정 구현. LIVE는 항상 null(fail-closed — D-WH-004). */
@Component
class ConfiguredMerchantWebhookEndpointDirectory(settings: MerchantWebhookSettings) : MerchantWebhookEndpointDirectory {
    private val byMid: Map<String, MerchantWebhookEndpoint> = settings.testEndpoints.associate { it.merchantAccountId to MerchantWebhookEndpoint(it.endpointRef, it.url) }

    override fun find(environment: Environment, merchantAccountId: MerchantAccountId): MerchantWebhookEndpoint? =
        if (environment == Environment.TEST) byMid[merchantAccountId.value] else null
}
