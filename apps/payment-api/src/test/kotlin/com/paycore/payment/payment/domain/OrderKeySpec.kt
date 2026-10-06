package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * OrderKey = (environment, merchantAccountId, merchantOrderId) — 확정 문서 §1, D-CPD-001(environment TEST/LIVE).
 * merchantOrderId는 입력 그대로 비교하고(D-CP-001, 사용자 확정 2026-10-06) 형식은 D-CPC-002(경계값은 MerchantRequestIdFormatSpec).
 * MID의 비정규화는 D-CP-001이 아니라 구현 관례(fail-closed, PG 발급 값)이며 MID 형식은 MASTER 발급 규칙이라 domain은 빈 값만 거부한다.
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

    Given("merchant order IDs that differ only by case or spaces (D-CP-001: compared as given; D-CPC-002: spaces rejected)") {
        Then("case is not folded") {
            MerchantOrderId("ORDER-1") shouldNotBe MerchantOrderId("order-1")
        }
        Then("spaces are not trimmed into a valid ID: they are rejected") {
            shouldThrow<IllegalArgumentException> { MerchantOrderId("order-1 ") }
            shouldThrow<IllegalArgumentException> { MerchantOrderId(" ") }
        }
    }

    Given("MIDs that differ only by case or surrounding spaces (implementation convention: fail-closed, PG-issued value)") {
        Then("case is not folded") {
            MerchantAccountId("MID-A") shouldNotBe MerchantAccountId("mid-a")
        }
        Then("spaces are not trimmed and the value is kept as given (MID format belongs to MASTER issuance rules)") {
            MerchantAccountId(" mid-a") shouldNotBe MerchantAccountId("mid-a")
            MerchantAccountId(" mid-a").value shouldBe " mid-a"
        }
    }

    Given("identifiers containing an unpaired UTF-16 surrogate (CP-CONTRACT-001 §3.1, L-3)") {
        val unpaired = listOf("mid\uD800", "\uDC00mid", "\uDC00\uD800")
        Then("a MID is rejected at creation (a DB key: a JDBC/UTF-8 '?' substitution would merge it with another MID)") {
            unpaired.forEach { s -> shouldThrow<IllegalArgumentException> { MerchantAccountId(s) } }
        }
        Then("a MID with a valid surrogate pair is kept as given") {
            MerchantAccountId("mid-😀").value shouldBe "mid-😀"
        }
        Then("merchant order ID and idempotency key already reject them: D-CPC-002 allows ASCII only") {
            unpaired.forEach { s ->
                shouldThrow<IllegalArgumentException> { MerchantOrderId(s) }
                shouldThrow<IllegalArgumentException> { IdempotencyKey(s) }
            }
            shouldThrow<IllegalArgumentException> { MerchantOrderId("order-😀") }
            shouldThrow<IllegalArgumentException> { IdempotencyKey("key-😀") }
        }
    }

    Given("an empty identifier (technical invariant: an ID must have a value)") {
        Then("every domain identifier rejects it") {
            shouldThrow<IllegalArgumentException> { MerchantAccountId("") }
            shouldThrow<IllegalArgumentException> { MerchantOrderId("") }
            shouldThrow<IllegalArgumentException> { MerchantSiteId("") }
            shouldThrow<IllegalArgumentException> { IdempotencyKey("") }
            shouldThrow<IllegalArgumentException> { PaymentId("") }
            shouldThrow<IllegalArgumentException> { OrderIntentFingerprint("") }
        }
    }

    Given("order intent fingerprints (computed by OrderIntent.fingerprint, CP-CONTRACT-001 §6)") {
        Then("control and Payment compare them as opaque values") {
            OrderIntentFingerprint("fixture:intent-A") shouldBe OrderIntentFingerprint("fixture:intent-A")
            OrderIntentFingerprint("fixture:intent-A") shouldNotBe OrderIntentFingerprint("fixture:intent-a")
        }
    }
})
