package com.paycore.payment.entitlement.adapter.web

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.util.Base64

/**
 * 내부 Inbox 수신 키(D-MS-004: 봉투 HMAC-SHA256, 환경별 키, 교체용 키 2개 동시 허용, 불일치·키 없음 거절). **운영 기본값이 없다** — 키가 없으면
 * 기동이 실패한다. 키는 비밀 저장소에서 주입하고 저장소·설정 파일에 운영 키를 두지 않는다. 운영망 mTLS는 LIVE 허용 전 별도 작업(D-MS-004).
 */
@ConfigurationProperties(prefix = "paycore.payment.entitlement.inbox")
class EntitlementInboxSettings(val signingKeys: List<SigningKey>) {
    class SigningKey(val keyId: String, val secret: String) {
        val bytes: ByteArray = try {
            Base64.getDecoder().decode(secret)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("signing key secret must be Base64", e)
        }

        override fun toString(): String = "SigningKey(keyId=$keyId, secret=****)"
    }

    init {
        require(signingKeys.size in 1..MAX_KEYS) { "1..$MAX_KEYS signing keys are required (two only while rotating)" }
        require(signingKeys.map { it.keyId }.toSet().size == signingKeys.size) { "signing key ids must be unique" }
        signingKeys.forEach {
            require(KEY_ID.matches(it.keyId)) { "signing key id must be 1..32 characters of [A-Za-z0-9._-]" }
            require(it.bytes.size >= MIN_KEY_BYTES) { "signing key must decode to at least $MIN_KEY_BYTES bytes" }
        }
    }

    fun key(keyId: String): ByteArray? = signingKeys.firstOrNull { it.keyId == keyId }?.bytes

    override fun toString(): String = "EntitlementInboxSettings(keys=${signingKeys.map { it.keyId }})"

    companion object {
        const val MAX_KEYS = 2
        const val MIN_KEY_BYTES = 32
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementInboxSettings::class)
class EntitlementInboxConfiguration
