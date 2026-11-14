package com.paycore.master.merchant.adapter.batch

import com.paycore.master.merchant.application.provided.DemoMerchantValues
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.RouteRef

/**
 * `demo-seed` 명령의 Demo 값(DEMO-SEED-001 명세 §4.2·§4.11). **모두 Demo — 운영값 아님.** 시험 Fixture가 아니라 DEMO 배포 전용 명령의 상수다
 * (Test Fixture Policy와 구별하려고 "Fixture" 이름을 쓰지 않는다).
 * - 한도: D-DEMO-003(KRW CSA 기본 1건 1,000,000원·1일 5,000,000원).
 * - 정책: 내용 없는 DEMO 버전(D-MS2-003 — 요율 숫자 없음, LIVE Gate 차단 조건).
 * - 등록번호 `0000000000`: 형식만 검사된다(실제 세무서 코드는 101 이상이라 실제 사업자와 겹치지 않는다).
 * - host: RFC 2606 예약 TLD `.test`. 카드사 개통: Simulator 가상 카드 카탈로그 전부의 매입사 `SIMACQA`, payment-api Demo Route `SIMVAN01`
 *   (기관 코드 공통 형식 `^[A-Z0-9]{1,32}$` — D-INST-CODE-001). 카탈로그·Route 등록부와의 일치는 harness `test_demo_seed_golden.py`가 파일로 교차 확인한다.
 */
object DemoMerchantSeedValues {
    /** 운영 명령의 고정 멱등키 공간(1회성 표지 — 명세 R-4). 단계 구성이 바뀌면 `v2`로(새 Demo DB 필요). */
    const val KEY_PREFIX = "demo-seed-v1"

    val ACTOR = ActorRef("system:demo-seed")

    const val SITE_HOST = "demo-shop.paycore.test"

    val VALUES = DemoMerchantValues(
        registrationNumber = BusinessRegistrationNumber("0000000000"),
        businessName = DisplayName("DEMO 사업자 (운영값 아님)"),
        merchantName = DisplayName("DEMO 가맹점 (운영값 아님)"),
        // MERCHANT-CODE-001 C-6: 새 DB seed 코드. 예약어 접두 `demo`의 유일한 예외(MerchantCodeReservedWords.DEMO_SEED_CODES).
        merchantCode = MerchantCode("demoshop01"),
        siteHost = SITE_HOST,
        evidence = EvidenceRef("DEMO-SEED-V1:NON-OPERATIONAL"),
        reason = DecisionReason("DEMO seed (not operational)"),
        currency = CurrencyCode("KRW"),
        perTransactionLimit = LimitValue.Limited(1_000_000),
        dailyLimit = LimitValue.Limited(5_000_000),
        feePolicyCode = PolicyCode("DEMO-FEE"),
        settlementPolicyCode = PolicyCode("DEMO-SETTLEMENT"),
        acquirerCode = AcquirerCode("SIMACQA"),
        routeRef = RouteRef("SIMVAN01"),
        institutionMerchantRef = InstitutionMerchantRef("SIMVAN-DEMO-0001"),
    )

    const val OUTPUT_HEADER =
        "PayCore DEMO merchant seed — NOT OPERATIONAL VALUES (D-DEMO-001). DEMO deployment only. Payments possible from effectiveFrom."
}
