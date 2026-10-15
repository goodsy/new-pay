package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 요청 fingerprint r1(CP-CONTRACT-001 §7, CP-API-001 명세 §6). 기대값은 **앱 코드와 독립**으로 만든 golden 파일
 * (`contracts/create-payment-request-r1.golden.json`, 생성 절차 harness/tests/test_create_payment_request_golden.py — HARNESS-SELFTEST가 매번
 * 다시 만들어 바이트 비교)에서 읽는다. 값을 이 시험 코드에 복제하지 않는다.
 */
class RequestFingerprintV1Spec : BehaviorSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        checkNotNull(RequestFingerprintV1Spec::class.java.getResource("/contracts/create-payment-request-r1.golden.json")).readText(),
    )
    val vectors = golden.get("vectors").values().toList()

    fun commandOf(vector: JsonNode) = run {
        val f = vector.get("fields").values().associate { it.get(0).stringValue() to it.get(1).takeUnless { v -> v.isNull }?.stringValue() }
        CreatePaymentFixture.command(
            order = OrderKey(Environment.LIVE, MerchantAccountId(f.getValue("merchantAccountId")!!), MerchantOrderId(f.getValue("merchantOrderId")!!)),
            intent = OrderIntent(MerchantSiteId(f.getValue("merchantSiteId")!!), Money(f.getValue("amount")!!.toLong(), CurrencyCode(f.getValue("currency")!!))),
            orderName = f["orderName"],
        )
    }

    Given("the independently generated golden vectors (with and without the optional orderName)") {
        Then("there are five vectors including an absent orderName") {
            vectors shouldHaveSize 5
            vectors.count { v -> v.get("fields").values().any { it.get(0).stringValue() == "orderName" && it.get(1).isNull } } shouldBe 1
        }
        vectors.forEach { vector ->
            Then("canonical form and r1 match: ${vector.get("name").stringValue()}") {
                val command = commandOf(vector)
                val canonical = RequestFingerprints.canonicalV1(RequestFingerprints.fieldsOf(command))
                canonical shouldBe vector.get("canonical").stringValue()
                canonical.toByteArray(Charsets.UTF_8).size shouldBe vector.get("canonicalUtf8Bytes").intValue()
                RequestFingerprints.current(command) shouldBe RequestFingerprint(vector.get("fingerprint").stringValue())
            }
        }
    }

    Given("the request fingerprint versus the order-intent fingerprint (contract §7 table)") {
        Then("the same key with only orderName changed is a different request; the fixed order input is the same") {
            val a = commandOf(vectors[0])
            val d = commandOf(vectors[3])
            RequestFingerprints.current(a) shouldNotBe RequestFingerprints.current(d)
            a.orderIntent.fingerprint() shouldBe d.orderIntent.fingerprint()
        }
    }

    Given("strict UTF-8 (contract §6.2)") {
        Then("an unpaired surrogate is reported, never replaced — \"a\\uD800\" cannot share a fingerprint with \"a?\"") {
            shouldThrow<IllegalArgumentException> { RequestFingerprints.canonicalV1(listOf("orderName" to "a\uD800")) }
            RequestFingerprints.canonicalV1(listOf("orderName" to "a?")) shouldBe "30:paycore.create-payment-request1:19:orderName12:a?"
        }
    }

    Given("versioned comparison (contract §6.4)") {
        Then("a stored version prefix that is not an Int is unsupported (503 path), never a NumberFormatException defect (review round2 n-a)") {
            RequestFingerprints.versionOfStoredValue("r1:" + "a".repeat(64)) shouldBe 1
            RequestFingerprints.versionOfStoredValue("r12:" + "a".repeat(64)) shouldBe 12
            listOf("r99999999999:" + "a".repeat(64), "rx:" + "a".repeat(64), "r:" + "a".repeat(64), "v1:" + "a".repeat(64), "r1").forEach { stored ->
                io.kotest.assertions.throwables.shouldThrow<UnsupportedFingerprintVersionException> { RequestFingerprints.versionOfStoredValue(stored) }
            }
        }
        Then("the stored version is read from the prefix and an unknown version is refused instead of compared") {
            RequestFingerprints.versionOf(RequestFingerprint("r1:" + "0".repeat(64))) shouldBe 1
            RequestFingerprints.versionOf(RequestFingerprint("r12:" + "0".repeat(64))) shouldBe 12
            shouldThrow<UnsupportedFingerprintVersionException> { RequestFingerprints.compute(commandOf(vectors[0]), 2) }
        }
    }
})
