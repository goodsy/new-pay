package com.paycore.master.merchant.domain

import java.time.Duration
import java.time.Instant

/**
 * 권리 Snapshot 관련 Named Fixture(Test Fixture Policy: Domain Factory → Named Fixture, src/test 전용). 모든 정상 상태는 공식 경로
 * (register·issue·approve·confirm·enable)로 만든다. 근거 참조·사유는 시험 값이다(실제 기관 회신 아님).
 */
object EntitlementFixture {
    val t0: Instant = MasterFixture.t0
    val operator = MasterFixture.operator
    val evidence = EvidenceRef("van-reply:2026-0001")
    val newEvidence = EvidenceRef("van-reply:2026-0002")
    val reason = DecisionReason("merchant request")
    val krw = CurrencyUnit(CurrencyCode("KRW"), 0)
    val usd = CurrencyUnit(CurrencyCode("USD"), 2)

    /** Demo lease 길이(시험 값 — 운영값 아님, 명세 §8). */
    val demoLease: Duration = Duration.ofSeconds(60)

    fun confirmationId() = SiteActivationConfirmationId("sac_${MasterFixture.ulid()}")
    fun enablementId() = CurrencyEnablementId("cur_${MasterFixture.ulid()}")

    /** 최초 Default 발급까지 끝난 구조 + CARD 개통 등록(UNDER_REVIEW). */
    data class Registered(val issued: MasterFixture.Issued, val activation: PaymentMethodActivation) {
        val account: MerchantAccount get() = issued.issuance.account
        val primary: SiteAssignment get() = issued.issuance.primaryAssignment
    }

    fun registered(at: Instant = t0): Registered {
        val issued = MasterFixture.issued(at)
        val (activation, _) = PaymentMethodActivation.register(MasterFixture.activationId(), issued.issuance.account, PaymentMethod.CARD, at, operator)
        return Registered(issued, activation)
    }

    /** 개통 승인(ACTIVE) + PRIMARY Site 적용 확인까지 끝난 구조. */
    data class Active(val registered: Registered, val confirmation: SiteActivationConfirmation) {
        val activation: PaymentMethodActivation get() = registered.activation
        val account: MerchantAccount get() = registered.account
    }

    fun active(at: Instant = t0): Active {
        val r = registered(at)
        val result = ActivationApproval.approve(r.activation, r.account, listOf(r.primary), emptyList(), confirmationId(), evidence, at, operator)
        return Active(r, checkNotNull(result.primaryConfirmation))
    }

    /** 조립 입력(Site 심사 APPROVED, KRW 활성화)을 공식 경로 결과로 만든다. */
    fun source(active: Active = active(), reviews: Map<MerchantSiteId, SiteReviewStatus>? = null, at: Instant = t0): EntitlementSource {
        val issued = active.registered.issued
        val currency = CurrencyEnablement.enable(enablementId(), active.account, krw, EffectivePeriod(at), emptyList(), evidence, at, operator)
        return EntitlementSource(
            business = issued.business,
            merchant = issued.merchant,
            sites = listOf(issued.site),
            siteReviews = reviews ?: mapOf(issued.site.id to SiteReviewStatus.APPROVED),
            accounts = listOf(
                AccountSource(
                    account = active.account,
                    issuingContract = issued.contract,
                    assignments = listOf(active.registered.primary),
                    activations = listOf(active.activation),
                    confirmations = listOf(active.confirmation),
                    currencies = listOf(currency),
                ),
            ),
        )
    }
}
