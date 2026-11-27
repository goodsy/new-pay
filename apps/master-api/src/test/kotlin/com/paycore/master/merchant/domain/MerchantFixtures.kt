package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * MERCHANT-CODE-001 §8 MC-1b Named Fixture(Test Fixture Policy — 업무 조건이 이름에 드러나게, src/test 전용). "계약 승인 사실"은 Domain 입력(Boolean — Application이 잠금 뒤 읽는다)이라
 * 시나리오는 (가맹점, 승인 사실 유무) 쌍이다. 모든 가맹점은 [Merchant.register](Domain Factory)로 만든다 — 규칙 우회 없음.
 */
object MerchantFixtures {
    val REGISTRANT = ActorRef("admin:adm_01JCRG0000000000000000RG01")
    val APPROVER = ActorRef("admin:adm_01JCAP0000000000000000APR1")
    const val APPROVAL_ID = "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H"
    val changedAt: Instant = Instant.parse("2026-10-10T01:00:00.000Z")

    data class Scenario(val merchant: Merchant, val contractApproved: Boolean)

    fun registered(code: String = MerchantCodeFixtures.VALID, by: ActorRef = MasterFixture.operator): Merchant =
        MasterFixture.business().let { business ->
            // FLOW-3(§4B.8): 유효 사업자 계약 입력만 보강.
            Merchant.register(MasterFixture.merchantId(), business, BusinessContractFixtures.storedEffectiveFor(business.id, MasterFixture.t0), DisplayName("Demo Merchant"), MerchantCode(code), MasterFixture.t0, by)
        }

    /** 첫 계약 승인 **전**(승인 사실 행 없음 — 1회 변경 가능 상태). */
    fun beforeContractApproval(code: String = MerchantCodeFixtures.VALID) = Scenario(registered(code), contractApproved = false)

    /** 첫 계약 승인 **뒤**(그 가맹점 대상 계약에 승인 사실 행이 있음 — 코드 불변). */
    fun afterContractApproval(code: String = MerchantCodeFixtures.VALID) = Scenario(registered(code), contractApproved = true)

    /** 원 가맹점 등록자가 [actor]인 가맹점(D-MCODE-002 Q-MC-10 결정 고정 시험용 — 계약 승인 전). */
    fun registeredBy(actor: ActorRef, code: String = MerchantCodeFixtures.VALID) = Scenario(registered(code, actor), contractApproved = false)

    /** 1회 변경을 이미 마친 가맹점(은퇴 코드 [MerchantCodeFixtures.retiredOf]를 가진다). */
    fun alreadyChanged(code: String = MerchantCodeFixtures.VALID, newCode: String = "shopabcd99"): Merchant = registered(code).also {
        it.changeCodeWithEvidence(MerchantCode(newCode), MerchantCode(code), false, MerchantCodeChangeReason.TYPO_CORRECTION, APPROVAL_ID, changedAt, APPROVER)
    }
}
