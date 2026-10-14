package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.RelayEnvelope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * 권리 Snapshot 설정(명세 §8): 운영 기본값 없음 — 값이 없거나 범위 밖이면 기동 실패. 시험 Demo 값으로만 뜬다. 봉투·서명 형식(D-MS-004)도 고정한다.
 */
class EntitlementSettingsSpec : FunSpec({

    val demo = arrayOf(
        "paycore.master.entitlement.lease-duration=PT60S",
        "paycore.master.entitlement.renew-before=PT30S",
        "paycore.master.entitlement.relay.batch-size=50",
        "paycore.master.entitlement.relay.claim-lease=PT30S",
        "paycore.master.entitlement.relay.backoff-initial=PT1S",
        "paycore.master.entitlement.relay.backoff-max=PT30S",
        "paycore.master.entitlement.relay.backoff-jitter-ratio=0.2",
        "paycore.master.entitlement.dispatch.endpoint=http://127.0.0.1:8080/internal/v1/entitlement-snapshots",
        "paycore.master.entitlement.dispatch.connect-timeout=PT1S",
        "paycore.master.entitlement.dispatch.read-timeout=PT3S",
        "paycore.master.entitlement.dispatch.signing-key-id=test-k1",
        "paycore.master.entitlement.dispatch.signing-key=dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh",
    )

    fun runner() = ApplicationContextRunner().withUserConfiguration(EntitlementSettingsConfiguration::class.java)

    test("the production application.yml has no entitlement timing, relay, dispatch or scheduling values (no production defaults)") {
        val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()
        listOf(
            "paycore.master.entitlement.lease-duration", "paycore.master.entitlement.renew-before", "paycore.master.entitlement.scheduling-enabled",
            "paycore.master.entitlement.relay.poll-interval", "paycore.master.entitlement.dispatch.signing-key",
        ).map { yaml.getProperty(it) } shouldBe listOf(null, null, null, null, null)
    }

    test("without any value the context fails to start (fail-closed instead of a silent default)") {
        runner().run { (it.startupFailure != null) shouldBe true }
    }

    test("with the Demo values the settings bind; toString never shows the signing key") {
        runner().withPropertyValues(*demo).run { context ->
            context.startupFailure shouldBe null
            val settings = context.getBean(EntitlementSettings::class.java)
            (settings.leaseDuration to settings.relayBatchSize) shouldBe (Duration.ofSeconds(60) to 50)
            settings.toString() shouldNotContain "dGVzdC1vbmx5"
        }
    }

    test("an out-of-range value fails startup: renew-before >= lease, a short signing key, jitter >= 1") {
        listOf(
            "paycore.master.entitlement.renew-before=PT60S",
            "paycore.master.entitlement.dispatch.signing-key=c2hvcnQ=",
            "paycore.master.entitlement.relay.backoff-jitter-ratio=1.0",
        ).map { override -> runner().withPropertyValues(*demo, override).run { (it.startupFailure != null) shouldBe true } }
    }

    test("the envelope v1 carries the event metadata and the payload object; the signature is HMAC-SHA256 over the exact body bytes") {
        val envelope = RelayEnvelope(
            eventId = "11111111-2222-3333-4444-555555555555", eventType = "EntitlementSnapshot", schemaVersion = 1, producer = "master-api",
            aggregateId = "mer_01J00000000000000000000000", aggregateVersion = 7, occurredAt = Instant.parse("2026-10-09T02:00:00.123Z"),
            attemptCount = 0, payloadJson = """{"revision":7,"merchantId":"mer_01J00000000000000000000000"}""",
        )
        val body = EntitlementEnvelope.body(envelope)
        val node = JsonMapper.builder().build().readTree(body)
        listOf(
            node.get("envelopeVersion").intValue(), node.get("eventId").stringValue(), node.get("aggregateVersion").longValue(),
            node.get("occurredAt").stringValue(), node.get("payload").get("revision").longValue(),
        ) shouldBe listOf(1, envelope.eventId, 7L, "2026-10-09T02:00:00.123Z", 7L)
        val key = "test-only-entitlement-signing-key-1!".toByteArray()
        EntitlementEnvelope.signatureHeader("test-k1", key, body) shouldBe "keyId=test-k1,v1=" + EntitlementEnvelope.hmacHex(key, body)
        (EntitlementEnvelope.hmacHex(key, body) == EntitlementEnvelope.hmacHex(key, body + ' '.code.toByte())) shouldBe false
    }
})
