package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * 고정 주문 입력(D-CPC-001, 사용자 확정 2026-10-06) = merchantSiteId + amount + currency. 결제수단·주문명 등 표시 정보는 넣지 않는다.
 * OrderIntentFingerprint v1 계산 규칙은 CP-CONTRACT-001 §6과 같다. 기대값(golden vector)은 계약 문서의 정의대로 별도 도구(Python
 * hashlib)로 계산해 고정한 값이며, 이 구현 코드로 만든 값이 아니다.
 */
class OrderIntentSpec : BehaviorSpec({

    val demoSite = MerchantSiteId("demo-site-001")

    Given("the v1 canonical form") {
        Then("each part is a UTF-8 byte-length-prefixed token in the fixed order: domain tag, version, site, amount, currency") {
            OrderIntent(demoSite, 10_000L.won()).canonicalV1() shouldBe
                "20:paycore.order-intent1:113:demo-site-0015:100003:KRW"
            OrderIntent(demoSite, 1_050L.usdCents()).canonicalV1() shouldBe
                "20:paycore.order-intent1:113:demo-site-0014:10503:USD"
        }
    }

    Given("the v1 fingerprint golden vectors (contract §6.3)") {
        Then("Demo site + 10,000 KRW") {
            OrderIntent(demoSite, 10_000L.won()).fingerprint() shouldBe
                OrderIntentFingerprint("v1:2d6f6ae82692623252de0081f409974629600801d4b17bd5901fc16c22d2d3f3")
        }
        Then("Demo site + 10.50 USD (1,050 cents)") {
            OrderIntent(demoSite, 1_050L.usdCents()).fingerprint() shouldBe
                OrderIntentFingerprint("v1:8704c2d2eac85ec8cdfc55b8278996183f0529622a64d590d1854799f2770e14")
        }
        Then("the value is the version prefix and 64 lower-case hex characters") {
            OrderIntent(demoSite, 10_000L.won()).fingerprint().value shouldMatch Regex("^v1:[0-9a-f]{64}$")
        }
    }

    Given("the same fixed inputs") {
        Then("the fingerprint is deterministic") {
            OrderIntent(MerchantSiteId("demo-site-001"), 10_000L.won()).fingerprint() shouldBe
                OrderIntent(demoSite, Money(10_000, CurrencyCode("KRW"))).fingerprint()
        }
    }

    Given("fixed inputs that differ in one field") {
        val original = OrderIntent(demoSite, 10_000L.won()).fingerprint()
        Then("a different site differs") {
            OrderIntent(MerchantSiteId("demo-site-002"), 10_000L.won()).fingerprint() shouldNotBe original
        }
        Then("a different amount differs") {
            OrderIntent(demoSite, 10_001L.won()).fingerprint() shouldNotBe original
        }
        Then("a different currency with the same minor-unit number differs") {
            OrderIntent(demoSite, 10_000L.usdCents()).fingerprint() shouldNotBe original
        }
        Then("a site differing only by case differs (IDs compared as given)") {
            OrderIntent(MerchantSiteId("DEMO-SITE-001"), 10_000L.won()).fingerprint() shouldNotBe original
        }
    }

    Given("inputs whose plain concatenation would collide") {
        Then("length prefixes keep them apart: site 'x' + 12 vs site 'x1' + 2") {
            val a = OrderIntent(MerchantSiteId("x"), 12L.won())
            val b = OrderIntent(MerchantSiteId("x1"), 2L.won())
            a.canonicalV1() shouldBe "20:paycore.order-intent1:11:x2:123:KRW"
            b.canonicalV1() shouldBe "20:paycore.order-intent1:12:x11:23:KRW"
            a.fingerprint() shouldBe OrderIntentFingerprint("v1:87e4a95435874434a2820c58536799b64dc0800a59fdd6417b9e4a713fa9d85d")
            b.fingerprint() shouldBe OrderIntentFingerprint("v1:8fd0ccda2b6bd3722b67004a09b1e11813a682c260bb930f0e58e8938394a6da")
        }
    }

    Given("the fixed-input type (D-CPC-001)") {
        Then("it holds exactly merchantSiteId and money: payment method and display fields are not part of it") {
            OrderIntent::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.sorted() shouldBe listOf("merchantSiteId", "money")
        }
    }

    Given("a merchant site id (issued by Master)") {
        Then("an empty value is rejected") {
            shouldThrow<IllegalArgumentException> { MerchantSiteId("") }
        }
        Then("it is compared as given") {
            MerchantSiteId("Site-A") shouldNotBe MerchantSiteId("site-a")
        }
    }

    // L-3(CP-CONTRACT-001 §3.1·§6.2): String.toByteArray(UTF_8)는 짝 없는 서로게이트를 '?'로 바꿔 "a\uD800"과 "a?"가 같은 v1
    // fingerprint가 된다. 생성 시 거부(값 객체)와 인코딩 시 malformed 보고(최후 방어선) 두 겹으로 막는다.
    Given("an unpaired UTF-16 surrogate (contract §3.1: 400, §6.2: malformed input is reported, never replaced)") {
        val unpaired = listOf("a\uD800", "\uDC00b", "\uDC00\uD800", "\uD800")

        Then("a merchant site id containing one is rejected at creation") {
            unpaired.forEach { s -> shouldThrow<IllegalArgumentException> { MerchantSiteId(s) } }
        }
        Then("it can never share a v1 fingerprint with the '?'-substituted value") {
            val substituted = OrderIntent(MerchantSiteId("a?"), 10_000L.won()).fingerprint()
            runCatching { OrderIntent(MerchantSiteId("a\uD800"), 10_000L.won()).fingerprint() }.getOrNull() shouldNotBe substituted
        }
        Then("the fingerprint UTF-8 encoding reports it instead of substituting '?'") {
            unpaired.forEach { s -> shouldThrow<IllegalArgumentException> { Utf8Text.encode(s) } }
        }
        Then("the rejection message does not echo the input") {
            val e = shouldThrow<IllegalArgumentException> { MerchantSiteId("secret\uD800") }
            (e.message ?: "").contains("secret") shouldBe false
        }
    }

    Given("well-formed non-ASCII text (supplementary characters are valid surrogate pairs)") {
        Then("it encodes to standard UTF-8 bytes") {
            Utf8Text.encode("가").size shouldBe 3
            Utf8Text.encode("a😀").size shouldBe 5
        }
        Then("a site with a valid pair is accepted, length-prefixed by bytes, and differs from the '?' value") {
            val site = MerchantSiteId("a😀")
            OrderIntent(site, 12L.won()).canonicalV1() shouldBe "20:paycore.order-intent1:15:a😀2:123:KRW"
            OrderIntent(site, 12L.won()).fingerprint() shouldNotBe OrderIntent(MerchantSiteId("a?"), 12L.won()).fingerprint()
        }
    }
})
