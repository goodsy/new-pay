package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * B3-1 회복 종류 행(MERCHANT-OPS-002 §8.3 B3-1 8 — §9.16 회복: 결정 조회 → 기한 확인(`operation` 포함 저장 바이트) → 참여자 → 무결성 플래그 → 같은 바이트 재전송).
 * 실제 MySQL + MASTER 실제 HTTP 대역. 호출 순서는 대역이 받은 경로로 단언한다(E = 실행, D = 결정 조회, C = 기한 확인).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantActivationRecoverySpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantActivationApprovalFixture(base, merchants, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun reference(id: String) = root().use { it.scalar("SELECT COALESCE(owner_reference, '-') FROM admin_owner_execution WHERE approval_id = ?", id) as String }
    fun fingerprint(id: String) = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
    fun calls(id: String, execPath: String): String = StubMasterOpsServer.received.filter {
        it.path in setOf(execPath, StubMasterOpsServer.APPROVAL_DECISIONS, StubMasterOpsServer.DEADLINE_CONFIRMATIONS) && base.idOf(it.body) == id
    }.joinToString("") {
        when (it.path) {
            execPath -> "E"
            StubMasterOpsServer.APPROVAL_DECISIONS -> "D"
            else -> "C"
        }
    }
    fun bodies(id: String, p: String) = StubMasterOpsServer.requestsForPath(p).filter { base.idOf(it.body) == id }.map { String(it.body) }
    fun run(id: String) {
        root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
        recovery.recoverDue()
    }
    fun pastDeadline(id: String) = root().use {
        it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
    }
    fun lost(request: () -> Pair<String, String>): Pair<String, String> {
        val (id, m) = request()
        base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        return id to m
    }

    Given("RA-1 activation: decision lookup → deadline confirmation (operation in the stored bytes) → resend") {
        Then("NONE before the deadline resends the same bytes to payment-method-activation-approvals; DECIDED (ApprovePaymentMethodActivation, same mop1) closes EXECUTED with no new POST") {
            val (id, m) = lost { f.requestedActivation() }
            run(id)
            calls(id, StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS) shouldBe "EDE"
            bodies(id, StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS).let { it.size to it.distinct().size } shouldBe (2 to 1)
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.activationOf(m), operation = "ApprovePaymentMethodActivation"))
            run(id)
            listOf(status(id), reference(id), calls(id, StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS)) shouldBe listOf("EXECUTED|-", f.activationOf(m), "EDED")
        }
        Then("past the deadline: confirmation only (no execution POST) with the stored bytes carrying operation ApprovePaymentMethodActivation; APPROVAL_EXPIRED → OWNER_REJECTED") {
            val (id, _) = lost { f.requestedActivation() }
            pastDeadline(id)
            base.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED")) }
            run(id)
            listOf(status(id), calls(id, StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS)) shouldBe listOf("OWNER_REJECTED|APPROVAL_EXPIRED", "EDC")
            val confirmed = bodies(id, StubMasterOpsServer.DEADLINE_CONFIRMATIONS).single()
            confirmed shouldBe bodies(id, StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS).single()
            json.readTree(confirmed).get("operation").asString() shouldBe "ApprovePaymentMethodActivation"
        }
        Then("a decision naming another operation (DecideSiteReview) with the same fingerprint is unreadable for this grammar → stays UNKNOWN, no EXECUTED") {
            val (id, m) = lost { f.requestedActivation() }
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.activationOf(m), operation = "DecideSiteReview"))
            run(id)
            status(id) shouldBe "EXECUTION_UNKNOWN|-"
        }
    }

    Given("RS-1 site review: the same order on DecideSiteReview") {
        Then("lost response → DECIDED site_…#n of the pinned site → EXECUTED; deadline confirmation bytes carry operation DecideSiteReview") {
            val (id, m) = lost { f.requestedSiteReview() }
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.secondSiteOf(m) + "#2", operation = "DecideSiteReview"))
            run(id)
            listOf(status(id), reference(id), calls(id, StubMasterOpsServer.SITE_REVIEW_APPROVALS)) shouldBe listOf("EXECUTED|-", f.secondSiteOf(m) + "#2", "ED")
            val (late, _) = lost { f.requestedSiteReview() }
            pastDeadline(late)
            base.deadlineReplies[late] = { Reply(200, StubMasterOpsServer.rejected("SITE_REVIEW_TRANSITION_NOT_ALLOWED")) }
            run(late)
            status(late) shouldBe "OWNER_REJECTED|SITE_REVIEW_TRANSITION_NOT_ALLOWED"
            json.readTree(bodies(late, StubMasterOpsServer.DEADLINE_CONFIRMATIONS).single()).get("operation").asString() shouldBe "DecideSiteReview"
        }
    }
})
