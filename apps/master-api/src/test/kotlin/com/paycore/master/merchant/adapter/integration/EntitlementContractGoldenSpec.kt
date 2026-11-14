package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.RelayEnvelope
import com.paycore.master.merchant.domain.AccountState
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActivationState
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.AssignmentState
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.CardRouteState
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.ContractWindow
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyState
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.EntitlementSnapshot
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.LimitPolicyState
import com.paycore.master.merchant.domain.LimitScopeKind
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PartyState
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyAssignmentState
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteAssignmentId
import com.paycore.master.merchant.domain.SiteAssignmentRole
import com.paycore.master.merchant.domain.SiteAssignmentStatus
import com.paycore.master.merchant.domain.SiteConfirmationState
import com.paycore.master.merchant.domain.SiteReviewStatus
import com.paycore.master.merchant.domain.SiteChannel
import com.paycore.master.merchant.domain.SiteState
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * 계약 golden(MASTER-SNAPSHOT-001 S7 → MASTER-SNAPSHOT-002 schemaVersion 2 → AUTHN-001 schemaVersion 3): `contracts/entitlement-snapshot-v3.golden.json`은 두 앱 시험 자원에
 * **같은 내용**으로 있고 harness 생성 절차(`harness/tests/test_entitlement_golden.py`)가 두 앱 코드와 독립적으로 만든다. MASTER가 같은 상태로 만든
 * 정규 내용·contentHash·전송 payload·봉투 본문·서명이 golden과 바이트 단위로 같아야 한다. MASTER는 v2만 조립하므로 MASTER 쪽 v1 golden은 이 파일로
 * 대체했고, AUTHN-001부터 v3만 조립하므로 v2 golden도 이 v3 파일로 대체했다(v1·v2는 PAYMENT가 계속 해석 — PAYMENT 시험 자원에 남는다).
 * v3 = v2 + sites[].channel(WEB host / APP는 type만 — 앱 식별값은 싣지 않는다, D-AUTHN-002). 한도·정책·host 값은 Demo(운영값 아님).
 */
class EntitlementContractGoldenSpec : FunSpec({

    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/entitlement-snapshot-v3.golden.json").inputStream)
    fun g(name: String): String = golden.get(name).stringValue()
    val t0 = Instant.parse("2026-10-09T02:00:00.000Z")
    val (s1, s2) = golden.get("siteIds").let { MerchantSiteId(it.get(0).stringValue()) to MerchantSiteId(it.get(1).stringValue()) }
    val csa = ContractServiceAgreementId(g("agreementId"))
    val krw = CurrencyCode("KRW")

    val snapshot = EntitlementSnapshot(
        merchantId = MerchantId(g("merchantId")),
        businessId = BusinessId(g("businessId")),
        revision = 7,
        issuedAt = Instant.parse(g("issuedAt")),
        leaseSeq = 0,
        leaseUntil = Instant.parse(g("leaseUntil")),
        party = PartyState(Lifecycle.ACTIVE, Lifecycle.ACTIVE, businessBlocked = false, merchantBlocked = false),
        // 입력 순서를 일부러 뒤섞는다 — 정규화가 정렬한다.
        sites = listOf(
            SiteState(s2, Lifecycle.ACTIVE, SiteReviewStatus.PENDING, SiteChannel(ChannelType.APP, "com.example.shop_app")),
            SiteState(s1, Lifecycle.ACTIVE, SiteReviewStatus.APPROVED, SiteChannel(ChannelType.WEB, "shop.example.test")),
        ),
        accounts = listOf(
            AccountState(
                mid = MerchantAccountId(g("mid")),
                lifecycle = Lifecycle.ACTIVE,
                blocked = false,
                issuingAgreementId = csa,
                serviceKind = ServiceKind.CARD_PG_PAYMENT,
                assignments = listOf(AssignmentState(SiteAssignmentId(g("assignmentId")), s1, SiteAssignmentRole.PRIMARY, SiteAssignmentStatus.ACTIVE, t0, null)),
                contractWindows = listOf(ContractWindow(s2, t0, t0.plusSeconds(3600)), ContractWindow(s1, t0, null)),
                activations = listOf(ActivationState(PaymentMethod.CARD, ActivationStatus.ACTIVE)),
                siteConfirmations = listOf(SiteConfirmationState(s1, PaymentMethod.CARD)),
                currencies = listOf(CurrencyState(krw, 0, t0, Instant.parse("2026-12-31T15:00:00.000Z"))),
                limitPolicies = listOf(
                    LimitPolicyState(
                        TransactionLimitPolicyId(g("limitOverrideId")), LimitScopeKind.MID_OVERRIDE, null, krw, LimitValue.Limited(300_000),
                        LimitValue.Unlimited, Instant.parse("2026-10-10T15:00:00.000Z"), null,
                    ),
                    LimitPolicyState(
                        TransactionLimitPolicyId(g("limitDefaultId")), LimitScopeKind.CSA_DEFAULT, csa, krw, LimitValue.Limited(1_000_000),
                        LimitValue.Limited(5_000_000), Instant.parse("2026-10-09T15:00:00.000Z"), null,
                    ),
                ),
                policyAssignments = listOf(
                    PolicyAssignmentState(
                        PolicyAssignmentId(g("settlementAssignmentId")), PolicyKind.SETTLEMENT_POLICY, csa, krw, PolicyVersionId(g("settlementVersionId")),
                        PolicyCode("STL-DEMO"), 1, PolicyContentStatus.DEMO, t0, null,
                    ),
                    PolicyAssignmentState(
                        PolicyAssignmentId(g("feeAssignmentId")), PolicyKind.FEE_SCHEDULE, csa, krw, PolicyVersionId(g("feeVersionId")),
                        PolicyCode("FEE-DEMO"), 1, PolicyContentStatus.DEMO, t0, null,
                    ),
                ),
                cardRoutes = listOf(
                    CardRouteState(
                        CardRouteActivationId(g("cardRouteId")), PaymentMethod.CARD, AcquirerCode("SIMCARD01"), RouteRef("SIMVAN01"),
                        InstitutionMerchantRef("SIMMERCH0001"), ActivationStatus.ACTIVE,
                    ),
                ),
            ),
        ),
    )

    test("canonical content, content hash and wire payload equal the v3 golden") {
        listOf(snapshot.canonicalContent, snapshot.contentHash, snapshot.wirePayload()) shouldBe
            listOf(g("contentCanonical"), g("contentHash"), g("wirePayloadCanonical"))
    }

    test("the v3 envelope body and its HMAC signature header equal the golden byte for byte") {
        ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION shouldBe 3
        val body = EntitlementEnvelope.body(
            RelayEnvelope(
                g("eventId"), "EntitlementSnapshot", ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION, "master-api", g("merchantId"), 7, Instant.parse(g("issuedAt")), 0,
                snapshot.wirePayload(),
            ),
        )
        String(body) shouldBe g("envelopeBody")
        EntitlementEnvelope.signatureHeader(g("signingKeyId"), Base64.getDecoder().decode(g("signingKeyBase64")), body) shouldBe g("signatureHeader")
    }

    // MERCHANT-OPS-002 §8.3 B3-2-4(골든 확장, review MINOR-1): AT_EXECUTION 확정 시각(lockedNow — KST 자정 아님·ms 있음)이 통화 구간 `from`인 Snapshot.
    // 같은 v3 계약·같은 ID에 통화 구간(열린 구간)·revision·발행 시각만 다르다. harness 생성 절차가 두 앱과 독립적으로 만든다(값은 Demo).
    val atExecution = JsonMapper.builder().build()
        .readTree(ClassPathResource("contracts/entitlement-snapshot-v3-currency-at-execution.golden.json").inputStream)
    fun ga(name: String): String = atExecution.get(name).stringValue()

    test("B3-2: a currency segment starting at an AT_EXECUTION lockedNow (non-midnight, milliseconds) equals its v3 golden byte for byte") {
        val lockedNow = Instant.parse("2026-10-09T02:00:07.123Z")
        listOf("merchantId", "businessId", "mid", "agreementId").forEach { ga(it) shouldBe g(it) }
        val variant = snapshot.copy(
            revision = 8,
            issuedAt = Instant.parse(ga("issuedAt")),
            leaseUntil = Instant.parse(ga("leaseUntil")),
            accounts = listOf(snapshot.accounts.single().copy(currencies = listOf(CurrencyState(krw, 0, lockedNow, null)))),
        )
        listOf(variant.canonicalContent, variant.contentHash, variant.wirePayload()) shouldBe
            listOf(ga("contentCanonical"), ga("contentHash"), ga("wirePayloadCanonical"))
        variant.canonicalContent.contains("\"currencies\":[{\"currency\":\"KRW\",\"from\":\"2026-10-09T02:00:07.123Z\",\"minorUnit\":0,\"to\":null}]") shouldBe true
        val body = EntitlementEnvelope.body(
            RelayEnvelope(ga("eventId"), "EntitlementSnapshot", ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION, "master-api", ga("merchantId"), 8, lockedNow, 0, variant.wirePayload()),
        )
        String(body) shouldBe ga("envelopeBody")
        EntitlementEnvelope.signatureHeader(ga("signingKeyId"), Base64.getDecoder().decode(ga("signingKeyBase64")), body) shouldBe ga("signatureHeader")
    }

    test("the APP channel identifier is not carried — only its type (minimum identifiers, AUTHN-001 §9.1)") {
        snapshot.canonicalContent.contains("com.example.shop_app") shouldBe false
        snapshot.canonicalContent.contains("\"channel\":{\"type\":\"APP\"}") shouldBe true
    }

    test("the institution merchant reference is masked in toString (internal identifier grade, D-MS2-005)") {
        val route = snapshot.accounts.single().cardRoutes.single()
        route.toString().contains("SIMMERCH0001") shouldBe false
        InstitutionMerchantRef("SIMMERCH0001").toString() shouldBe "InstitutionMerchantRef(****)"
    }
})
