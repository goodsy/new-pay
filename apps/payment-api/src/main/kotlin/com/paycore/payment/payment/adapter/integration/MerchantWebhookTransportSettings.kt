package com.paycore.payment.payment.adapter.integration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 가맹점 Webhook 전송 설정(`paycore.webhook.transport` — WEBHOOK-001 §7.2·§21.0b, D-WH-007, 검토 m-4·m-5·m-6). **운영 기본값 없음**(값이 없거나 범위 밖이면 기동 실패. Demo: connect 3s,
 * 응답 idle 10s, 전체 요청 기한 15s, DNS 2s, 응답 상한 4 KiB).
 * - [testAllowlist]: TEST 전달에서만 쓰는 정확한 `host:port`(소문자) 목록 — 시험·로컬 수신기용(빈 목록은 명시 값). LIVE 전달에는 코드가 적용하지 않는다.
 * - [testTrustPem]: TEST allowlist 목적지에만 쓰는 추가 CA(PEM 경로, 선택). LIVE·allowlist 밖 목적지는 JDK 기본 trust뿐이다.
 * - JVM 전역 `javax.net.ssl.trustStore`가 설정돼 있으면 기동을 거부한다(기본 trust를 바꾸면 LIVE에도 사용자 정의 trust가 들어간다 — m-5).
 * - [maxResponseBytes]: **현재 쓰이지 않는다 — 설정 호환을 위해 남김**(최종 검토 n-1). 전송 adapter는 상태 줄로 판정한 뒤 본문을 전혀 읽지 않고 abort하므로(검토 #2 m-1)
 *   상한보다 엄격하다. 값 검증(1..상한)은 그대로 둔다.
 */
@ConfigurationProperties(prefix = "paycore.webhook.transport")
data class MerchantWebhookTransportSettings(
    val connectTimeout: Duration,
    val responseTimeout: Duration,
    val requestDeadline: Duration,
    val dnsTimeout: Duration,
    val maxResponseBytes: Int,
    val testAllowlist: List<String>,
    val testTrustPem: String?,
) {
    init {
        require(connectTimeout.isPositive && responseTimeout.isPositive && requestDeadline.isPositive && dnsTimeout.isPositive) { "webhook transport timeouts must be positive" }
        require(requestDeadline >= connectTimeout && requestDeadline >= responseTimeout) { "webhook transport request-deadline must cover connect-timeout and response-timeout" }
        require(maxResponseBytes in 1..MAX_RESPONSE) { "webhook transport max-response-bytes must be within 1..$MAX_RESPONSE" }
        testAllowlist.forEach { require(ALLOW.matches(it)) { "webhook transport test-allowlist entries are exact lower-case host:port" } }
        require(System.getProperty("javax.net.ssl.trustStore") == null) {
            "a JVM-wide javax.net.ssl.trustStore would add custom trust to LIVE webhook deliveries; refusing to start"
        }
    }

    override fun toString(): String =
        "MerchantWebhookTransportSettings(connectTimeout=$connectTimeout, responseTimeout=$responseTimeout, requestDeadline=$requestDeadline, dnsTimeout=$dnsTimeout, " +
            "maxResponseBytes=$maxResponseBytes, testAllowlist=${testAllowlist.size} entries, testTrust=${if (testTrustPem == null) "none" else "set"})"

    private companion object {
        const val MAX_RESPONSE = 1_048_576
        val ALLOW = Regex("^[a-z0-9.-]{1,253}:[0-9]{1,5}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantWebhookTransportSettings::class)
class MerchantWebhookTransportSettingsConfiguration
