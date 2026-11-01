package com.paycore.master.merchant.adapter.batch

import com.paycore.master.merchant.domain.LimitValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * `demo-seed` Demo 값(DEMO-SEED-001 명세 §4.2·§4.11, D-DEMO-003, D-INST-CODE-001)을 구현 상수와 **독립 리터럴**로 확인한다(DB 없음).
 * 카드사 개통 값은 Simulator 가상 카드 카탈로그의 매입사(`SIMACQA`)·payment-api Demo Route 등록부 이름(`SIMVAN01`)과 같아야 PAYMENT 승인 시작 판정
 * (A1 — (CARD, 매입사) 카드사 개통)이 Simulator 인증 결과의 매입사와 맞는다. 앱 사이 파일 교차 확인은 harness `test_demo_seed_golden.py`가 한다.
 */
class DemoMerchantSeedValuesSpec : FunSpec({

    val v = DemoMerchantSeedValues.VALUES

    test("card route values are the Simulator catalog acquirer and the payment-api Demo route (D-INST-CODE-001 common format)") {
        v.acquirerCode.value shouldBe "SIMACQA"
        v.routeRef.value shouldBe "SIMVAN01"
        Regex("^[A-Z0-9]{1,32}$").matches(v.acquirerCode.value) shouldBe true
        Regex("^[A-Z0-9]{1,32}$").matches(v.routeRef.value) shouldBe true
    }

    test("Demo limits are D-DEMO-003 (per transaction 1,000,000 KRW, daily 5,000,000 KRW)") {
        v.currency.value shouldBe "KRW"
        v.perTransactionLimit shouldBe LimitValue.Limited(1_000_000)
        v.dailyLimit shouldBe LimitValue.Limited(5_000_000)
    }

    test("Demo markers (§4.11): fixed key space, system actor, reserved host, non-operational texts") {
        DemoMerchantSeedValues.KEY_PREFIX shouldBe "demo-seed-v1"
        DemoMerchantSeedValues.ACTOR.value shouldBe "system:demo-seed"
        v.siteHost shouldBe "demo-shop.paycore.test"
        v.registrationNumber.value shouldBe "0000000000"
        v.evidence.value shouldBe "DEMO-SEED-V1:NON-OPERATIONAL"
        v.feePolicyCode.value shouldBe "DEMO-FEE"
        v.settlementPolicyCode.value shouldBe "DEMO-SETTLEMENT"
        v.businessName.value shouldBe "DEMO 사업자 (운영값 아님)"
        v.merchantName.value shouldBe "DEMO 가맹점 (운영값 아님)"
    }
})
