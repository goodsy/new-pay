package com.paycore.payment.payment.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * ADMIN-PAYMENT-OPS-001 §4.19 ①(ADMIN-BOOT-001 F-4): 승인 대상 지문 golden `operator-cancel-target-oct1.golden.json`(harness `test_operator_cancel_target_golden.py`가 Python
 * `hashlib`으로 두 앱과 독립 생성 — internal-admin과 같은 파일)의 모든 벡터를 PAYMENT [OperatorCancelTarget.fingerprint]가 같은 값으로 만든다. ID는 Demo 값이다.
 */
class OperatorCancelTargetGoldenSpec : BehaviorSpec({

    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/operator-cancel-target-oct1.golden.json").inputStream)

    fun target(v: JsonNode): OperatorCancelTarget {
        val reference = v.get("purposeReference").asString()
        val purpose = when (val kind = v.get("purposeKind").asString()) {
            "INCIDENT_OR_RECON_CORRECTION" -> OperatorCancelPurpose.IncidentOrReconCorrection(reference)
            "MERCHANT_TERMINATED_OBLIGATION" -> OperatorCancelPurpose.MerchantTerminatedObligation(reference)
            else -> error("unknown purpose kind $kind")
        }
        return OperatorCancelTarget(
            paymentId = PaymentId(v.get("paymentId").asString()),
            money = Money(v.get("amountMinor").asLong(), CurrencyCode(v.get("currency").asString())),
            balanceRevision = v.get("balanceRevision").asLong(),
            approvalId = OperatorApprovalId(v.get("approvalId").asString()),
            requestedBy = OperatorId(v.get("requestedBy").asString()),
            approvedBy = OperatorId(v.get("approvedBy").asString()),
            purpose = purpose,
        )
    }

    Given("the oct1 golden generated independently by the harness") {
        Then("it covers the agreed vectors (UTF-8 multi-byte reference, both purposes, revision 0 and Long.MAX, USD, swapped participants)") {
            golden.get("domainTag").asString() shouldBe "paycore.operator-cancel-target"
            golden.get("vectors").items().map { it.get("name").asString() } shouldBe listOf(
                "ascii-basic", "hangul-and-supplementary-reference", "merchant-terminated-obligation", "revision-zero", "revision-long-max", "usd-partial",
                "requester-approver-swapped", "amount-one", "other-approval-id",
            )
        }
        golden.get("vectors").items().forEach { v ->
            Then("vector ${v.get("name").asString()}: PAYMENT computes the golden fingerprint exactly") {
                target(v).fingerprint() shouldBe v.get("fingerprint").asString()
            }
        }
    }
})

private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()
