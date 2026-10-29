package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * 결정 내용 hash `opd1:`(ADMIN-PAYMENT-OPS-001 §4.4.2 `OperatorDecisionContentHashSpec` — PAYMENT 내부 값, golden 대상 아님): 요소 14개를 명세 순서대로 `<UTF-8 바이트 수>:<값>`으로 이은 SHA-256.
 * 기대값은 명세 순서로 시험이 직접 만든 canonical에서 계산한다. correlationId는 요소가 아니고 나머지 요소는 하나만 바꿔도 hash가 바뀐다(요소 누락 변이를 잡는다).
 */
class OperatorDecisionContentHashSpec : BehaviorSpec({

    val base = OperatorCorrectionCancelCommand(
        paymentId = "pay_01JC0000000000000000000001", money = Money(7000, CurrencyCode("KRW")), requestedBy = OperatorId("adm_01JC00000000000000000000R1"),
        approvedBy = OperatorId("adm_01JC00000000000000000000A1"), approvalId = OperatorApprovalId("apr_01JC0000000000000000000001"), reason = "보정 사유 𠀀",
        purpose = OperatorCancelPurpose.IncidentOrReconCorrection("RECON-2026-0001"), approvedTargetFingerprint = "oct1:" + "a".repeat(64),
        approvedAt = Instant.parse("2026-10-27T01:02:03.456Z"), executeBefore = Instant.parse("2026-10-28T01:02:03.456Z"), correlationId = "corr-1",
    )

    Given("the specified canonical order") {
        Then("the hash equals SHA-256 of the length-prefixed elements in §4.4.2 order (UTF-8 byte lengths)") {
            val parts = listOf(
                "paycore.operator-decision-content", "1", "apr_01JC0000000000000000000001", "pay_01JC0000000000000000000001", "7000", "KRW", "adm_01JC00000000000000000000R1",
                "adm_01JC00000000000000000000A1", "INCIDENT_OR_RECON_CORRECTION", "RECON-2026-0001", "보정 사유 𠀀", "oct1:" + "a".repeat(64), "2026-10-27T01:02:03.456Z",
                "2026-10-28T01:02:03.456Z",
            )
            val canonical = parts.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            base.contentHash() shouldBe "opd1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        }
        Then("correlationId is not an element; every other element changes the hash") {
            base.copy(correlationId = "another").contentHash() shouldBe base.contentHash()
            listOf(
                base.copy(paymentId = "pay_01JC0000000000000000000002"), base.copy(money = Money(7001, CurrencyCode("KRW"))), base.copy(money = Money(7000, CurrencyCode("USD"))),
                base.copy(requestedBy = OperatorId("adm_01JC00000000000000000000R2")), base.copy(approvedBy = OperatorId("adm_01JC00000000000000000000A2")),
                base.copy(approvalId = OperatorApprovalId("apr_01JC0000000000000000000002")), base.copy(reason = "another"),
                base.copy(purpose = OperatorCancelPurpose.MerchantTerminatedObligation("RECON-2026-0001")),
                base.copy(purpose = OperatorCancelPurpose.IncidentOrReconCorrection("RECON-2026-0002")), base.copy(approvedTargetFingerprint = "oct1:" + "b".repeat(64)),
                base.copy(approvedAt = Instant.parse("2026-10-27T01:02:03.457Z")), base.copy(executeBefore = Instant.parse("2026-10-28T01:02:03.457Z")),
            ).forEach { it.contentHash() shouldNotBe base.contentHash() }
        }
    }
})
