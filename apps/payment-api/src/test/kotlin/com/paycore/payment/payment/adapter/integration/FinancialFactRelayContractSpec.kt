package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.FinancialFactDispatchOutcome
import com.paycore.payment.payment.application.required.FinancialFactRelayEnvelope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * PAYMENT 쪽 결제 사실 채널 계약(FIN-FACT-RELAY-001 §4.1 — golden `contracts/payment-fact-relay-v1.golden.json`, harness Python 독립 생성, financial-batch 사본과 바이트 동일).
 * 봉투 본문·서명 헤더 바이트, ACK 판정, HTTP 상태 분류를 실제 dispatcher(실제 소켓 — [StubFinancialInbox] 대신 상태·본문만 돌려주는 대역)로 고정한다. 설정 기동 검사도 여기서 본다.
 */
class FinancialFactRelayContractSpec : BehaviorSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        requireNotNull(FinancialFactRelayContractSpec::class.java.getResourceAsStream("/contracts/payment-fact-relay-v1.golden.json")) { "golden missing" },
    )
    fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

    fun envelopeOf(input: JsonNode, outboxId: Long = 1) = FinancialFactRelayEnvelope(
        outboxId = outboxId, eventId = input.get("eventId").asString(), effectKey = input.get("effectKey").asString(), eventType = input.get("eventType").asString(),
        schemaVersion = input.get("schemaVersion").asInt(), eventCategory = input.get("eventCategory").asString(), producer = input.get("producer").asString(),
        aggregateType = input.get("aggregateType").asString(), aggregateId = input.get("aggregateId").asString(), aggregateVersion = input.get("aggregateVersion").asLong(),
        occurredAt = Instant.parse(input.get("occurredAt").asString()), attempts = 0, payloadJson = input.get("payloadJson").asString(),
    )

    Given("FR-P1 the golden envelopes") {
        Then("PAYMENT builds byte-identical bodies and signature headers from the stored Outbox values") {
            golden.get("header").asString() shouldBe FinancialFactEnvelope.SIGNATURE_HEADER
            golden.get("canonicalTag").asString() shouldBe FinancialFactEnvelope.CANONICAL_TAG
            golden.get("path").asString() shouldBe FinancialFactEnvelope.PATH
            golden.get("consumer").asString() shouldBe HttpFinancialFactDispatcher.EXPECTED_CONSUMER
            val key = java.util.Base64.getDecoder().decode(golden.get("keys").items().first { it.get("kid").asString() == "fact-golden-k1" }.get("secretBase64").asString())
            golden.get("envelopes").items().forEach { v ->
                withClue(v.get("input").get("name").asString()) {
                    val body = FinancialFactEnvelope.body(envelopeOf(v.get("input")))
                    String(body, Charsets.UTF_8) shouldBe v.get("body").asString()
                    FinancialFactEnvelope.canonical(v.get("t").asLong().toString(), body) shouldBe v.get("canonical").asString()
                    FinancialFactEnvelope.signatureHeader(v.get("kid").asString(), key, v.get("t").asLong(), body) shouldBe v.get("header").asString()
                }
            }
        }
    }

    Given("FR-P2 the golden ACK cases through the real dispatcher (2xx with each body)") {
        golden.get("ackCases").items().forEach { c ->
            Then("ack ${c.get("name").asString()}") {
                val ackBody = c.get("body").asString()
                StubFinancialInbox().use { stub ->
                    val sent = envelopeOf(golden.get("envelopes").items().first().get("input"))
                    stub.script(sent.eventId, StubFinancialInbox.Behavior.CommitThenAck(ackBody))
                    val outcome = HttpFinancialFactDispatcher(StubFinancialInbox.settings(stub.endpoint)).dispatch(sent)
                    val expected = c.get("expected")
                    when (expected.get("result").asString()) {
                        "ACKNOWLEDGED" -> outcome shouldBe FinancialFactDispatchOutcome.Acknowledged(
                            "financial-batch", expected.get("outcome").asString(), expected.get("originalOutcome").takeUnless { it.isNull }?.asString(),
                        )
                        else -> outcome shouldBe FinancialFactDispatchOutcome.Retryable("ACK_UNREADABLE")
                    }
                }
            }
        }
    }

    Given("FR-P3 the golden HTTP status classification through the real dispatcher") {
        Then("non-2xx statuses without a FINANCIAL contract code are retryable; connection refused is retryable") {
            StubFinancialInbox().use { stub ->
                val sent = envelopeOf(golden.get("envelopes").items().first().get("input"))
                val dispatcher = HttpFinancialFactDispatcher(StubFinancialInbox.settings(stub.endpoint))
                golden.get("statusClassification").properties().filter { it.value.asString() == "RETRYABLE" }.forEach { (status, _) ->
                    stub.script(sent.eventId, StubFinancialInbox.Behavior.Status(status.toInt()))
                    withClue(status) { dispatcher.dispatch(sent) shouldBe FinancialFactDispatchOutcome.Retryable("HTTP_$status") }
                }
            }
            val closed = StubFinancialInbox().let { s -> s.endpoint.also { s.close() } }
            HttpFinancialFactDispatcher(StubFinancialInbox.settings(closed)).dispatch(envelopeOf(golden.get("envelopes").items().first().get("input")))
                .shouldBeInstanceOf<FinancialFactDispatchOutcome.Retryable>().code shouldBe "CONNECTION"
        }
        Then("m-1: 400·409·422 are quarantined only with the matching FINANCIAL contract error code (golden statusCases)") {
            golden.get("rejectCodes").properties().associate { it.key.toInt() to it.value.items().map { c -> c.asString() }.toSet() } shouldBe
                HttpFinancialFactDispatcher.REJECT_CODES
            StubFinancialInbox().use { stub ->
                val sent = envelopeOf(golden.get("envelopes").items().first().get("input"))
                val dispatcher = HttpFinancialFactDispatcher(StubFinancialInbox.settings(stub.endpoint))
                golden.get("statusCases").items().forEach { c ->
                    val status = c.get("status").asInt()
                    val body = c.get("body").asString()
                    stub.script(sent.eventId, StubFinancialInbox.Behavior.Status(status, body))
                    val outcome = dispatcher.dispatch(sent)
                    withClue("$status $body") {
                        when (c.get("expected").asString()) {
                            "REJECTED" -> outcome.shouldBeInstanceOf<FinancialFactDispatchOutcome.Rejected>().code shouldBe
                                "HTTP_${status}_" + JsonMapper.builder().build().readTree(body).get("error").asString()
                            else -> outcome shouldBe FinancialFactDispatchOutcome.Retryable("HTTP_$status")
                        }
                    }
                }
            }
        }
        Then("a response slower than the request timeout is a retryable TIMEOUT within the send budget (the inbox may have committed — the resend converges to DUPLICATE)") {
            StubFinancialInbox().use { stub ->
                val sent = envelopeOf(golden.get("envelopes").items().first().get("input"))
                stub.script(sent.eventId, StubFinancialInbox.Behavior.CommitThenStall(Duration.ofMillis(1500)))
                val dispatcher = HttpFinancialFactDispatcher(StubFinancialInbox.settings(stub.endpoint, requestTimeout = Duration.ofMillis(500)))
                val started = System.nanoTime()
                dispatcher.dispatch(sent) shouldBe FinancialFactDispatchOutcome.Retryable("TIMEOUT")
                (Duration.ofNanos(System.nanoTime() - started) <= dispatcher.sendBudget.plusMillis(300)) shouldBe true
                stub.isCommitted(sent.eventId) shouldBe true
                dispatcher.dispatch(sent) shouldBe FinancialFactDispatchOutcome.Acknowledged("financial-batch", "DUPLICATE", "APPLIED")
                stub.receivedFor(sent.eventId).map { String(it.body, Charsets.UTF_8) }.toSet().size shouldBe 1
                stub.receivedFor(sent.eventId).all { it.signatureValid } shouldBe true
            }
        }
    }

    Given("FR-P4 startup checks of the dispatch settings (fail-closed, no defaults)") {
        val ok = StubFinancialInbox.settings(URI.create("http://127.0.0.1:1/internal/v1/payment-facts"))
        Then("a short key, a wrong path or a query are refused at binding") {
            shouldThrow<IllegalArgumentException> {
                FinancialFactRelaySettings(ok.endpoint, ok.connectTimeout, ok.requestTimeout, "k1", java.util.Base64.getEncoder().encodeToString(ByteArray(31)), 4096)
            }
            shouldThrow<IllegalArgumentException> {
                FinancialFactRelaySettings(URI.create("http://127.0.0.1:1/other"), ok.connectTimeout, ok.requestTimeout, "k1", StubFinancialInbox.KEY_BASE64, 4096)
            }
            shouldThrow<IllegalArgumentException> {
                FinancialFactRelaySettings(URI.create("http://127.0.0.1:1/internal/v1/payment-facts?x=1"), ok.connectTimeout, ok.requestTimeout, "k1", StubFinancialInbox.KEY_BASE64, 4096)
            }
            ok.toString() shouldNotContain StubFinancialInbox.KEY_BASE64
        }
        Then("the relay key must not equal (bytes or kid) an entitlement-inbox, admin-channel or merchant-webhook test key (n-5)") {
            fun binder(vararg entries: Pair<String, String>) = Binder(MapConfigurationPropertySource(mapOf(*entries)))
            FinancialFactRelayKeySeparation(ok, binder("paycore.payment.entitlement.inbox.signing-keys[0].key-id" to "other", "paycore.payment.entitlement.inbox.signing-keys[0].secret" to "b3RoZXI="))
            shouldThrow<IllegalStateException> {
                FinancialFactRelayKeySeparation(
                    ok, binder("paycore.payment.admin-channel.signing-keys[0].key-id" to "adm", "paycore.payment.admin-channel.signing-keys[0].secret" to StubFinancialInbox.KEY_BASE64),
                )
            }.message shouldNotContain StubFinancialInbox.KEY_BASE64
            shouldThrow<IllegalStateException> {
                FinancialFactRelayKeySeparation(
                    ok, binder("paycore.payment.entitlement.inbox.signing-keys[0].key-id" to StubFinancialInbox.KID, "paycore.payment.entitlement.inbox.signing-keys[0].secret" to "b3RoZXI="),
                )
            }
            val whsec = "whsec_" + "A".repeat(43)
            fun webhook(kid: String) = binder(
                "paycore.webhook.test-signing-keys[0].merchant-account-id" to "m", "paycore.webhook.test-signing-keys[0].key-id" to kid,
                "paycore.webhook.test-signing-keys[0].secret" to whsec,
            )
            FinancialFactRelayKeySeparation(ok, webhook("wh1"))
            val sameAsWebhook = FinancialFactRelaySettings(ok.endpoint, ok.connectTimeout, ok.requestTimeout, "k9", java.util.Base64.getEncoder().encodeToString(whsec.toByteArray()), 4096)
            shouldThrow<IllegalStateException> { FinancialFactRelayKeySeparation(sameAsWebhook, webhook("wh1")) }
            shouldThrow<IllegalStateException> { FinancialFactRelayKeySeparation(ok, webhook(StubFinancialInbox.KID)) }
        }
        Then("the claim lease must exceed connect + request timeout + 1s") {
            fun binder(lease: String) = Binder(MapConfigurationPropertySource(mapOf("paycore.financial-relay.job.claim-lease" to lease)))
            FinancialFactRelayTimingConsistency(ok, binder("PT3S"))
            shouldThrow<IllegalStateException> { FinancialFactRelayTimingConsistency(ok, binder("PT2.5S")) }
        }
    }
})
