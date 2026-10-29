package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.FingerprintInput
import com.paycore.admin.paymentops.application.provided.OperatorCancelContract
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient
import com.paycore.admin.paymentops.domain.OperatorCancelTargetFingerprint
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer

/**
 * 운영자 보정 취소 요청·승인 2-TX(ADMIN-PAYMENT-OPS-001 §4.12·§4.13·§4.15 — `OwnerApprovalRequestSpec`·`OwnerApprovalTargetChangedSpec`·`OwnerApprovalTargetBusySpec`·
 * `OwnerExecutionResultSpec`(대표 행)·S4 리뷰 9). 실제 MySQL 8.4.11, PAYMENT는 실제 HTTP 대역([StubPaymentOpsServer] — 서명을 앱과 독립으로 검증).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerServiceApprovalFlowSpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val contract: OperatorCancelContract,
    private val client: PaymentOpsClient,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun audits(id: String) = root().use {
        it.column(
            "SELECT CONCAT(action, '|', COALESCE(target_actor_id, '-'), '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id,
        )
    }
    fun paymentAudits(paymentId: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_ref = ? ORDER BY event_id", paymentId) }
    fun rowsFor(paymentId: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", paymentId) as Number).toLong() }
    fun lastStep(actorId: String) = root().use { it.scalar("SELECT COALESCE(last_totp_step, -1) FROM admin_account WHERE actor_id = ?", actorId).toString() }
    fun posts(id: String) = StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" }
    fun started(cid: String) = StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"$cid\",\"status\":\"IN_PROGRESS\",\"existing\":false}")

    Given("a request by an OPERATOR (§4.12)") {
        Then("it stores the approval (PAYMENT target, revision), the INSERT-only command with the pinned target, and a target_ref audit row") {
            val operator = fixture.operator()
            val (id, paymentId) = fixture.requested(operator)
            root().use {
                it.column(
                    "SELECT CONCAT(r.status, '|', r.target_kind, '|', r.target_ref, '|', r.target_security_version, '|', r.unresolved_key, '|', c.amount_minor, '|', c.currency, " +
                        "'|', c.purpose_kind, '|', (c.pinned_target = r.target_snapshot)) FROM admin_approval_request r JOIN admin_operator_cancel_command c ON c.approval_id = r.approval_id " +
                        "WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf("PENDING|PAYMENT|$paymentId|3|PAYMENT:$paymentId|7000|KRW|INCIDENT_OR_RECON_CORRECTION|1")
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|-|$paymentId")
        }
        Then("invalid input is 400 before PAYMENT is called (no row, no call): id format, amount, purpose, reference/reason format, card number in reason") {
            val operator = fixture.operator()
            val paymentId = fixture.newPaymentId()
            StubPaymentOpsServer.targetFound(paymentId)
            listOf(
                fixture.request(operator, "pay_x"), fixture.request(operator, paymentId, amountMinor = 0), fixture.request(operator, paymentId, purpose = "WHIM"),
                fixture.request(operator, paymentId, reference = " RECON"), fixture.request(operator, paymentId, reason = "card 4111 1111 1111 1111"),
                fixture.request(operator, paymentId, reason = "trailing "),
            ) shouldBe listOf(
                // I-111(S8): 첫 거절 필드와 고정 이유 code(값 원문 없음).
                ApprovalOutcome.Rejected("INVALID_INPUT", "paymentId", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "amountMinor", "NOT_POSITIVE"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "purposeKind", "UNKNOWN_KIND"), ApprovalOutcome.Rejected("INVALID_INPUT", "purposeReference", "EDGE_WHITESPACE"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "reason", "CARD_NUMBER_PATTERN"), ApprovalOutcome.Rejected("INVALID_INPUT", "reason", "EDGE_WHITESPACE"),
            )
            (rowsFor(paymentId) to StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$paymentId/operator-cancel-target").size) shouldBe (0L to 0)
        }
        Then("a non-OPERATOR is denied without calling PAYMENT") {
            val paymentId = fixture.newPaymentId()
            StubPaymentOpsServer.targetFound(paymentId)
            fixture.request(storage.securityAdmin(), paymentId) shouldBe ApprovalOutcome.Denied
            StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$paymentId/operator-cancel-target").size shouldBe 0
        }
        Then("PAYMENT unavailable → 503 PAYMENT_UNAVAILABLE with nothing stored; not found → PAYMENT_NOT_FOUND with a separate-TX audit row") {
            val operator = fixture.operator()
            val down = fixture.newPaymentId()
            StubPaymentOpsServer.target(down, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val missing = fixture.newPaymentId()
            listOf(fixture.request(operator, down), fixture.request(operator, missing)) shouldBe
                listOf(ApprovalOutcome.Unavailable("PAYMENT_UNAVAILABLE"), ApprovalOutcome.Rejected("PAYMENT_NOT_FOUND"))
            listOf(rowsFor(down), rowsFor(missing)) shouldBe listOf(0L, 0L)
            paymentAudits(down) shouldBe emptyList()
            paymentAudits(missing) shouldBe listOf("APPROVAL_DENIED|kind=OPERATOR_CORRECTION_CANCEL;reason=PAYMENT_NOT_FOUND")
        }
        Then("advisory (L-7): an in-flight cancel, a non-cancellable payment or an amount over the cancellable balance is refused with an audit row, nothing stored") {
            val operator = fixture.operator()
            val busy = fixture.newPaymentId().also { StubPaymentOpsServer.targetFound(it, inFlight = true) }
            val closed = fixture.newPaymentId().also { StubPaymentOpsServer.targetFound(it, cancellable = false) }
            val small = fixture.newPaymentId().also { StubPaymentOpsServer.targetFound(it, cancellableMinor = 6_999) }
            listOf(fixture.request(operator, busy), fixture.request(operator, closed), fixture.request(operator, small)) shouldBe listOf(
                ApprovalOutcome.Rejected("CANCEL_IN_PROGRESS_ADVISORY"), ApprovalOutcome.Rejected("PAYMENT_NOT_CANCELLABLE_ADVISORY"),
                ApprovalOutcome.Rejected("AMOUNT_EXCEEDS_CANCELLABLE_ADVISORY"),
            )
            listOf(rowsFor(busy), rowsFor(closed), rowsFor(small)) shouldBe listOf(0L, 0L, 0L)
            paymentAudits(busy) shouldBe listOf("APPROVAL_DENIED|kind=OPERATOR_CORRECTION_CANCEL;reason=CANCEL_IN_PROGRESS_ADVISORY")
        }
        Then("a second request for a Payment with an unresolved approval is 409 DUPLICATE_UNRESOLVED (unresolved_key 1062), audited, one row") {
            val operator = fixture.operator()
            val (_, paymentId) = fixture.requested(operator)
            fixture.request(fixture.operator(), paymentId) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            rowsFor(paymentId) shouldBe 1L
            paymentAudits(paymentId).last() shouldBe "APPROVAL_DENIED|kind=OPERATOR_CORRECTION_CANCEL;reason=DUPLICATE_UNRESOLVED"
        }
    }

    Given("an approval by an APPROVER with step-up TOTP (§4.13)") {
        Then("200 STARTED → EXECUTED: one signed POST carrying the stored body, oct1 computed independently, execution row with the owner reference, target_ref audits") {
            val operator = fixture.operator()
            val (id, paymentId) = fixture.requested(operator)
            StubPaymentOpsServer.execution(id, started("pcn_01JC000000000000000000EX01"))
            val approver = fixture.approver()
            fixture.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = StubPaymentOpsServer.requestsFor(id).single()
            post.signatureValid shouldBe true
            val expected = OperatorCancelTargetFingerprint.of(
                OperatorCancelTargetFingerprint.Input(paymentId, 7_000, "KRW", 3, id, operator.id.value, approver.account.id.value, "INCIDENT_OR_RECON_CORRECTION", "RECON-2026-0001"),
            )
            root().use {
                it.column(
                    "SELECT CONCAT(r.status, '|', r.decided_by, '|', COALESCE(r.unresolved_key, '-'), '|', e.fingerprint, '|', e.attempt_count, '|', e.owner_reference, '|', e.executor_actor_id, " +
                        "'|', TIMESTAMPDIFF(HOUR, e.approved_at, e.execute_before)) FROM admin_approval_request r JOIN admin_owner_execution e ON e.approval_id = r.approval_id WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf("EXECUTED|${approver.account.id.value}|-|$expected|1|pcn_01JC000000000000000000EX01|${approver.account.id.value}|24")
            root().use { c -> c.prepareStatement("SELECT request_body FROM admin_owner_execution WHERE approval_id = ?").use { s -> s.setString(1, id); s.executeQuery().use { r -> r.next(); r.getBytes(1).toList() } } } shouldBe
                post.body.toList()
            post.body.toString(Charsets.UTF_8).contains("\"approvedTargetFingerprint\":\"$expected\"") shouldBe true
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|-|$paymentId", "APPROVAL_EXECUTING|-|$paymentId", "APPROVAL_EXECUTED|-|$paymentId")
            contract.fingerprint(FingerprintInput(paymentId, 7_000, "KRW", 3, id, operator.id.value, approver.account.id.value, "INCIDENT_OR_RECON_CORRECTION", "RECON-2026-0001")) shouldBe expected
        }
        Then("TARGET_BUSY (L-7): an in-flight cancel at approval → 409, PENDING, TOTP not verified or consumed, no POST") {
            val (id, paymentId) = fixture.requested()
            StubPaymentOpsServer.targetFound(paymentId, inFlight = true)
            val approver = fixture.approver()
            val before = lastStep(approver.account.id.value)
            fixture.approve(id, approver) shouldBe ApprovalOutcome.Rejected("TARGET_BUSY")
            listOf(status(id), lastStep(approver.account.id.value), posts(id).toString()) shouldBe listOf("PENDING", before, "0")
        }
        Then("TARGET_CHANGED: the revision moved since the request → INVALIDATED with the TOTP step consumed, no POST, the Payment is free again") {
            val (id, paymentId) = fixture.requested()
            StubPaymentOpsServer.targetFound(paymentId, revision = 4)
            val approver = fixture.approver()
            val before = lastStep(approver.account.id.value)
            fixture.approve(id, approver) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            listOf(status(id), posts(id).toString()) shouldBe listOf("INVALIDATED", "0")
            lastStep(approver.account.id.value) shouldNotBe before
            StubPaymentOpsServer.targetFound(paymentId, revision = 4)
            (fixture.request(fixture.operator(), paymentId) is ApprovalOutcome.Created) shouldBe true
        }
        Then("an OPERATOR who also holds APPROVER cannot approve their own request (SELF_APPROVAL — 2인 규칙, PENDING, no POST, denial audited)") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, paymentId) = fixture.requested(storage.load(both.account.id))
            fixture.approve(id, both) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            listOf(status(id), posts(id).toString()) shouldBe listOf("PENDING", "0")
            audits(id).last() shouldBe "APPROVAL_DENIED|-|$paymentId"
        }
        Then("result mapping: derived 409 → EXECUTION_UNKNOWN + integrity flag (Payment stays blocked); 503 → UNKNOWN; 409 rejection → OWNER_REJECTED; 400 → OWNER_REJECTED") {
            fun run(reply: StubPaymentOpsServer.Reply): Triple<String, ApprovalOutcome, String> {
                val (id, paymentId) = fixture.requested()
                StubPaymentOpsServer.execution(id, reply)
                val outcome = fixture.approve(id)
                val row = root().use {
                    it.column(
                        "SELECT CONCAT(r.status, '|', COALESCE(r.result_code, '-'), '|', COALESCE(r.unresolved_key, '-'), '|', COALESCE(e.integrity_flag, '-'), '|', " +
                            "COALESCE(e.last_failure, '-'), '|', COALESCE(e.owner_result_code, '-'), '|', e.attempt_count) " +
                            "FROM admin_approval_request r JOIN admin_owner_execution e ON e.approval_id = r.approval_id WHERE r.approval_id = ?",
                        id,
                    ).single()
                }
                return Triple(id, outcome, row.replace("PAYMENT:$paymentId", "KEY"))
            }
            val conflict = run(StubPaymentOpsServer.Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}"))
            val unavailable = run(StubPaymentOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}"))
            val rejected = run(StubPaymentOpsServer.Reply(409, "{\"error\":\"CANCEL_IN_PROGRESS\"}"))
            val malformed = run(StubPaymentOpsServer.Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}"))
            listOf(conflict, unavailable, rejected, malformed).map { it.second to it.third } shouldBe listOf(
                ApprovalOutcome.Executing(conflict.first, "EXECUTION_UNKNOWN") to "EXECUTION_UNKNOWN|-|KEY|APPROVAL_CONFLICT|APPROVAL_CONFLICT|-|1",
                ApprovalOutcome.Executing(unavailable.first, "EXECUTION_UNKNOWN") to "EXECUTION_UNKNOWN|-|KEY|-|UNAVAILABLE_TEMPORARILY_UNAVAILABLE|-|1",
                ApprovalOutcome.OwnerRejected("CANCEL_IN_PROGRESS") to "OWNER_REJECTED|CANCEL_IN_PROGRESS|-|-|-|CANCEL_IN_PROGRESS|1",
                ApprovalOutcome.OwnerRejected("REQUEST_MALFORMED") to "OWNER_REJECTED|REQUEST_MALFORMED|-|-|-|REQUEST_MALFORMED|1",
            )
        }
        Then("S4 review 9: a pinned target that no longer matches the approval target (root change) is an integrity violation — rollback, PENDING, TOTP not consumed, no POST") {
            val (id, paymentId) = fixture.requested()
            root().use { it.update("UPDATE admin_operator_cancel_command SET pinned_target = JSON_SET(pinned_target, '$.balanceRevision', 99) WHERE approval_id = ?", id) }
            val approver = fixture.approver()
            val before = lastStep(approver.account.id.value)
            fixture.approve(id, approver) shouldBe ApprovalOutcome.IntegrityViolation
            listOf(status(id), lastStep(approver.account.id.value), posts(id).toString()) shouldBe listOf("PENDING", before, "0")
            // S5 리뷰 M-2: 무결성 위반 감사 행(별도 TX)은 승인 ID와 대상 Payment(target_ref)를 남긴다.
            root().use {
                it.column(
                    "SELECT detail FROM admin_audit_event WHERE action = 'APPROVAL_INTEGRITY_VIOLATION' AND approval_id = ? AND target_ref = ? AND target_actor_id IS NULL", id, paymentId,
                )
            } shouldBe listOf("column=target_security_version")
        }
    }

    Given("the PAYMENT client inside a database transaction (PaymentCallOutsideTransactionSpec)") {
        Then("it refuses before any call") {
            val tx = TransactionTemplate(transactionManager)
            shouldThrow<IllegalStateException> { tx.execute { client.target(fixture.newPaymentId(), CallPurpose.APPROVAL) } }
        }
    }
})
