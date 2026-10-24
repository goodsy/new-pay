package com.paycore.simulator.merchant.adapter.integration

import com.paycore.simulator.merchant.application.required.MerchantReceiver
import com.paycore.simulator.merchant.application.required.MerchantWebhookReceivers
import com.paycore.simulator.merchant.domain.ReceiverScenario
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 가상 가맹점 수신기 설정(`paycore.simulator.merchant`, WEBHOOK-001 §9 — **Demo, 운영값 아님**). 운영 기본값 없음: 수신기를 설정하지 않으면 0개(모든 수신 404 — fail-closed),
 * 수신기가 있으면 `signature-window`가 반드시 있어야 한다. 비밀은 PG 시험 서명 키와 같은 문법(`whsec_` + base64url 43~128자 — 환경변수·시험 자원으로만, 저장소에 운영 비밀 없음).
 * 같은 PG client만 그 수신기의 원장을 조회한다. toString에 비밀을 넣지 않는다.
 */
@ConfigurationProperties(prefix = "paycore.simulator.merchant")
class MerchantReceiverSettings(
    signatureWindow: Duration?,
    receivers: List<Receiver>?,
) : MerchantWebhookReceivers {

    private val configured: List<Receiver> = receivers.orEmpty()
    private val window: Duration? = signatureWindow

    class Key(val keyId: String, val secret: String) {
        init {
            require(KEY_ID.matches(keyId)) { "merchant receiver key id must be 1..40 characters of [A-Za-z0-9_-]" }
            require(SECRET.matches(secret)) { "merchant receiver secret[$keyId] must be whsec_ followed by 43..128 base64url characters" }
        }

        override fun toString(): String = "Key(keyId=$keyId)"
    }

    enum class Scenario { OK, FAIL_THEN_OK, DELAYED, REDIRECT, LARGE_BODY }

    class Receiver(val id: String, val client: String, val keys: List<Key>, val scenario: Scenario, val failures: Int?, val delay: Duration?, val bodyBytes: Int?) {
        init {
            require(ID.matches(id)) { "merchant receiver id must be 1..64 of [a-z0-9-]" }
            require(ID.matches(client)) { "merchant receiver client must be 1..64 of [a-z0-9-]" }
            require(keys.size in 1..MAX_KEYS && keys.map { it.keyId }.toSet().size == keys.size) { "merchant receiver $id needs 1..$MAX_KEYS keys with unique ids" }
        }

        fun toScenario(): ReceiverScenario = when (scenario) {
            Scenario.OK -> ReceiverScenario.Ok
            Scenario.FAIL_THEN_OK -> ReceiverScenario.FailThenOk(requireNotNull(failures) { "merchant receiver $id: FAIL_THEN_OK needs failures" })
            Scenario.DELAYED -> ReceiverScenario.Delayed(requireNotNull(delay) { "merchant receiver $id: DELAYED needs delay" })
            Scenario.REDIRECT -> ReceiverScenario.Redirect
            Scenario.LARGE_BODY -> ReceiverScenario.LargeBody(requireNotNull(bodyBytes) { "merchant receiver $id: LARGE_BODY needs body-bytes" })
        }

        override fun toString(): String = "Receiver(id=$id, client=$client, keys=$keys, scenario=$scenario)"
    }

    private val byId: Map<String, MerchantReceiver>

    init {
        require(configured.map { it.id }.toSet().size == configured.size) { "merchant receiver ids must be unique" }
        if (configured.isNotEmpty()) {
            val w = requireNotNull(window) { "paycore.simulator.merchant.signature-window is required when receivers are configured" }
            require(!w.isNegative && !w.isZero) { "signature-window must be positive" }
        }
        byId = configured.associate { r ->
            r.id to MerchantReceiver(r.id, r.client, r.keys.associate { it.keyId to it.secret.toByteArray(Charsets.UTF_8) }, r.toScenario())
        }
    }

    override val signatureWindow: Duration get() = checkNotNull(window) { "no merchant receivers are configured" }

    override fun find(receiverId: String): MerchantReceiver? = byId[receiverId]

    override fun toString(): String = "MerchantReceiverSettings(signatureWindow=$window, receivers=$configured)"

    private companion object {
        const val MAX_KEYS = 2
        val ID = Regex("^[a-z0-9-]{1,64}$")
        val KEY_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        val SECRET = Regex("^whsec_[A-Za-z0-9_-]{43,128}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantReceiverSettings::class)
class MerchantReceiverSettingsConfiguration
