package com.paycore.simulator.merchant.adapter.integration

import com.paycore.simulator.merchant.domain.WebhookSignatureCheck
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * T-S1·T-S2(WEBHOOK-001 §9·§21.0): 서명 golden `webhook-signature-v1.golden.json`(harness가 Python `hmac`으로 독립 생성 — payment-api 서명 adapter와 같은 파일)의 검증 사례마다
 * 가상 가맹점 수신기의 판정(domain 문법·창 + adapter HMAC·`MessageDigest.isEqual`)이 golden expected와 같다. ±300초 통과·±301초 거절, 교체 2서명, 본문 1바이트 변경 포함.
 */
class WebhookReceiverVerificationSpec : BehaviorSpec({

    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/webhook-signature-v1.golden.json").inputStream)
    val secrets = golden.get("keys").items().associate { it.get("keyId").asString() to it.get("secret").asString().toByteArray(Charsets.UTF_8) }
    val now = Instant.ofEpochSecond(golden.get("now").asLong())
    val window = Duration.ofSeconds(golden.get("windowSeconds").asLong())
    val matcher = HmacWebhookSignatureMatcher()

    Given("the verification cases of the golden") {
        golden.get("verification").items().forEach { c ->
            Then("case ${c.get("name").asString()}: the receiver verdict is ${c.get("expected").asString()}") {
                val known = c.get("receiverKeyIds").items().map { it.asString() }.toSet()
                val body = c.get("body").asString().toByteArray(Charsets.UTF_8)
                val verification = WebhookSignatureCheck.verify(c.get("header").asString(), now, window, known) { kid, t, v1 ->
                    matcher.matches(secrets.getValue(kid), t, body, v1)
                }
                verification.verdict.name shouldBe c.get("expected").asString()
            }
        }
    }

    Given("the signing vectors of the golden") {
        golden.get("signing").items().forEach { v ->
            Then("vector ${v.get("name").asString()}: every pair of the PG header verifies with its own key") {
                val body = v.get("body").asString().toByteArray(Charsets.UTF_8)
                val pairs = v.get("header").asString().split(',').drop(1).chunked(2).map { (k, s) -> k.removePrefix("kid=") to s.removePrefix("v1=") }
                pairs.size shouldBe v.get("keyIds").size()
                pairs.forEach { (kid, sig) -> matcher.matches(secrets.getValue(kid), v.get("t").asLong(), body, sig) shouldBe true }
                matcher.matches(secrets.getValue(pairs[0].first), v.get("t").asLong() + 1, body, pairs[0].second) shouldBe false
            }
        }
    }
})

private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()
