package com.paycore.financial.paymentfact.adapter.web

import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

/**
 * FINANCIAL 쪽 계약 시험(golden `contracts/payment-fact-relay-v1.golden.json` — harness/tests/test_payment_fact_relay_golden.py가 Python으로 독립 생성, payment-api 사본과 바이트 동일).
 * 서명 검증 판정·봉투 해석(effect key·aggregate·UNSUPPORTED)을 golden 벡터로 고정한다.
 */
class PaymentFactRelayGoldenSpec : FunSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        requireNotNull(PaymentFactRelayGoldenSpec::class.java.getResourceAsStream("/contracts/payment-fact-relay-v1.golden.json")) { "golden missing" },
    )
    val keys: Map<String, ByteArray> = golden.get("keys").items().associate { it.get("kid").asString() to Base64.getDecoder().decode(it.get("secretBase64").asString()) }
    val window = Duration.ofSeconds(golden.get("windowSeconds").asLong())

    test("signature constants match the golden contract") {
        PaymentFactSignature.HEADER shouldBe golden.get("header").asString()
        PaymentFactSignature.CANONICAL_TAG shouldBe golden.get("canonicalTag").asString()
        PaymentFactSignature.METHOD shouldBe golden.get("method").asString()
        PaymentFactSignature.PATH shouldBe golden.get("path").asString()
        PaymentFactInboxController.CONSUMER shouldBe golden.get("consumer").asString()
    }

    test("every golden verify case gets the golden decision (window inclusive, kid, signature, grammar)") {
        golden.get("verifyCases").items().forEach { c ->
            val known = c.get("knownKids").items().map { it.asString() }.toSet()
            val header = c.get("header").takeUnless { it.isNull }?.asString()
            val result = PaymentFactSignature.verify(header, c.get("body").asString().toByteArray(), c.get("now").asLong(), window) { kid ->
                keys[kid]?.takeIf { kid in known }
            }
            val expected = c.get("expected")
            val actual = when (result) {
                is PaymentFactSignature.Verification.Accepted -> "ACCEPT" to null
                is PaymentFactSignature.Verification.Rejected -> "REJECT" to result.reason.name
            }
            withClue(c.get("name").asString()) {
                actual shouldBe (expected.get("result").asString() to expected.get("reason").takeUnless { it.isNull }?.asString())
            }
        }
    }

    test("golden envelopes: canonical bytes, signature acceptance, and parsing into facts or UNSUPPORTED") {
        golden.get("envelopes").items().forEach { e ->
            val name = e.get("input").get("name").asString()
            val body = e.get("body").asString().toByteArray(Charsets.UTF_8)
            withClue(name) {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)) shouldBe e.get("bodySha256").asString()
                PaymentFactSignature.canonical(e.get("t").asLong().toString(), body) shouldBe e.get("canonical").asString()
                PaymentFactSignature.verify(e.get("header").asString(), body, e.get("t").asLong(), window, keys::get)
                    .shouldBeInstanceOf<PaymentFactSignature.Verification.Accepted>().keyId shouldBe e.get("kid").asString()
                val parsed = PaymentFactEnvelopeParser.parse(body, e.get("kid").asString())
                parsed.eventId shouldBe e.get("input").get("eventId").asString()
                parsed.effectKey shouldBe e.get("input").get("effectKey").asString()
                parsed.rawSha256 shouldBe e.get("bodySha256").asString()
                when (name) {
                    "authorized" -> parsed.fact.shouldBeInstanceOf<AuthorizedPaymentFact>().money.minor shouldBe 10_000L
                    "cancelled-partial-hangul-site" -> parsed.fact.shouldBeInstanceOf<CancelledPaymentFact>().identity.merchantSiteId shouldBe "사이트-001"
                    "unsupported-schema-version" -> parsed.fact shouldBe null
                    else -> error("unexpected golden envelope $name")
                }
            }
        }
    }
})

private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

private inline fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue(clue, block)
