package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.ReturnUrlFixture
import com.paycore.payment.payment.domain.ReturnUrlParse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI

/**
 * AUTHN-001 명세 §8.2 교차 확인(adapter 계층 — domain은 java.net을 쓰지 않는다): domain 엄격 부분집합이 받아들인 모든 URL을
 * RFC 3986 파서(`java.net.URI`)로 다시 읽어도 scheme = https, host = 같은 host, port 없음, userinfo 없음, fragment 없음이다. 받아들인 문자열이
 * 다른 파서에서 다른 host로 해석되지 않음을 보인다(redirect 구성 결과 포함).
 */
class MerchantReturnUrlUriCrossCheckSpec : FunSpec({

    fun crossCheck(url: MerchantReturnUrl) {
        val uri = URI(url.value)
        withClue(url.value) {
            uri.scheme shouldBe "https"
            uri.host shouldBe url.host
            uri.port shouldBe -1
            uri.rawUserInfo shouldBe null
            uri.rawFragment shouldBe null
        }
    }

    test("every accepted §8.1 row has the same host, no port, no userinfo and no fragment under java.net.URI") {
        val rows = ReturnUrlFixture.accepted + listOf("https://${ReturnUrlFixture.longestHost}/", ReturnUrlFixture.urlOfLength(2048))
        rows.forEach { raw ->
            val parsed = MerchantReturnUrl.parse(raw)
            withClue(raw) { (parsed is ReturnUrlParse.Accepted) shouldBe true }
            crossCheck((parsed as ReturnUrlParse.Accepted).url)
        }
    }

    test("composed redirect URLs (appended, percent-encoded parameters) keep the host under java.net.URI") {
        ReturnUrlFixture.accepted.forEach { raw ->
            val url = (MerchantReturnUrl.parse(raw) as ReturnUrlParse.Accepted).url
            crossCheck(url.withQueryParameters(listOf("paymentId" to "pay_01", "code" to "@evil.example#x?y&z=1 \\ 한")))
        }
    }
})
