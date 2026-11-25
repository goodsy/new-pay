package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.application.ApprovedCommandFixture.contractApproval
import com.paycore.master.merchant.application.ApprovedCommandFixture.evidenceRow
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalDecidedVia
import com.paycore.master.merchant.application.provided.ApprovalDecision
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.ApprovalOutcome
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MERCHANT-OPS-002 §8.2 B2-2-3 결정 조회·기한 확인(C-18 개정, rev 0.6.2 m-a·m-b·m-c·n-b): 결정 조회는 NONE/DECIDED·쓰기 0(전 표 CHECKSUM 불변), 기한 확인은 기한 전 NOT_YET_DUE + 예약 행 없음·재시도 0,
 * 기한 뒤 결정 없음 → `APPROVAL_EXPIRED` 저장(`DEADLINE_CONFIRM`)·업무 표 불변, 이후 실행 재전송은 그 만료를 재생, 결정 있음 → 재생, 실행 진행 중 → RequestInProgress, 판정 1·2 저장·재생, 판정 3 행 없음.
 */
@MasterDbTest
class ApprovalDecisionSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val decisions: ApprovalDecisionUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    evidences: ApprovalEvidenceStore,
    fingerprints: com.paycore.master.merchant.application.required.RequestFingerprintDigester,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, fingerprints, evidences)

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun evidence(approvalId: String) = runtime(container).use { evidenceRow(it, approvalId) }

    /** MASTER 표별 행 수·CHECKSUM(root — 쓰기 0 확인). [only]가 있으면 그 표만. */
    fun snapshot(only: Set<String>? = null): Map<String, String> = MasterDbAccounts.root(container).use { c ->
        c.column(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = '${MasterDbAccounts.MASTER_DATABASE}' AND table_type = 'BASE TABLE' ORDER BY table_name",
        ).filter { only == null || it in only }.associateWith { t ->
            val rows = c.scalar("SELECT COUNT(*) FROM `$t`").toString()
            val checksum = c.prepareStatement("CHECKSUM TABLE `$t`").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(2) } }
            "$rows/$checksum"
        }
    }

    val businessTables = setOf(
        "contract", "contract_revision", "contract_revision_approval", "merchant", "merchant_account", "default_designation", "master_change_history", "outbox_event",
        "entitlement_snapshot_issue",
    )

    Given("the decision query (read-only)") {
        Then("an unknown approvalId is NONE; after an execution it is DECIDED with operation, outcome, reference, fingerprint and decidedVia; no table changes") {
            val unknown = ApprovalEvidenceFixtures.approvalId()
            val before = snapshot()
            decisions.decision(unknown) shouldBe ApprovalDecision.None
            snapshot() shouldBe before

            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            succeeded(onboarding.approveContractRevisionWithEvidence(command))
            val afterExecution = snapshot()
            decisions.decision(command.evidence.approvalId) shouldBe ApprovalDecision.Decided(
                AdminOperation.APPROVE_CONTRACT_REVISION, ApprovalOutcome.EXECUTED, "${ready.contractId.value}#1", null, command.evidence.fingerprint, ApprovalDecidedVia.EXECUTE,
            )
            snapshot() shouldBe afterExecution
        }

        Then("a stored evidence rejection is DECIDED REJECTED with the rejection and no reference") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId, evidence = ApprovalEvidenceFixtures.sameActor())
            onboarding.approveContractRevisionWithEvidence(command)
            decisions.decision(command.evidence.approvalId) shouldBe ApprovalDecision.Decided(
                AdminOperation.APPROVE_CONTRACT_REVISION, ApprovalOutcome.REJECTED, null, MasterRejection.APPROVAL_SAME_ACTOR, command.evidence.fingerprint, ApprovalDecidedVia.EXECUTE,
            )
        }
    }

    Given("the deadline confirmation (no execution path)") {
        Then("m-c: before the deadline it is NOT_YET_DUE and leaves no admin_request row for that key, after exactly one reservation attempt (no retry)") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            val reservations = AtomicInteger()
            faults.after(MasterFaultPoint.ADMIN_RESERVE) { reservations.incrementAndGet() }
            clock.set(command.evidence.executeBefore.minusMillis(1))
            decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.NotYetDue
            reservations.get() shouldBe 1
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value) shouldBe 0
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 0
        }

        Then("at the deadline without a decision it stores APPROVAL_EXPIRED with a DEADLINE_CONFIRM row; business tables unchanged; a later execution resend replays that expiry") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            val business = snapshot(businessTables)
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false))
            snapshot(businessTables) shouldBe business
            evidence(command.evidence.approvalId).shouldNotBeNull().let { listOf(it.outcome, it.resultCode, it.resultReference, it.decidedVia) } shouldBe
                listOf("REJECTED", "APPROVAL_EXPIRED", null, "DEADLINE_CONFIRM")
            decisions.decision(command.evidence.approvalId) shouldBe ApprovalDecision.Decided(
                AdminOperation.APPROVE_CONTRACT_REVISION, ApprovalOutcome.REJECTED, null, MasterRejection.APPROVAL_EXPIRED, command.evidence.fingerprint, ApprovalDecidedVia.DEADLINE_CONFIRM,
            )
            clock.reset()
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true)
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", ready.contractId.value) shouldBe 0
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true))
        }

        Then("with an executed decision it replays the success (success stays success), also after the deadline") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            succeeded(onboarding.approveContractRevisionWithEvidence(command))
            clock.set(command.evidence.executeBefore.plusSeconds(3600))
            decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.Decided(AdminCommandResult.Succeeded("${ready.contractId.value}#1", replayed = true))
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 1
        }

        Then("m-a: the same actor is stored as APPROVAL_SAME_ACTOR with a DEADLINE_CONFIRM row and replays; a window violation likewise (no permanent 503)") {
            listOf(
                ApprovalEvidenceFixtures.sameActor() to MasterRejection.APPROVAL_SAME_ACTOR,
                ApprovalEvidenceFixtures.windowExceeded() to MasterRejection.APPROVAL_WINDOW_INVALID,
            ).forEach { (e, rejection) ->
                val ready = readyMerchant(onboarding, approved = false)
                val command = contractApproval(ready.contractId, evidence = e)
                decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(rejection, null, replayed = false))
                decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(rejection, null, replayed = true))
                evidence(command.evidence.approvalId).shouldNotBeNull().let { listOf(it.resultCode, it.decidedVia) } shouldBe listOf(rejection.name, "DEADLINE_CONFIRM")
            }
        }

        Then("m-b: an approvalId already used by another operation is APPROVAL_ID_ALREADY_USED with no evidence row added") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            val other = ApprovedCommandFixture.withFingerprint(command.evidence, AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf("m-b"))
            executor.executeApproved(
                AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, ApprovedCommandFixture.requestOf(other), other, ApprovalEvidenceFixtures.DEMO_WINDOW, listOf("m-b"),
            ) { work -> work.lockedNow(); work.beginWrites(); "mid_m-b" }
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_ID_ALREADY_USED, null, replayed = false))
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 1
            evidence(command.evidence.approvalId).shouldNotBeNull().operation shouldBe "IssueFirstDefaultMerchantAccount"
        }

        Then("while an execution holds the reservation lock the deadline confirmation is RequestInProgress (503), and nothing is decided by it") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(MasterFaultPoint.MERCHANT_LOCK) {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val execution = pool.submit<AdminCommandResult> { onboarding.approveContractRevisionWithEvidence(command) }
                holding.await(10, TimeUnit.SECONDS) shouldBe true
                clock.set(command.evidence.executeBefore)
                shouldThrow<AdminCommandFailure.RequestInProgress> { decisions.confirmDeadline(command) }
                clock.reset()
                release.countDown()
                execution.get(30, TimeUnit.SECONDS) shouldBe AdminCommandResult.Succeeded("${ready.contractId.value}#1", replayed = false)
            } finally {
                release.countDown()
                pool.shutdown()
            }
            evidence(command.evidence.approvalId).shouldNotBeNull().decidedVia shouldBe "EXECUTE"
        }
    }

    Given("invariant n-a after the decision and deadline scenarios") {
        Then("every approval-operation admin_request result has its 1:1 evidence row except APPROVAL_ID_ALREADY_USED") {
            runtime(container).use { ApprovedCommandFixture.invariantViolations(it) }.shouldBeEmpty()
        }
    }
})
