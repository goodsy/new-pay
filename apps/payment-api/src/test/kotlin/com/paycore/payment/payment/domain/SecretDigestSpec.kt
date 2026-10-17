package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * 원문 비밀(결제창 토큰·결제창 세션 쿠키·인증 state·결과 참조)의 저장형(D-AUTHN-001 "DB에는 hash만", AUTHN-001 명세 §7.1·AN-22).
 * 저장형 = 종류 접두 + SHA-256 hex(소문자 64자). 기대 hex는 표준 SHA-256 시험 벡터에서 독립적으로 가져왔다.
 */
class SecretDigestSpec : BehaviorSpec({

    Given("a raw secret") {
        Then("the stored form is the kind prefix followed by lower-case SHA-256 hex of the UTF-8 bytes") {
            // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad (FIPS 180-2 B.1)
            val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            SecretDigest.of(SecretKind.CHECKOUT_TOKEN, "abc").value shouldBe "cth1:$hex"
            SecretDigest.of(SecretKind.CHECKOUT_SESSION, "abc").value shouldBe "cks1:$hex"
            SecretDigest.of(SecretKind.AUTHENTICATION_STATE, "abc").value shouldBe "std1:$hex"
            SecretDigest.of(SecretKind.RESULT_REFERENCE, "abc").value shouldBe "rrd1:$hex"
        }
        Then("an empty secret is refused") {
            shouldThrow<IllegalArgumentException> { SecretDigest.of(SecretKind.CHECKOUT_TOKEN, "") }
        }
        Then("the same raw value of a different kind is a different digest (no cross-kind confusion)") {
            SecretDigest.of(SecretKind.CHECKOUT_TOKEN, "abc") shouldNotBe SecretDigest.of(SecretKind.CHECKOUT_SESSION, "abc")
        }
    }

    Given("a stored digest read back from the database") {
        Then("parse accepts exactly a known prefix + 64 lower-case hex characters and equals the computed digest") {
            val computed = SecretDigest.of(SecretKind.RESULT_REFERENCE, "demo-result-ref")
            SecretDigest.parse(computed.value) shouldBe computed
            SecretDigest.parse(computed.value).kind shouldBe SecretKind.RESULT_REFERENCE
        }
        Then("parse refuses unknown prefixes, upper-case hex, wrong lengths and raw values") {
            val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            listOf("xyz1:$hex", "cth1:${hex.uppercase()}", "cth1:${hex.dropLast(1)}", "cth1:${hex}0", "ckt_rawtoken", "", "cth1$hex").forEach { bad ->
                shouldThrow<IllegalArgumentException> { SecretDigest.parse(bad) }
            }
        }
    }

    Given("diagnostics") {
        Then("toString never prints the digest value") {
            val d = SecretDigest.of(SecretKind.CHECKOUT_TOKEN, "abc")
            d.toString() shouldBe "SecretDigest(cth1:…)"
            d.toString() shouldNotContain "ba7816bf"
        }
    }
})
