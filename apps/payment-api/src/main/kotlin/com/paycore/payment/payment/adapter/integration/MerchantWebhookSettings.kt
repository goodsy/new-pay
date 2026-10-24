package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.URISyntaxException

/**
 * TEST 환경 Demo endpoint 설정(`paycore.webhook.test-endpoints` — D-WH-004·D-WH-008: 등록 경로 전에는 설정만, **운영 기본값 없음** — 키가 없으면 기동 실패, 빈 목록은 명시 값).
 * 항목은 TEST 환경에만 적용된다(LIVE 항목 개념이 없다). URL은 `https` + host만(userinfo·fragment 거부). 목적지 검사(공인 IP·연결 고정 등 D-WH-007)는 전송 adapter(S3)가 한다.
 * toString에 URL을 넣지 않는다(endpointRef만 — §11).
 */
@ConfigurationProperties(prefix = "paycore.webhook")
class MerchantWebhookSettings(val testEndpoints: List<TestEndpoint>, val testSigningKeys: List<TestSigningKey>) {

    class TestEndpoint(val merchantAccountId: String, val endpointRef: String, val url: String) {
        init {
            MerchantAccountId(merchantAccountId)
            require(ENDPOINT_REF.matches(endpointRef)) { "paycore.webhook.test-endpoints[].endpoint-ref must be 1..64 characters of [A-Za-z0-9._-]" }
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("paycore.webhook.test-endpoints[$endpointRef].url is not a URI", e)
            }
            require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null) {
                "paycore.webhook.test-endpoints[$endpointRef].url must be an https URL with a host and no userinfo or fragment"
            }
        }

        override fun toString(): String = "TestEndpoint(merchantAccountId=$merchantAccountId, endpointRef=$endpointRef)"
    }

    /**
     * TEST 시험 서명 비밀(`paycore.webhook.test-signing-keys` — D-WH-005: 발급 경로 전에는 설정 시험 키만, 운영 키 아님). 비밀은 `whsec_` + base64url 43자 이상(256bit),
     * HMAC 키는 그 문자열 전체의 UTF-8 바이트(WH-19). (MID)당 최대 2개(교체 — 순서 = 서명 순서). toString에 비밀을 넣지 않는다.
     */
    class TestSigningKey(val merchantAccountId: String, val keyId: String, val secret: String) {
        init {
            MerchantAccountId(merchantAccountId)
            require(KEY_ID.matches(keyId)) { "paycore.webhook.test-signing-keys[].key-id must be 1..40 characters of [A-Za-z0-9_-]" }
            require(SECRET.matches(secret)) { "paycore.webhook.test-signing-keys[$keyId].secret must be whsec_ followed by at least 43 base64url characters" }
        }

        override fun toString(): String = "TestSigningKey(merchantAccountId=$merchantAccountId, keyId=$keyId, secret=****)"
    }

    init {
        require(testEndpoints.map { it.merchantAccountId }.toSet().size == testEndpoints.size) { "paycore.webhook.test-endpoints has one endpoint per MID" }
        require(testSigningKeys.groupBy { it.merchantAccountId }.values.all { it.size <= 2 }) { "paycore.webhook.test-signing-keys allows at most 2 active keys per MID" }
        require(testSigningKeys.map { it.merchantAccountId to it.keyId }.toSet().size == testSigningKeys.size) { "paycore.webhook.test-signing-keys key ids are unique per MID" }
    }

    override fun toString(): String = "MerchantWebhookSettings(testEndpoints=$testEndpoints, testSigningKeys=$testSigningKeys)"

    private companion object {
        val ENDPOINT_REF = Regex("^[A-Za-z0-9._-]{1,64}$")
        val KEY_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        val SECRET = Regex("^whsec_[A-Za-z0-9_-]{43,128}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantWebhookSettings::class)
class MerchantWebhookSettingsConfiguration
