package com.paycore.simulator.merchant.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 가상 가맹점 수신기의 서명 헤더 판정(WEBHOOK-001 §21.0 검증자 문법, D-WH-003, 검토 m-8 — 판정 순서 문법 → 창 → 서명). HMAC 계산은 adapter가 맡고 여기서는 [matches] 함수로 주입한다
 * (도메인 순수성). 두 앱·Python 독립 생성 golden 대조는 `WebhookReceiverVerificationSpec`.
 */
class WebhookSignatureCheckSpec : BehaviorSpec({

    val now = Instant.ofEpochSecond(1_792_500_300)
    val window = Duration.ofSeconds(300)
    val sig = "a".repeat(64)
    val other = "b".repeat(64)

    /** 시험 대역: (kid, t, v1)이 (k1, now 근처 아무 t, sig)일 때만 일치. */
    fun matches(keyId: String, t: Long, v1: String): Boolean = keyId == "k1" && v1 == sig && t > 0

    fun check(header: String?, known: Set<String> = setOf("k1")) = WebhookSignatureCheck.verify(header, now, window, known, ::matches)

    Given("a well-formed header") {
        Then("one matching pair is accepted with the matching key id") {
            check("t=1792500300,kid=k1,v1=$sig") shouldBe WebhookVerification(WebhookVerdict.ACCEPTED, "k1")
        }
        Then("during rotation the second pair may be the one the receiver knows") {
            check("t=1792500300,kid=k0,v1=$other,kid=k1,v1=$sig") shouldBe WebhookVerification(WebhookVerdict.ACCEPTED, "k1")
        }
        Then("unknown parameters are ignored") {
            check("t=1792500300,kid=k1,v1=$sig,v0=whatever") shouldBe WebhookVerification(WebhookVerdict.ACCEPTED, "k1")
        }
        Then("the window is inclusive at ±300s and refused at ±301s (STALE before the signature is looked at)") {
            check("t=1792500000,kid=k1,v1=$sig").verdict shouldBe WebhookVerdict.ACCEPTED
            check("t=1792500600,kid=k1,v1=$sig").verdict shouldBe WebhookVerdict.ACCEPTED
            check("t=1792499999,kid=k1,v1=$sig").verdict shouldBe WebhookVerdict.STALE
            check("t=1792500601,kid=k1,v1=$sig").verdict shouldBe WebhookVerdict.STALE
            check("t=1792499999,kid=k1,v1=$other").verdict shouldBe WebhookVerdict.STALE
        }
        Then("no pair matches, or only unknown key ids are present → SIGNATURE") {
            check("t=1792500300,kid=k1,v1=$other") shouldBe WebhookVerification(WebhookVerdict.SIGNATURE, null)
            check("t=1792500300,kid=k1,v1=$sig", known = setOf("k2")) shouldBe WebhookVerification(WebhookVerdict.SIGNATURE, null)
        }
    }

    Given("a malformed header") {
        Then("missing, empty, no '=', two t, no t, non-digit or 11-digit t, a pair without kid or v1, three pairs, upper-case or short hex, spaces → MALFORMED") {
            listOf(
                null, "", "garbage", "t=1792500300,kid=k1,v1=$sig,garbage", "t=1,t=1792500300,kid=k1,v1=$sig", "kid=k1,v1=$sig", "t=x,kid=k1,v1=$sig",
                "t=17925003000,kid=k1,v1=$sig", "t=1792500300,v1=$sig", "t=1792500300,kid=k1", "t=1792500300,v1=$sig,kid=k1",
                "t=1792500300,kid=k1,v1=$sig,kid=k2,v1=$sig,kid=k3,v1=$sig", "t=1792500300,kid=k1,v1=${sig.uppercase()}", "t=1792500300,kid=k1,v1=abc",
                "t=1792500300, kid=k1, v1=$sig",
            ).forEach { h -> check(h) shouldBe WebhookVerification(WebhookVerdict.MALFORMED, null) }
        }
    }
})
