package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.adapter.web.EntitlementInboxSettings
import com.paycore.payment.entitlement.adapter.web.EntitlementSignature
import com.paycore.payment.entitlement.adapter.web.WebJsonTree
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.IncomingSnapshot
import com.paycore.payment.entitlement.domain.JsonValue
import com.paycore.payment.entitlement.domain.Lifecycle
import com.paycore.payment.entitlement.domain.SiteChannelEntry
import com.paycore.payment.entitlement.domain.SiteReviewStatus
import com.paycore.payment.entitlement.domain.SnapshotContractViolation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper

/**
 * 계약 golden(MASTER-SNAPSHOT-001 S7) — PAYMENT 쪽. MASTER 시험과 같은 파일을 쓴다. MASTER가 만든 봉투 본문을 PAYMENT가 서명 검증하고, 내용을
 * 해석해 다시 계산한 정규 내용·contentHash가 golden(=MASTER 결과)과 같아야 한다.
 */
class EntitlementContractGoldenSpec : FunSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/entitlement-snapshot-v1.golden.json").inputStream)
    fun g(name: String): String = golden.get(name).stringValue()
    val body = g("envelopeBody").toByteArray()

    test("the golden envelope verifies with the signing key (and fails with one byte changed)") {
        val settings = EntitlementInboxSettings(listOf(EntitlementInboxSettings.SigningKey(g("signingKeyId"), g("signingKeyBase64"))))
        EntitlementSignature.verify(settings, g("signatureHeader"), body) shouldBe true
        EntitlementSignature.verify(settings, g("signatureHeader"), body + ' '.code.toByte()) shouldBe false
    }

    test("PAYMENT re-derives the golden canonical content and content hash from the golden payload") {
        val payload = WebJsonTree.toJsonValue(mapper.readTree(body).get("payload"))
        val content = EntitlementContent.fromJson(payload, 1, allowLeaseFields = true)
        listOf(content.canonical, content.contentHash, content.snapshotVersion) shouldBe
            listOf(g("contentCanonical"), g("contentHash"), "es1:${g("merchantId")}:7")
        val incoming = IncomingSnapshot(
            g("eventId"), g("merchantId"), 7, 1, content, (payload.obj("p").fields["contentHash"] as JsonValue.JString).value, EntitlementContent.leaseFromJson(payload),
        )
        incoming.lease!!.leaseUntil.toString() shouldBe "2026-10-09T02:01:05Z"
        content.sites.associate { it.siteId to (it.lifecycle to it.reviewStatus) }.values.toSet() shouldBe
            setOf(Lifecycle.ACTIVE to SiteReviewStatus.APPROVED, Lifecycle.ACTIVE to SiteReviewStatus.PENDING)
    }

    // MASTER-SNAPSHOT-002: schemaVersion 2 golden(MASTER 시험 자원과 같은 파일, harness 독립 생성). 값은 Demo.
    val v2 = mapper.readTree(ClassPathResource("contracts/entitlement-snapshot-v2.golden.json").inputStream)
    fun g2(name: String): String = v2.get(name).stringValue()
    val body2 = g2("envelopeBody").toByteArray()

    test("the v2 golden envelope verifies, declares schemaVersion 2, and PAYMENT re-derives its canonical content and hash including the terms") {
        val settings = EntitlementInboxSettings(listOf(EntitlementInboxSettings.SigningKey(g2("signingKeyId"), g2("signingKeyBase64"))))
        EntitlementSignature.verify(settings, g2("signatureHeader"), body2) shouldBe true
        val envelope = mapper.readTree(body2)
        envelope.get("schemaVersion").intValue() shouldBe 2
        val payload = WebJsonTree.toJsonValue(envelope.get("payload"))
        val content = EntitlementContent.fromJson(payload, 2, allowLeaseFields = true)
        listOf(content.canonical, content.contentHash, content.schemaVersion) shouldBe listOf(g2("contentCanonical"), g2("contentHash"), 2)
        val terms = content.accounts.single().terms!!
        listOf(
            terms.limitPolicies.map { it.policyId }.toSet(), terms.policyAssignments.map { it.assignmentId }.toSet(),
            terms.cardRoutes.single().acquirerCode, terms.cardRoutes.single().routeRef,
        ) shouldBe listOf(
            setOf(g2("limitDefaultId"), g2("limitOverrideId")), setOf(g2("feeAssignmentId"), g2("settlementAssignmentId")), "SIMCARD01", "SIM-VAN-01",
        )
        // v1 해석기로는 v2 내용을 받지 않는다(모르는 필드 거절 — 배포 순서 §5.4의 근거).
        runCatching { EntitlementContent.fromJson(payload, 1, allowLeaseFields = true) }.exceptionOrNull()!!::class shouldBe SnapshotContractViolation::class
    }

    // AUTHN-001: schemaVersion 3 golden(MASTER 시험 자원과 같은 파일, harness 독립 생성) — v2 + sites[].channel. 값은 Demo.
    val v3 = mapper.readTree(ClassPathResource("contracts/entitlement-snapshot-v3.golden.json").inputStream)
    fun g3(name: String): String = v3.get(name).stringValue()
    val body3 = g3("envelopeBody").toByteArray()

    test("the v3 golden envelope verifies, declares schemaVersion 3, and PAYMENT re-derives its canonical content and hash including the site channels") {
        val settings = EntitlementInboxSettings(listOf(EntitlementInboxSettings.SigningKey(g3("signingKeyId"), g3("signingKeyBase64"))))
        EntitlementSignature.verify(settings, g3("signatureHeader"), body3) shouldBe true
        val envelope = mapper.readTree(body3)
        envelope.get("schemaVersion").intValue() shouldBe 3
        val payload = WebJsonTree.toJsonValue(envelope.get("payload"))
        val content = EntitlementContent.fromJson(payload, 3, allowLeaseFields = true)
        listOf(content.canonical, content.contentHash, content.schemaVersion) shouldBe listOf(g3("contentCanonical"), g3("contentHash"), 3)
        val (s1, s2) = v3.get("siteIds").let { it.get(0).stringValue() to it.get(1).stringValue() }
        content.sites.associate { it.siteId to it.channel } shouldBe mapOf(s1 to SiteChannelEntry.Web("shop.example.test"), s2 to SiteChannelEntry.App)
        // v2 해석기로는 v3 내용을 받지 않는다(모르는 필드 거절 — 배포 순서: PAYMENT v3 해석 먼저, MS2 §5.4).
        runCatching { EntitlementContent.fromJson(payload, 2, allowLeaseFields = true) }.exceptionOrNull()!!::class shouldBe SnapshotContractViolation::class
    }
})
