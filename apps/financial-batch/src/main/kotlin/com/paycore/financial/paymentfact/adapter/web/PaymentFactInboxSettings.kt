package com.paycore.financial.paymentfact.adapter.web

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.Base64

/**
 * 결제 사실 Inbox 수신 설정(FIN-FACT-RELAY-001 §4.1·§8). **운영 기본값이 없다** — 값이 없거나 범위 밖이면 기동이 실패한다(fail-closed). 키는 비밀 저장소(환경변수)에서 주입하고
 * 저장소·설정 파일에 운영 키를 두지 않는다. 이 채널 전용 키 공간이다(MASTER→PAYMENT 권리 채널·ADMIN 채널 키와 다른 키 — 송신 측 payment-api가 같은 키를 거부한다).
 *
 * @param signingKeys 1..2개(교체 중에만 2개). kid `[A-Za-z0-9._-]{1,32}`, 비밀 Base64 → 32바이트 이상.
 * @param clockWindow 서명 시각 창 `|now − t| ≤ clockWindow`(경계 포함, 정수 초 1s..10m). Demo 300초 — 운영값은 LIVE Gate.
 * @param maxBodyBytes 본문 상한(1 KiB..1 MiB). Content-Length가 없거나 넘으면 읽지 않고 거절한다. Demo 64 KiB.
 */
@ConfigurationProperties(prefix = "paycore.financial.payment-fact-inbox")
class PaymentFactInboxSettings(val signingKeys: List<SigningKey>, val clockWindow: Duration, val maxBodyBytes: Int) {

    class SigningKey(val keyId: String, secret: String) {
        // 디코드 오류 메시지·원인에는 비밀 일부가 들어갈 수 있어 원인을 붙이지 않는다.
        val bytes: ByteArray = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()
            ?: throw IllegalArgumentException("payment-fact-inbox signing key secret must be Base64")

        override fun toString(): String = "SigningKey(keyId=$keyId, secret=****)"
    }

    init {
        require(signingKeys.size in 1..MAX_KEYS) { "1..$MAX_KEYS payment-fact-inbox signing keys are required (two only while rotating)" }
        require(signingKeys.map { it.keyId }.toSet().size == signingKeys.size) { "payment-fact-inbox signing key ids must be unique" }
        signingKeys.forEach {
            require(KEY_ID.matches(it.keyId)) { "payment-fact-inbox signing key id must be 1..32 characters of [A-Za-z0-9._-]" }
            require(it.bytes.size >= MIN_KEY_BYTES) { "payment-fact-inbox signing key must decode to at least $MIN_KEY_BYTES bytes" }
        }
        require(clockWindow >= MIN_WINDOW && clockWindow <= MAX_WINDOW) { "payment-fact-inbox clock-window must be within 1s..10m" }
        require(clockWindow.nano == 0) { "payment-fact-inbox clock-window must be whole seconds" }
        require(maxBodyBytes in MIN_BODY..MAX_BODY) { "payment-fact-inbox max-body-bytes must be within $MIN_BODY..$MAX_BODY" }
    }

    fun key(keyId: String): ByteArray? = signingKeys.firstOrNull { it.keyId == keyId }?.bytes

    override fun toString(): String = "PaymentFactInboxSettings(keys=${signingKeys.map { it.keyId }}, clockWindow=$clockWindow, maxBodyBytes=$maxBodyBytes)"

    companion object {
        const val MAX_KEYS = 2
        const val MIN_KEY_BYTES = 32
        const val MIN_BODY = 1024
        const val MAX_BODY = 1024 * 1024
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
        private val MIN_WINDOW: Duration = Duration.ofSeconds(1)
        private val MAX_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentFactInboxSettings::class)
class PaymentFactInboxConfiguration
