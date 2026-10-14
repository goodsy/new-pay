package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.adapter.web.EntitlementInboxSettings
import com.paycore.payment.entitlement.adapter.web.EntitlementSignature
import com.paycore.payment.entitlement.adapter.web.WebJsonTree
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.IncomingSnapshot
import com.paycore.payment.entitlement.domain.JsonValue
import com.paycore.payment.entitlement.domain.Lifecycle
import com.paycore.payment.entitlement.domain.SiteReviewStatus
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
        val content = EntitlementContent.fromJson(payload, allowLeaseFields = true)
        listOf(content.canonical, content.contentHash, content.snapshotVersion) shouldBe
            listOf(g("contentCanonical"), g("contentHash"), "es1:${g("merchantId")}:7")
        val incoming = IncomingSnapshot(
            g("eventId"), g("merchantId"), 7, 1, content, (payload.obj("p").fields["contentHash"] as JsonValue.JString).value, EntitlementContent.leaseFromJson(payload),
        )
        incoming.lease!!.leaseUntil.toString() shouldBe "2026-10-09T02:01:05Z"
        content.sites.associate { it.siteId to (it.lifecycle to it.reviewStatus) }.values.toSet() shouldBe
            setOf(Lifecycle.ACTIVE to SiteReviewStatus.APPROVED, Lifecycle.ACTIVE to SiteReviewStatus.PENDING)
    }
})
