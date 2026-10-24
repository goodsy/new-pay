package com.paycore.payment.payment.adapter.integration

import org.springframework.stereotype.Component
import java.net.InetAddress

/** 시스템 이름 해석(JDK). 예산(`dns-timeout`)과 공인 주소 판정은 [HttpMerchantWebhookSender]가 건다. */
@Component
class SystemWebhookHostResolver : WebhookHostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}
