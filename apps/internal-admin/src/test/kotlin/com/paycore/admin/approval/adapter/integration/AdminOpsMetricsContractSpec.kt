package com.paycore.admin.approval.adapter.integration

import com.paycore.admin.paymentops.adapter.integration.MicrometerPaymentOpsMetrics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * ADMIN 운영 지표 이름·label 묶음(ADMIN-PAYMENT-OPS-001 §6·§4.16·§4.17, S9): 소유 서비스 승인·회복·대사·PAYMENT 호출·근거 조회 지표가 명세 목록과 정확히 같은 이름·label 키로
 * 등록된다(값은 고정 분류만 — paymentId·approvalId 없음). 이름을 바꾸면 경보 설정이 조용히 끊기므로 계약으로 고정한다.
 */
class AdminOpsMetricsContractSpec : FunSpec({

    test("every operations metric has its specified name and label keys") {
        val registry = SimpleMeterRegistry()
        MicrometerOwnerOpsMetrics(registry).apply {
            integrityAlert("APPROVAL_CONFLICT")
            resend("EXECUTE_STARTED")
            resendHalted("REQUESTER_NOT_ELIGIBLE")
            outcomeDivergence()
            unresolved("EXECUTION_UNKNOWN", 1)
            unknownOldestAge(5)
            reconRun("OK")
            reconFinding("ORPHAN_DECISION")
            reconFindingDuplicate()
            reconLag(0)
            openFindings(mapOf("ORPHAN_DECISION" to 1L))
            justificationRateLimited()
        }
        MicrometerPaymentOpsMetrics(registry).apply {
            call("EXECUTE", "STARTED")
            lookupRateLimited()
        }
        MicrometerApprovalMetrics(registry).apply {
            decision("OWNER_APPROVE", "OPERATOR_CORRECTION_CANCEL", "EXECUTED")
            retryLater("OWNER_APPROVE")
            integrityViolation("command")
        }
        registry.meters.groupBy({ it.id.name }, { it.id.tags.map { t -> t.key }.sorted() }).mapValues { (_, keys) -> keys.toSet() } shouldBe mapOf(
            "paycore.admin.ops.integrity_alert" to setOf(listOf("code")),
            "paycore.admin.ops.resend" to setOf(listOf("result")),
            "paycore.admin.ops.resend_halted" to setOf(listOf("reason")),
            "paycore.admin.ops.outcome_divergence" to setOf(emptyList()),
            "paycore.admin.ops.unresolved" to setOf(listOf("status")),
            "paycore.admin.ops.unknown.oldest_age_seconds" to setOf(emptyList()),
            "paycore.admin.ops.recon.run" to setOf(listOf("result")),
            "paycore.admin.ops.recon.finding" to setOf(listOf("kind")),
            "paycore.admin.ops.recon.finding_duplicate" to setOf(emptyList()),
            "paycore.admin.ops.recon.lag_seconds" to setOf(emptyList()),
            "paycore.admin.ops.recon.open_findings" to setOf(listOf("kind")),
            "paycore.admin.approval.justification_rate_limited" to setOf(emptyList()),
            "paycore.admin.ops.call" to setOf(listOf("operation", "result")),
            "paycore.admin.ops.lookup_rate_limited" to setOf(emptyList()),
            "paycore.admin.approval" to setOf(listOf("kind", "operation", "result")),
            "paycore.admin.approval.retry_later" to setOf(listOf("operation")),
            "paycore.admin.approval.integrity_violation" to setOf(listOf("check")),
        )
        // open_findings는 종류 전부(없으면 0). 기대값 변경(B2-2 MERCHANT-OPS-002 C-28): 6종 + OWNER_UNRESOLVABLE(가맹점 승인 확정 불가 finding).
        registry.find("paycore.admin.ops.recon.open_findings").gauges().map { it.id.getTag("kind") }.toSet() shouldBe
            setOf("ORPHAN_DECISION", "DECISION_FOR_UNAPPROVED", "FINGERPRINT_MISMATCH", "STATE_DIVERGENCE", "UNRESOLVED_WITH_DECISION", "MISSING_DECISION", "OWNER_UNRESOLVABLE")
    }
})
