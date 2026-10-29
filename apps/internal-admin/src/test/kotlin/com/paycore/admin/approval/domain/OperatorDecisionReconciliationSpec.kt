package com.paycore.admin.approval.domain

import com.paycore.admin.approval.domain.OperatorDecisionReconciliation.AdminView
import com.paycore.admin.approval.domain.OperatorDecisionReconciliation.Finding
import com.paycore.admin.approval.domain.OperatorDecisionReconciliation.PaymentDecision
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 대사 판정표(ADMIN-PAYMENT-OPS-001 §4.17 — 순수 함수, 상태를 바꾸지 않는다). 발견 종류 우선순위 ORPHAN → DECISION_FOR_UNAPPROVED → FINGERPRINT_MISMATCH →
 * STATE_DIVERGENCE → UNRESOLVED_WITH_DECISION, 여럿이 맞으면 앞의 것 하나 + 나머지는 detail. DECISION_FOR_UNAPPROVED의 detail은 다른 열 이름(없으면 CONTENT_MATCH).
 */
class OperatorDecisionReconciliationSpec : FunSpec({

    val now = Instant.parse("2026-10-28T12:00:00Z")
    val unresolvedAfter = Duration.ofMinutes(3)
    val fp = "oct1:" + "a".repeat(64)
    val started = PaymentDecision(
        "apr_01JC0000000000000000000001", "STARTED", null, "pcn_01JC0000000000000000000001", "pay_01JC0000000000000000000001", 7000, "KRW",
        "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", fp, now.minusSeconds(30),
    )
    val rejected = started.copy(decision = "REJECTED", code = "CANCEL_IN_PROGRESS", cancellationId = null)
    fun admin(status: ApprovalStatus, resultCode: String? = null, ownerReference: String? = null, executed: Boolean = true) = AdminView(
        ApprovalKind.OPERATOR_CORRECTION_CANCEL, status, resultCode, "pay_01JC0000000000000000000001", "adm_01JC00000000000000000000R1", 7000, "KRW",
        if (executed) "adm_01JC00000000000000000000A1" else null, if (executed) fp else null, ownerReference,
    )
    fun judge(d: PaymentDecision, a: AdminView?) = OperatorDecisionReconciliation.judge(d, a, now, unresolvedAfter)

    test("matching terminal states and young unresolved approvals are not findings") {
        listOf(
            judge(started, admin(ApprovalStatus.EXECUTED, ownerReference = "pcn_01JC0000000000000000000001")),
            judge(rejected, admin(ApprovalStatus.OWNER_REJECTED, resultCode = "CANCEL_IN_PROGRESS")),
            judge(started, admin(ApprovalStatus.EXECUTION_UNKNOWN)), judge(started, admin(ApprovalStatus.EXECUTING)),
        ) shouldBe List(4) { null }
    }

    test("ORPHAN_DECISION: no ADMIN approval, or an approval of another kind") {
        judge(started, null) shouldBe Finding(ReconciliationFindingKind.ORPHAN_DECISION, null)
        judge(started, admin(ApprovalStatus.EXECUTED).copy(kind = ApprovalKind.ROLE_GRANT)) shouldBe Finding(ReconciliationFindingKind.ORPHAN_DECISION, null)
    }

    test("DECISION_FOR_UNAPPROVED (M-a): each non-executed status, with the differing column names or CONTENT_MATCH") {
        listOf(ApprovalStatus.PENDING, ApprovalStatus.REJECTED, ApprovalStatus.WITHDRAWN, ApprovalStatus.EXPIRED, ApprovalStatus.INVALIDATED).map {
            judge(started, admin(it, executed = false))
        } shouldBe List(5) { Finding(ReconciliationFindingKind.DECISION_FOR_UNAPPROVED, "CONTENT_MATCH") }
        judge(started.copy(paymentId = "pay_01JC0000000000000000000002", amountMinor = 1, currency = "USD", requestedBy = "adm_01JC00000000000000000000X1"), admin(ApprovalStatus.PENDING, executed = false)) shouldBe
            Finding(ReconciliationFindingKind.DECISION_FOR_UNAPPROVED, "PAYMENT_ID,AMOUNT,CURRENCY,REQUESTER")
        judge(started.copy(amountMinor = 1), admin(ApprovalStatus.WITHDRAWN, executed = false)) shouldBe Finding(ReconciliationFindingKind.DECISION_FOR_UNAPPROVED, "AMOUNT")
    }

    test("FINGERPRINT_MISMATCH: fingerprint, payment, amount, currency, requester or approver differ from the executed approval") {
        listOf(
            started.copy(fingerprint = "oct1:" + "b".repeat(64)), started.copy(paymentId = "pay_01JC0000000000000000000002"), started.copy(amountMinor = 6999),
            started.copy(currency = "USD"), started.copy(requestedBy = "adm_01JC00000000000000000000X1"), started.copy(approvedBy = "adm_01JC00000000000000000000X2"),
        ).map { judge(it, admin(ApprovalStatus.EXECUTED, ownerReference = "pcn_01JC0000000000000000000001"))?.kind } shouldBe
            List(6) { ReconciliationFindingKind.FINGERPRINT_MISMATCH }
    }

    test("STATE_DIVERGENCE: EXECUTED vs REJECTED, OWNER_REJECTED vs STARTED, different code, different cancellation id") {
        listOf(
            judge(rejected, admin(ApprovalStatus.EXECUTED, ownerReference = "pcn_01JC0000000000000000000001")),
            judge(started, admin(ApprovalStatus.OWNER_REJECTED, resultCode = "CANCEL_IN_PROGRESS")),
            judge(rejected, admin(ApprovalStatus.OWNER_REJECTED, resultCode = "OPERATOR_TARGET_STALE")),
            judge(started, admin(ApprovalStatus.EXECUTED, ownerReference = "pcn_01JC0000000000000000000002")),
        ).map { it?.kind } shouldBe List(4) { ReconciliationFindingKind.STATE_DIVERGENCE }
    }

    test("UNRESOLVED_WITH_DECISION: the decision is older than stable delay + backoff max while ADMIN is still EXECUTING / EXECUTION_UNKNOWN") {
        val old = started.copy(recordedAt = now.minus(Duration.ofMinutes(10)))
        listOf(judge(old, admin(ApprovalStatus.EXECUTION_UNKNOWN)), judge(old, admin(ApprovalStatus.EXECUTING))).map { it?.kind } shouldBe
            List(2) { ReconciliationFindingKind.UNRESOLVED_WITH_DECISION }
    }

    test("priority: the first matching kind is recorded and the others go to detail") {
        judge(rejected.copy(fingerprint = "oct1:" + "b".repeat(64)), admin(ApprovalStatus.EXECUTED, ownerReference = "pcn_01JC0000000000000000000001")) shouldBe
            Finding(ReconciliationFindingKind.FINGERPRINT_MISMATCH, "STATE_DIVERGENCE")
    }

    test("S8 non-disclosure (I-128): a finding record accepts only column names or codes as detail — free text cannot be stored") {
        fun record(detail: String?) = com.paycore.admin.approval.application.required.ReconciliationFindingRecord(
            ReconciliationFindingKind.DECISION_FOR_UNAPPROVED, "apr_01JC0000000000000000000001", null, detail, now,
        )
        listOf(null, "CONTENT_MATCH", "PAYMENT_ID,AMOUNT,CURRENCY,REQUESTER").map { record(it).detail } shouldBe listOf(null, "CONTENT_MATCH", "PAYMENT_ID,AMOUNT,CURRENCY,REQUESTER")
        listOf("duplicate charge", "RECON-2026-0001", "AMOUNT, CURRENCY", "", "amount").forEach { text ->
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { record(text) }
        }
    }
})
