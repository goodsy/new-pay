package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 취소 멱등 fingerprint `x1:`(CANCEL-001 §4.8 CC-06 — confirm `c1:`과 같은 길이 접두 canonical): 범위 = paymentId·merchantCancelId·amount·currency. 기대 canonical 문자열은 여기서 손으로 적는다
 * (구현과 독립). 전송 필드(키·correlationId)와 인증 범위(MID·환경 — 멱등 범위가 따로 가진다)는 fingerprint에 없다. **v1 동결**.
 */
class CancelFingerprintsSpec : BehaviorSpec({

    fun command(amount: Long = 3_000, merchantCancelId: String = "mc-0001", paymentId: String = "pay_demo0001", currency: String = "KRW", key: String = "k1", correlation: String = "c1") =
        CancelPaymentCommand(Environment.TEST, MerchantAccountId("mid-demo-0001"), IdempotencyKey(key), paymentId, MerchantCancelId(merchantCancelId), Money(amount, CurrencyCode(currency)), correlation)

    Given("the x1 canonical") {
        Then("is the length-prefixed domain, version and four named fields, hashed with SHA-256") {
            val canonical = "30:paycore.cancel-payment-request" + "1:1" + "9:paymentId" + "12:pay_demo0001" + "16:merchantCancelId" + "7:mc-0001" + "6:amount" + "4:3000" + "8:currency" + "3:KRW"
            val expected = "x1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
            CancelFingerprints.current(command()) shouldBe RequestFingerprint(expected)
        }
        Then("transport values and the merchant scope do not change it; each business field does") {
            val base = CancelFingerprints.current(command())
            CancelFingerprints.current(command(key = "k2", correlation = "c2")) shouldBe base
            CancelFingerprints.current(command().copy(merchantAccountId = MerchantAccountId("mid-other-0001"))) shouldBe base
            listOf(command(amount = 3_001), command(merchantCancelId = "mc-0002"), command(paymentId = "pay_demo0002"), command(currency = "USD")).forEach {
                CancelFingerprints.current(it) shouldNotBe base
            }
            CancelFingerprints.isComparable(base) shouldBe true
            CancelFingerprints.isComparable(RequestFingerprint("c1:" + "0".repeat(64))) shouldBe false
        }
    }
})
