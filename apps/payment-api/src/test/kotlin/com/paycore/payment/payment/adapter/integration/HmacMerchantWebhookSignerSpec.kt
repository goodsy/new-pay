package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.WebhookSigningKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 서명 헤더(WEBHOOK-001 §21.0, D-WH-003, WH-19·WH-20, 검토 m-8): `t=<초>,kid=<id>,v1=<64 소문자 hex>[,kid=…,v1=…]`, 공백 없음, t 먼저, 활성 키 순서대로 최대 2쌍.
 * HMAC-SHA256 키 = 가맹점에 보이는 비밀 문자열 전체(`whsec_…`)의 UTF-8 바이트, 서명 대상 = t의 ASCII 숫자 + "." + 본문 바이트. 기대값은 JDK `Mac`으로 시험 안에서 따로 계산한다
 * (두 앱·Python의 독립 golden은 S4 `webhook-signature-v1.golden.json`).
 */
class HmacMerchantWebhookSignerSpec : BehaviorSpec({

    val signer = HmacMerchantWebhookSigner()
    val body = """{"eventId":"whe_01JB0000000000000000000001","type":"payment.expired"}""".toByteArray()
    val k1 = WebhookSigningKey("whk_t_1", "whsec_dGVzdC1vbmx5LXdlYmhvb2stc2lnbmluZy1rZXktMQ".toByteArray())
    val k2 = WebhookSigningKey("whk_t_2", "whsec_dGVzdC1vbmx5LXdlYmhvb2stc2lnbmluZy1rZXktMg".toByteArray())

    fun expected(key: WebhookSigningKey, t: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.secret, "HmacSHA256")) }
        mac.update("$t.".toByteArray(Charsets.US_ASCII))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    Given("one active key") {
        Then("the header is t first, then one kid/v1 pair, lowercase hex, no spaces") {
            signer.signatureHeader(1_792_500_000, body, listOf(k1)) shouldBe "t=1792500000,kid=whk_t_1,v1=${expected(k1, 1_792_500_000)}"
        }
    }

    Given("two active keys during rotation") {
        Then("both pairs in the given order, each over the same t") {
            signer.signatureHeader(1_792_500_001, body, listOf(k1, k2)) shouldBe
                "t=1792500001,kid=whk_t_1,v1=${expected(k1, 1_792_500_001)},kid=whk_t_2,v1=${expected(k2, 1_792_500_001)}"
        }
        Then("a different t or one changed body byte changes the signature") {
            val a = signer.signatureHeader(1, body, listOf(k1))
            signer.signatureHeader(2, body, listOf(k1)).substringAfter("v1=") shouldBe expected(k1, 2)
            (a.substringAfter("v1=") == expected(k1, 2)) shouldBe false
        }
    }

    Given("invalid inputs") {
        Then("no key, more than two keys, a negative t or a malformed key id is refused") {
            shouldThrow<IllegalArgumentException> { signer.signatureHeader(1, body, emptyList()) }
            shouldThrow<IllegalArgumentException> { signer.signatureHeader(1, body, listOf(k1, k2, k1)) }
            shouldThrow<IllegalArgumentException> { signer.signatureHeader(-1, body, listOf(k1)) }
            shouldThrow<IllegalArgumentException> { signer.signatureHeader(1, body, listOf(WebhookSigningKey("bad id", k1.secret))) }
        }
        Then("a key never appears in its toString") {
            k1.toString().contains("whsec_") shouldBe false
        }
    }
})
