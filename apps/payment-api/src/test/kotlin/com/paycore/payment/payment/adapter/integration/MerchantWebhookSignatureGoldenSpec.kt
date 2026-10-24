package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.WebhookSigningKey
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * T-S1(WEBHOOK-001 §9·§21.0): 서명 golden `webhook-signature-v1.golden.json`(harness `test_webhook_signature_golden.py`가 Python `hmac`으로 독립 생성 — Simulator 수신기와 같은 파일)의
 * 서명 벡터를 PG 서명 adapter가 바이트 그대로 만든다(단일 키·교체 2키·UTF-8 본문). 비밀은 golden의 시험 전용 Demo 값이다(운영 비밀 아님).
 */
class MerchantWebhookSignatureGoldenSpec : BehaviorSpec({

    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/webhook-signature-v1.golden.json").inputStream)
    val secrets = golden.get("keys").items().associate { it.get("keyId").asString() to it.get("secret").asString() }
    val signer = HmacMerchantWebhookSigner()

    Given("the signing vectors of the golden") {
        Then("there are vectors for one key and for a two-key rotation") {
            golden.get("signing").items().map { it.get("keyIds").size() } shouldBe listOf(1, 2)
            golden.get("headerName").asString() shouldBe "PayCore-Signature"
        }
        golden.get("signing").items().forEach { v ->
            Then("vector ${v.get("name").asString()}: the PG header equals the golden header byte for byte") {
                val keys = v.get("keyIds").items().map { WebhookSigningKey(it.asString(), secrets.getValue(it.asString()).toByteArray(Charsets.UTF_8)) }
                signer.signatureHeader(v.get("t").asLong(), v.get("body").asString().toByteArray(Charsets.UTF_8), keys) shouldBe v.get("header").asString()
            }
        }
    }
})

private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()
