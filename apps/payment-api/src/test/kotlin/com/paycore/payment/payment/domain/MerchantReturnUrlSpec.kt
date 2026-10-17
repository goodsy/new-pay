package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.ReturnUrlFixture.SITE_HOST
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 가맹점 반환 URL(D-AUTHN-002, AUTHN-001 명세 §8): 엄격 부분집합 문법(정규화 없이 거절) + host 문자열 완전 일치 + redirect 구성 뒤 재검증.
 * 기대값은 명세 표에서 독립적으로 적었다(구현에서 계산하지 않는다).
 */
class MerchantReturnUrlSpec : BehaviorSpec({

    fun accepted(raw: String): MerchantReturnUrl {
        val parsed = MerchantReturnUrl.parse(raw)
        withClue(raw) { parsed.shouldBeInstanceOf<ReturnUrlParse.Accepted>() }
        return (parsed as ReturnUrlParse.Accepted).url
    }

    Given("the §8.1 accepted rows") {
        Then("each parses unchanged (no normalization) and exposes its host") {
            ReturnUrlFixture.accepted.forEach { raw ->
                val url = accepted(raw)
                url.value shouldBe raw
                url.host shouldBe raw.removePrefix("https://").takeWhile { it != '/' && it != '?' }
            }
        }
        Then("the 63-character label / 253-character host and the 2048-character URL boundaries are accepted") {
            ReturnUrlFixture.longestHost.length shouldBe 253
            accepted("https://${ReturnUrlFixture.longestHost}/").host shouldBe ReturnUrlFixture.longestHost
            accepted(ReturnUrlFixture.urlOfLength(2048)).value.length shouldBe 2048
        }
    }

    Given("the §8.1 rejected rows") {
        Then("each is rejected with its reason (one input per row)") {
            ReturnUrlFixture.rejected.forEach { (raw, reason) ->
                withClue(raw) { MerchantReturnUrl.parse(raw) shouldBe ReturnUrlParse.Rejected(reason) }
            }
        }
        Then("a 254-character host and a 2049-character URL are rejected") {
            MerchantReturnUrl.parse("https://${ReturnUrlFixture.longestHost}c/") shouldBe ReturnUrlParse.Rejected(ReturnUrlRejection.HOST_SYNTAX)
            MerchantReturnUrl.parse(ReturnUrlFixture.urlOfLength(2049)) shouldBe ReturnUrlParse.Rejected(ReturnUrlRejection.TOO_LONG)
        }
    }

    Given("host matching against the Site WEB host (§8.2)") {
        Then("only the exact same string matches; subdomains, suffix tricks and look-alikes are different hosts") {
            accepted("https://$SITE_HOST/ok").hasHost(SITE_HOST) shouldBe true
            listOf(
                "https://www.$SITE_HOST/ok",
                "https://$SITE_HOST.evil.example/ok",
                "https://evil-$SITE_HOST/ok",
                "https://shop.example.testx/ok",
            ).forEach { raw -> withClue(raw) { accepted(raw).hasHost(SITE_HOST) shouldBe false } }
        }
    }

    Given("a redirect built from a stored URL (§8.3)") {
        Then("parameters are appended with '?' when there is no query, percent-encoded outside RFC 3986 unreserved") {
            val built = accepted("https://$SITE_HOST/pay/ok").withQueryParameters(listOf("paymentId" to "pay_01", "code" to "a b/한~-._"))
            built.value shouldBe "https://$SITE_HOST/pay/ok?paymentId=pay_01&code=a%20b%2F%ED%95%9C~-._"
            built.host shouldBe SITE_HOST
        }
        Then("existing query is preserved and parameters are appended with '&'") {
            accepted("https://$SITE_HOST/pay/ok?x=1").withQueryParameters(listOf("paymentId" to "pay_01")).value shouldBe
                "https://$SITE_HOST/pay/ok?x=1&paymentId=pay_01"
            accepted("https://$SITE_HOST").withQueryParameters(listOf("code" to "USER_CANCELLED")).value shouldBe
                "https://$SITE_HOST?code=USER_CANCELLED"
        }
        Then("values that would change the host or add a fragment are encoded, so the host stays the same") {
            val built = accepted("https://$SITE_HOST/ok").withQueryParameters(listOf("code" to "@evil.example#x?y&z=1"))
            built.value shouldBe "https://$SITE_HOST/ok?code=%40evil.example%23x%3Fy%26z%3D1"
            built.hasHost(SITE_HOST) shouldBe true
        }
        Then("a stored URL at the 2048 limit still yields a redirect (the limit is the stored column, not the composed Location)") {
            val built = accepted(ReturnUrlFixture.urlOfLength(2048)).withQueryParameters(listOf("paymentId" to "pay_01"))
            built.value.length shouldBe 2048 + "?paymentId=pay_01".length
        }
        Then("parameter names must be RFC 3986 unreserved (names are code constants, not input)") {
            shouldThrow<IllegalArgumentException> { accepted("https://$SITE_HOST/ok").withQueryParameters(listOf("a b" to "1")) }
            shouldThrow<IllegalArgumentException> { accepted("https://$SITE_HOST/ok").withQueryParameters(listOf("" to "1")) }
        }
    }

    Given("diagnostics") {
        Then("toString shows only the host") {
            accepted("https://$SITE_HOST/pay/ok?secret=1").toString() shouldBe "MerchantReturnUrl(host=$SITE_HOST)"
        }
    }
})
