package com.paycore.payment.payment.adapter.integration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * PAYMENT → FINANCIAL 결제 사실 전달 설정(FIN-FACT-RELAY-001 §8 — `paycore.financial-relay.dispatch`). **운영 기본값이 없다**: 값이 없거나 범위 밖이면 기동이 실패한다.
 * 서명 키는 이 채널 전용 키 공간이다([FinancialFactRelayKeySeparation] — 권리 채널·ADMIN 채널 키와 바이트·kid가 같으면 기동 거부). 키는 비밀 저장소(환경변수)에서 주입하고
 * 저장소·설정 파일에 운영 키를 두지 않는다. 운영망 mTLS·내부망 endpoint는 LIVE Gate.
 */
@ConfigurationProperties(prefix = "paycore.financial-relay.dispatch")
class FinancialFactRelaySettings(
    val endpoint: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val signingKeyId: String,
    signingKey: String,
    val maxAckBytes: Int,
) {
    // 디코드 오류 메시지·원인에는 비밀 일부가 들어갈 수 있어 원인을 붙이지 않는다.
    val signingKeyBytes: ByteArray = runCatching { Base64.getDecoder().decode(signingKey) }.getOrNull()
        ?: throw IllegalArgumentException("paycore.financial-relay.dispatch.signing-key must be Base64")

    init {
        require(endpoint.scheme in setOf("http", "https") && endpoint.host != null && endpoint.rawQuery == null) {
            "financial-relay endpoint must be an http(s) URL without query"
        }
        require(endpoint.rawPath == FinancialFactEnvelope.PATH) { "financial-relay endpoint path must be ${FinancialFactEnvelope.PATH} (the signed path)" }
        require(connectTimeout.isPositive && requestTimeout.isPositive) { "financial-relay timeouts must be positive" }
        require(KEY_ID.matches(signingKeyId)) { "financial-relay signing-key-id must be 1..32 characters of [A-Za-z0-9._-]" }
        require(signingKeyBytes.size >= MIN_KEY_BYTES) { "financial-relay signing-key must decode to at least $MIN_KEY_BYTES bytes" }
        require(maxAckBytes in MIN_ACK..MAX_ACK) { "financial-relay max-ack-bytes must be within $MIN_ACK..$MAX_ACK" }
    }

    /** 한 전송의 최대 소요(연결 + 요청). claim lease는 이보다 커야 한다(겹친 전송 방지 — [FinancialFactRelayTimingConsistency]). */
    val sendBudget: Duration get() = connectTimeout.plus(requestTimeout)

    override fun toString(): String =
        "FinancialFactRelaySettings(endpoint=$endpoint, connectTimeout=$connectTimeout, requestTimeout=$requestTimeout, signingKeyId=$signingKeyId, signingKey=****)"

    companion object {
        const val MIN_KEY_BYTES = 32
        const val MIN_ACK = 256
        const val MAX_ACK = 65_536
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
    }
}

/**
 * 전용 키 공간 기동 검사(D-ACQ-001 "MASTER→PAYMENT 권리 전달 패턴 재사용" + ADMIN-PAYMENT-OPS-001 D-OPS-001과 같은 방식): 이 채널의 서명 키가 권리 채널(entitlement inbox)·
 * ADMIN 채널·가맹점 Webhook 시험 서명 키(검토 n-5)와 **바이트가 같거나 kid가 같으면** 기동을 거부한다. Webhook 비밀은 `whsec_…` 문자열 UTF-8 바이트가 HMAC 키이므로 그 바이트와
 * `whsec_` 뒤 base64url을 디코드한 바이트를 모두 비교한다. 다른 모듈·adapter의 설정 타입에 의존하지 않고 `Binder`로 속성 모양만 읽는다. 실패 메시지에 kid·키 원문이 없다.
 */
class FinancialFactRelayKeySeparation(settings: FinancialFactRelaySettings, binder: Binder) {

    /** 다른 채널 키 속성의 모양만(`key-id`, `secret` — 그 밖 속성은 무시). */
    class OtherKey(val keyId: String, val secret: String)

    init {
        fun requireSeparate(keyId: String, candidates: List<ByteArray>) {
            check(keyId != settings.signingKeyId) { "financial-relay signing key id must differ from other channels' key ids (separate key spaces)" }
            check(candidates.none { MessageDigest.isEqual(it, settings.signingKeyBytes) }) {
                "financial-relay signing key must differ from other channels' keys (separate key spaces)"
            }
        }
        BASE64_KEY_SPACES.forEach { property ->
            bind(binder, property).forEach { other ->
                val bytes = runCatching { Base64.getDecoder().decode(other.secret) }.getOrNull()
                    ?: throw IllegalStateException("another channel's signing key secret must be Base64")
                requireSeparate(other.keyId, listOf(bytes))
            }
        }
        bind(binder, WEBHOOK_TEST_KEYS).forEach { other ->
            val raw = other.secret.toByteArray(Charsets.UTF_8)
            val decoded = runCatching { Base64.getUrlDecoder().decode(other.secret.removePrefix("whsec_")) }.getOrNull()
            requireSeparate(other.keyId, listOfNotNull(raw, decoded))
        }
    }

    private fun bind(binder: Binder, property: String): List<OtherKey> =
        binder.bind(property, Bindable.listOf(OtherKey::class.java)).orElse(emptyList()) ?: emptyList()

    private companion object {
        val BASE64_KEY_SPACES = listOf("paycore.payment.entitlement.inbox.signing-keys", "paycore.payment.admin-channel.signing-keys")
        const val WEBHOOK_TEST_KEYS = "paycore.webhook.test-signing-keys"
    }
}

/**
 * claim lease > 한 전송 최대 소요(연결 + 요청) + 1초. 명백히 잘못된 설정만 거르는 최소 조건이며 회차당 진행을 보장하지는 않는다(재검토 m-A — discover·claim 시간 미포함). 겹친 전송 방지 자체는 Relay가 **행마다** 남은 lease를
 * 확인해 지킨다(검토 M-1, FinancialFactRelayService). 배치 크기 × 지연이 lease를 넘으면 남은 행은 다음 회차로 넘어간다(처리량 저하 — 겹친 전송 아님).
 */
class FinancialFactRelayTimingConsistency(settings: FinancialFactRelaySettings, binder: Binder) {
    init {
        val lease = binder.bind(CLAIM_LEASE, Duration::class.java).orElseThrow { IllegalStateException("$CLAIM_LEASE is required") }
        check(lease > settings.sendBudget.plus(MARGIN)) { "$CLAIM_LEASE must exceed connect-timeout + request-timeout + ${MARGIN.seconds}s" }
    }

    private companion object {
        const val CLAIM_LEASE = "paycore.financial-relay.job.claim-lease"
        val MARGIN: Duration = Duration.ofSeconds(1)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinancialFactRelaySettings::class)
class FinancialFactRelayConfiguration {
    @Bean
    fun financialFactRelayKeySeparation(settings: FinancialFactRelaySettings, environment: Environment) =
        FinancialFactRelayKeySeparation(settings, Binder.get(environment))

    @Bean
    fun financialFactRelayTimingConsistency(settings: FinancialFactRelaySettings, environment: Environment) =
        FinancialFactRelayTimingConsistency(settings, Binder.get(environment))
}
