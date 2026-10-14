package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.RelayEnvelope
import com.paycore.master.merchant.domain.AccountState
import com.paycore.master.merchant.domain.ActivationState
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.AssignmentState
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.ContractWindow
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyState
import com.paycore.master.merchant.domain.EntitlementSnapshot
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PartyState
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteAssignmentId
import com.paycore.master.merchant.domain.SiteAssignmentRole
import com.paycore.master.merchant.domain.SiteAssignmentStatus
import com.paycore.master.merchant.domain.SiteConfirmationState
import com.paycore.master.merchant.domain.SiteReviewStatus
import com.paycore.master.merchant.domain.SiteState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * 계약 golden(MASTER-SNAPSHOT-001 S7): `contracts/entitlement-snapshot-v1.golden.json`은 두 앱 시험 자원에 **같은 내용**으로 있다(앱 간 직접 의존
 * 없음). MASTER가 같은 권리 상태로 만든 정규 내용·contentHash·전송 payload·봉투 본문·서명이 golden과 바이트 단위로 같아야 한다. PAYMENT 쪽
 * EntitlementContractGoldenSpec은 같은 파일을 해석·검증한다.
 */
class EntitlementContractGoldenSpec : FunSpec({

    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/entitlement-snapshot-v1.golden.json").inputStream)
    fun g(name: String): String = golden.get(name).stringValue()
    val t0 = Instant.parse("2026-10-09T02:00:00.000Z")
    val (s1, s2) = golden.get("siteIds").let { MerchantSiteId(it.get(0).stringValue()) to MerchantSiteId(it.get(1).stringValue()) }

    val snapshot = EntitlementSnapshot(
        merchantId = MerchantId(g("merchantId")),
        businessId = BusinessId(g("businessId")),
        revision = 7,
        issuedAt = Instant.parse(g("issuedAt")),
        leaseSeq = 0,
        leaseUntil = Instant.parse(g("leaseUntil")),
        party = PartyState(Lifecycle.ACTIVE, Lifecycle.ACTIVE, businessBlocked = false, merchantBlocked = false),
        // 입력 순서를 일부러 뒤섞는다 — 정규화가 정렬한다.
        sites = listOf(SiteState(s2, Lifecycle.ACTIVE, SiteReviewStatus.PENDING), SiteState(s1, Lifecycle.ACTIVE, SiteReviewStatus.APPROVED)),
        accounts = listOf(
            AccountState(
                mid = MerchantAccountId(g("mid")),
                lifecycle = Lifecycle.ACTIVE,
                blocked = false,
                issuingAgreementId = ContractServiceAgreementId(g("agreementId")),
                serviceKind = ServiceKind.CARD_PG_PAYMENT,
                assignments = listOf(AssignmentState(SiteAssignmentId(g("assignmentId")), s1, SiteAssignmentRole.PRIMARY, SiteAssignmentStatus.ACTIVE, t0, null)),
                contractWindows = listOf(ContractWindow(s2, t0, t0.plusSeconds(3600)), ContractWindow(s1, t0, null)),
                activations = listOf(ActivationState(PaymentMethod.CARD, ActivationStatus.ACTIVE)),
                siteConfirmations = listOf(SiteConfirmationState(s1, PaymentMethod.CARD)),
                currencies = listOf(CurrencyState(CurrencyCode("KRW"), 0, t0, Instant.parse("2026-12-31T15:00:00.000Z"))),
            ),
        ),
    )

    test("canonical content, content hash and wire payload equal the golden") {
        listOf(snapshot.canonicalContent, snapshot.contentHash, snapshot.wirePayload()) shouldBe
            listOf(g("contentCanonical"), g("contentHash"), g("wirePayloadCanonical"))
    }

    test("the envelope body and its HMAC signature header equal the golden byte for byte") {
        val body = EntitlementEnvelope.body(
            RelayEnvelope(g("eventId"), "EntitlementSnapshot", 1, "master-api", g("merchantId"), 7, Instant.parse(g("issuedAt")), 0, snapshot.wirePayload()),
        )
        String(body) shouldBe g("envelopeBody")
        EntitlementEnvelope.signatureHeader(g("signingKeyId"), Base64.getDecoder().decode(g("signingKeyBase64")), body) shouldBe g("signatureHeader")
    }
})
