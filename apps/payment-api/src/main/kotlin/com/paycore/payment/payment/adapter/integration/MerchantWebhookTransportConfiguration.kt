package com.paycore.payment.payment.adapter.integration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 전송 adapter 구성(WEBHOOK-001 §7.2). TEST allowlist 목적지에만 쓰는 추가 CA(`paycore.webhook.transport.test-trust-pem`, 선택)를 읽는다 — LIVE·allowlist 밖 목적지는 JDK 기본 trust뿐(m-5).
 */
@Configuration(proxyBeanMethods = false)
class MerchantWebhookTransportConfiguration {

    @Bean(destroyMethod = "close")
    fun httpMerchantWebhookSender(settings: MerchantWebhookTransportSettings, resolver: WebhookHostResolver): HttpMerchantWebhookSender =
        HttpMerchantWebhookSender(settings, resolver, settings.testTrustPem?.let(::trustOnly))

    private fun trustOnly(pemPath: String): SSLContext {
        val certificates = File(pemPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        require(certificates.isNotEmpty()) { "paycore.webhook.transport.test-trust-pem holds no certificate" }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { i, c -> setCertificateEntry("webhook-test-ca-$i", c) }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers, null)
        }
    }
}
