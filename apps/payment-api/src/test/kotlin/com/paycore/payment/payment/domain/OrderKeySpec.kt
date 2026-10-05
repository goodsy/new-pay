package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * OrderKey = (environment, merchantAccountId, merchantOrderId) — 확정 문서 §1, D-CPD-001(environment TEST/LIVE).
 * ID는 입력 그대로 비교한다(D-CP-001, 사용자 확정 2026-10-06: trim·대소문자 변환·Unicode 정규화 없음). 길이·허용 문자와
 * 공백만인 값의 허용 여부는 API 계약(CP-CONTRACT-001) 범위라 domain에서 정하지 않는다.
 */
class OrderKeySpec : BehaviorSpec({

    val mid = MerchantAccountId("demo-mid-001")
    val order = MerchantOrderId("demo-order-0001")

    Given("the order key environment (D-CPD-001)") {
        Then("it has exactly TEST and LIVE") {
            Environment.entries.map { it.name } shouldBe listOf("TEST", "LIVE")
        }
    }

    Given("an order key TEST + demo MID + demo order") {
        val key = OrderKey(Environment.TEST, mid, order)

        When("the same three parts are given again") {
            Then("it is the same order") {
                key shouldBe OrderKey(Environment.TEST, MerchantAccountId("demo-mid-001"), MerchantOrderId("demo-order-0001"))
            }
        }
        When("only the environment is LIVE") {
            Then("it is a different order") { key shouldNotBe OrderKey(Environment.LIVE, mid, order) }
        }
        When("only the MID differs (orders are MID-scoped)") {
            Then("it is a different order") { key shouldNotBe OrderKey(Environment.TEST, MerchantAccountId("demo-mid-002"), order) }
        }
        When("only the merchant order ID differs") {
            Then("it is a different order") { key shouldNotBe OrderKey(Environment.TEST, mid, MerchantOrderId("demo-order-0002")) }
        }
    }

    Given("IDs that differ only by case or surrounding spaces (D-CP-001: compared as given, no normalization)") {
        Then("case is not folded") {
            MerchantOrderId("ORDER-1") shouldNotBe MerchantOrderId("order-1")
            MerchantAccountId("MID-A") shouldNotBe MerchantAccountId("mid-a")
        }
        Then("spaces are not trimmed and the value is kept as given") {
            MerchantOrderId("order-1 ") shouldNotBe MerchantOrderId("order-1")
            MerchantAccountId(" mid-a") shouldNotBe MerchantAccountId("mid-a")
            MerchantOrderId("order-1 ").value shouldBe "order-1 "
        }
        Then("a whitespace-only ID is not rejected by the domain (allowed characters belong to the CP-CONTRACT-001 API contract)") {
            MerchantOrderId(" ").value shouldBe " "
        }
    }

    Given("an empty identifier (technical invariant: an ID must have a value)") {
        Then("every domain identifier rejects it") {
            shouldThrow<IllegalArgumentException> { MerchantAccountId("") }
            shouldThrow<IllegalArgumentException> { MerchantOrderId("") }
            shouldThrow<IllegalArgumentException> { PaymentId("") }
            shouldThrow<IllegalArgumentException> { OrderIntentFingerprint("") }
        }
    }

    Given("order intent fingerprints computed by the application") {
        Then("they are compared as opaque values") {
            OrderIntentFingerprint("fixture:intent-A") shouldBe OrderIntentFingerprint("fixture:intent-A")
            OrderIntentFingerprint("fixture:intent-A") shouldNotBe OrderIntentFingerprint("fixture:intent-a")
        }
    }
})
