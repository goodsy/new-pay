package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.contractApproval
import com.paycore.master.merchant.application.ApprovedCommandFixture.evidenceRow
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MERCHANT-OPS-002 §8.2 B2-2-1(순서 m-4)·B2-2-3(원자성 — 실제 MySQL rollback)·B2-2-5(근거 없는 Use Case 진입 관문, C-26)·C-31·m-1(두 연결 PK 경합)·불변식 n-a.
 * 실행기는 operation 매개변수형이라(B2-3 재사용) 다른 operation(`IssueFirstDefaultMerchantAccount`) 근거 경로는 실행기를 직접 만들어 대역 본문으로 확인한다(같은 실제 Store·TX).
 */
@MasterDbTest
class ApprovedCommandAtomicitySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val mode: SwitchableDeploymentMode,
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    evidences: ApprovalEvidenceStore,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
        mode.reset()
    }

    /** 대역 본문용 실행기(운영과 같은 Store·TX — operation만 다르다). */
    val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, evidences)

    class Injected : RuntimeException("injected")

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun evidence(approvalId: String) = runtime(container).use { evidenceRow(it, approvalId) }

    /** 승인 하나에 관한 MASTER 행 수(계약 승인 사실·권리 revision·Snapshot·이력·Outbox·멱등 기록·근거 행). */
    data class Rows(val approvals: Long, val entitlement: Long, val snapshots: Long, val history: Long, val outbox: Long, val adminRequests: Long, val evidence: Long)

    fun rows(ready: OnboardingFixture.ReadyMerchant, approvalId: String): Rows = Rows(
        approvals = count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", ready.contractId.value),
        entitlement = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value),
        snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", ready.merchantId.value),
        history = count("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'ContractRevisionApproved' AND aggregate_id = ?", ready.contractId.value),
        outbox = count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ContractRevisionApproved' AND aggregate_id = ?", ready.contractId.value),
        adminRequests = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.$approvalId"),
        evidence = count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", approvalId),
    )

    /** Rev1 승인(seed)·첫 MID(seed)·Rev2 등록(operator-1 — 미승인)까지 끝난 가맹점: Rev2 근거 승인이 권리 revision +1·Snapshot 발행을 일으킨다(MID 있음). */
    fun withMidAndRevision2(): OnboardingFixture.ReadyMerchant {
        val ready = readyMerchant(onboarding)
        succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand())) shouldStartWith "${MerchantAccountId.PREFIX}_"
        succeeded(
            onboarding.registerContractRevision(
                RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(ready.merchantId), EffectivePeriod(OnboardingFixture.t0)),
            ),
        )
        return ready
    }

    Given("B2-2-3: an approved contract revision with evidence is one local transaction") {
        Then("success: approval fact, entitlement revision +1, Snapshot, history (actor = approver), Outbox, admin_request result and one EXECUTED evidence row") {
            val ready = withMidAndRevision2()
            val command = contractApproval(ready.contractId, 2)
            val before = rows(ready, command.evidence.approvalId)
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Succeeded("${ready.contractId.value}#2", replayed = false)
            val after = rows(ready, command.evidence.approvalId)
            after shouldBe before.copy(
                approvals = before.approvals + 1, entitlement = before.entitlement + 1, snapshots = before.snapshots + 1, history = before.history + 1,
                outbox = before.outbox + 1, adminRequests = 1, evidence = 1,
            )
            val row = evidence(command.evidence.approvalId).shouldNotBeNull()
            val requestRecordId = runtime(container).use {
                it.scalar("SELECT request_record_id FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value)
            }
            row shouldBe ApprovedCommandFixture.EvidenceRow(
                operation = "ApproveContractRevision", requestRecordId = requestRecordId.toString(),
                requestedBy = ApprovalEvidenceFixtures.REQUESTER.value, approvedBy = ApprovalEvidenceFixtures.APPROVER.value,
                evidenceRef = ApprovalEvidenceFixtures.EVIDENCE_REF, fingerprint = command.evidence.fingerprint, outcome = "EXECUTED", resultCode = null,
                resultReference = "${ready.contractId.value}#2", decidedVia = "EXECUTE",
                approvedAtMillis = command.evidence.approvedAt.toEpochMilli(), executeBeforeMillis = command.evidence.executeBefore.toEpochMilli(),
            )
            runtime(container).use {
                it.scalar("SELECT approved_by FROM contract_revision_approval WHERE contract_id = ? AND revision_no = 2", ready.contractId.value)
            } shouldBe ApprovalEvidenceFixtures.APPROVER.value
            runtime(container).use {
                it.scalar(
                    "SELECT actor_ref FROM master_change_history WHERE change_type = 'ContractRevisionApproved' AND aggregate_id = ? ORDER BY id DESC LIMIT 1",
                    ready.contractId.value,
                )
            } shouldBe ApprovalEvidenceFixtures.APPROVER.value
            runtime(container).use {
                it.scalar("SELECT correlation_id FROM outbox_event WHERE event_type = 'ContractRevisionApproved' AND aggregate_id = ? ORDER BY id DESC LIMIT 1", ready.contractId.value)
            } shouldBe "mop.${command.evidence.approvalId}"
        }

        Then("a fault right after the evidence row write rolls back everything (no approval, no revision change, no evidence, no admin_request); a retry then executes once") {
            val ready = withMidAndRevision2()
            val command = contractApproval(ready.contractId, 2)
            val before = rows(ready, command.evidence.approvalId)
            faults.after(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD) { throw Injected() }
            shouldThrow<Injected> { onboarding.approveContractRevisionWithEvidence(command) }
            rows(ready, command.evidence.approvalId) shouldBe before
            faults.reset()
            onboarding.approveContractRevisionWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
            rows(ready, command.evidence.approvalId).evidence shouldBe 1
        }

        Then("a fault right at the result write rolls back everything as well") {
            val ready = withMidAndRevision2()
            val command = contractApproval(ready.contractId, 2)
            val before = rows(ready, command.evidence.approvalId)
            faults.after(MasterFaultPoint.ADMIN_COMPLETE) { throw Injected() }
            shouldThrow<Injected> { onboarding.approveContractRevisionWithEvidence(command) }
            rows(ready, command.evidence.approvalId) shouldBe before
        }

        Then("the same actor is a stored APPROVAL_SAME_ACTOR with a REJECTED evidence row; the contract is unchanged; the same request replays (one row)") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId, evidence = ApprovalEvidenceFixtures.sameActor())
            val before = rows(ready, command.evidence.approvalId)
            val rejected = AdminCommandResult.Rejected(MasterRejection.APPROVAL_SAME_ACTOR, null, replayed = false)
            onboarding.approveContractRevisionWithEvidence(command) shouldBe rejected
            onboarding.approveContractRevisionWithEvidence(command) shouldBe rejected.copy(replayed = true)
            rows(ready, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.outcome, it.resultCode, it.resultReference, it.decidedVia) shouldBe listOf("REJECTED", "APPROVAL_SAME_ACTOR", null, "EXECUTE")
                it.requestedBy shouldBe it.approvedBy
            }
        }

        Then("a window violation is a stored APPROVAL_WINDOW_INVALID with a REJECTED evidence row") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId, evidence = ApprovalEvidenceFixtures.windowExceeded())
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_WINDOW_INVALID, null, replayed = false)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "APPROVAL_WINDOW_INVALID"
            rows(ready, command.evidence.approvalId).approvals shouldBe 0
        }

        Then("the same approvalId under another operation is APPROVAL_ID_ALREADY_USED: stored, no evidence row added, the business body never runs") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            succeeded(onboarding.approveContractRevisionWithEvidence(command))
            val other = ApprovedCommandFixture.withFingerprint(command.evidence, AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf("x"))
            val bodyCalls = AtomicInteger()
            executor.executeApproved(
                AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, ApprovedCommandFixture.requestOf(other), other, ApprovalEvidenceFixtures.DEMO_WINDOW, listOf("x", other.fingerprint),
            ) { bodyCalls.incrementAndGet(); "mid_never" } shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_ID_ALREADY_USED, null, replayed = false)
            bodyCalls.get() shouldBe 0
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 1
            evidence(command.evidence.approvalId).shouldNotBeNull().operation shouldBe "ApproveContractRevision"
            runtime(container).use {
                it.scalar(
                    "SELECT CONCAT(result_kind, '/', result_code) FROM admin_request WHERE operation = 'IssueFirstDefaultMerchantAccount' AND idempotency_key = ?",
                    "mop1.${command.evidence.approvalId}",
                )
            } shouldBe "REJECTED/APPROVAL_ID_ALREADY_USED"
        }

        Then("resending the same request replays (replayed=true) with one evidence row; a different evidence under the same key is IdempotencyKeyReused (nothing stored)") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            succeeded(onboarding.approveContractRevisionWithEvidence(command))
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Succeeded("${ready.contractId.value}#1", replayed = true)
            val otherEvidenceRef = contractApproval(
                ready.contractId, evidence = ApprovalEvidenceFixtures.distinctActors(approvalId = command.evidence.approvalId, evidenceRef = "OTHER-DOC-1"),
            )
            onboarding.approveContractRevisionWithEvidence(otherEvidenceRef) shouldBe AdminCommandResult.IdempotencyKeyReused
            rows(ready, command.evidence.approvalId).let { listOf(it.adminRequests, it.evidence, it.approvals) } shouldBe listOf(1L, 1L, 1L)
        }

        Then("after the deadline: an executed request still replays SUCCEEDED; a never-executed one is stored APPROVAL_EXPIRED (REJECTED row, decided_via EXECUTE)") {
            val ready = readyMerchant(onboarding, approved = false)
            val executed = contractApproval(ready.contractId)
            succeeded(onboarding.approveContractRevisionWithEvidence(executed))
            clock.set(executed.evidence.executeBefore)
            onboarding.approveContractRevisionWithEvidence(executed) shouldBe AdminCommandResult.Succeeded("${ready.contractId.value}#1", replayed = true)

            val late = readyMerchant(onboarding, approved = false)
            val lateCommand = contractApproval(late.contractId)
            onboarding.approveContractRevisionWithEvidence(lateCommand) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            evidence(lateCommand.evidence.approvalId).shouldNotBeNull().let { listOf(it.outcome, it.resultCode, it.decidedVia) } shouldBe
                listOf("REJECTED", "APPROVAL_EXPIRED", "EXECUTE")
            rows(late, lateCommand.evidence.approvalId).approvals shouldBe 0
        }
    }

    Given("B2-2-1 order (m-4): pre-lock lookups, the post-lock expiry hook, and the write-phase guard") {
        Then("an expired evidence for an unknown contract is CONTRACT_NOT_FOUND (lookup before locks wins over expiry), stored with a REJECTED evidence row") {
            val missing = ContractId("ctr_" + com.paycore.master.merchant.domain.MasterFixture.ulid())
            val command = contractApproval(missing, evidence = ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_NOT_FOUND, null, replayed = false)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "CONTRACT_NOT_FOUND"
        }

        Then("an expired evidence for an existing, already approved revision is APPROVAL_EXPIRED (the hook after the locks runs before the Domain decision)") {
            val ready = readyMerchant(onboarding)
            val command = contractApproval(ready.contractId, evidence = ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            onboarding.approveContractRevisionWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            val control = contractApproval(ready.contractId)
            onboarding.approveContractRevisionWithEvidence(control) shouldBe AdminCommandResult.Rejected(MasterRejection.REVISION_ALREADY_APPROVED, null, replayed = false)
        }

        Then("a body that starts writing without lockedNow() is a defect: IllegalStateException and nothing is stored (rollback); a body that never starts writing is a defect too") {
            listOf<(AdminCommandExecutor.Work) -> String>(
                { work -> work.beginWrites(); "ctr_x#1" },
                { _ -> "ctr_x#1" },
            ).forEach { body ->
                val e = ApprovedCommandFixture.withFingerprint(ApprovalEvidenceFixtures.distinctActors(), AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf("x"))
                shouldThrow<IllegalStateException> {
                    executor.executeApproved(AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, ApprovedCommandFixture.requestOf(e), e, ApprovalEvidenceFixtures.DEMO_WINDOW, listOf("x"), body)
                }
                count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.${e.approvalId}") shouldBe 0
                count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", e.approvalId) shouldBe 0
            }
        }
    }

    Given("C-31 (D-MOPS-008 ③): the revision registrant may not approve with evidence; the Demo seed path keeps its single-actor approval") {
        Then("evidence path: the contract (revision 1) registered by the approver is a stored APPROVER_IS_REVISION_REGISTRANT with a REJECTED evidence row") {
            val ready = readyMerchant(onboarding, approved = false)
            val contract = ContractId(
                succeeded(
                    onboarding.registerContract(
                        RegisterContractCommand(request(actor = ApprovalEvidenceFixtures.APPROVER), ready.businessId, ScopeInput(ready.merchantId), EffectivePeriod(OnboardingFixture.t0)),
                    ),
                ),
            )
            val command = contractApproval(contract)
            onboarding.approveContractRevisionWithEvidence(command) shouldBe
                AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_REVISION_REGISTRANT, null, replayed = false)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "APPROVER_IS_REVISION_REGISTRANT"
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contract.value) shouldBe 0
        }

        Then("seed path (system:demo-seed + Demo mode): the same actor registers and approves the contract — still succeeds") {
            val ready = readyMerchant(onboarding, approved = false)
            val contract = ContractId(
                succeeded(onboarding.registerContract(RegisterContractCommand(seedRequest(), ready.businessId, ScopeInput(ready.merchantId), EffectivePeriod(OnboardingFixture.t0)))),
            )
            succeeded(onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), contract, 1))) shouldBe "${contract.value}#1"
        }
    }

    Given("B2-2-5 / C-26 (D-MOPS-008 ②): the evidence-less use case entries run only for system:demo-seed in Demo mode") {
        Then("other actors (system:other, admin:…, operator-1) and LIVE mode are refused with IllegalStateException before any transaction (no admin_request row)") {
            val ready = readyMerchant(onboarding, approved = false)
            val actors = listOf(
                com.paycore.master.merchant.domain.ActorRef("system:other"), ApprovalEvidenceFixtures.APPROVER, OnboardingFixture.operator,
            )
            actors.forEach { actor ->
                withClue(actor.value) {
                    val key = OnboardingFixture.key()
                    shouldThrow<IllegalStateException> { onboarding.approveContractRevision(ApproveContractRevisionCommand(request(key, actor), ready.contractId, 1)) }
                    shouldThrow<IllegalStateException> {
                        onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand(key).copy(request = request(key, actor)))
                    }
                    count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe 0
                }
            }
            mode.demo = false
            val key = OnboardingFixture.key()
            shouldThrow<IllegalStateException> { onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(key), ready.contractId, 1)) }
            shouldThrow<IllegalStateException> { onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand(key)) }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe 0
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", ready.contractId.value) shouldBe 0
            mode.reset()
            succeeded(onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), ready.contractId, 1))) shouldBe "${ready.contractId.value}#1"
        }
    }

    Given("m-1: the same approvalId under two operations at once on two real MySQL connections") {
        Then("one SUCCEEDED with one EXECUTED evidence row, the other APPROVAL_ID_ALREADY_USED with no row; the loser converged through StoreConstraint(businessKey) — no 500") {
            val ready = readyMerchant(onboarding, approved = false)
            val command = contractApproval(ready.contractId)
            val other = ApprovedCommandFixture.withFingerprint(command.evidence, AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf("race"))
            val contractLocked = CountDownLatch(1)
            val otherPastLookup = CountDownLatch(1)
            val merchantLocks = AtomicInteger()
            // 계약 승인 쪽: merchant 잠금 직후(판정 3 조회 뒤) 다른 operation 쪽이 판정 3을 지날 때까지 기다린다 → 둘 다 "근거 행 없음"을 본 뒤 쓰기로 간다.
            faults.after(MasterFaultPoint.MERCHANT_LOCK) {
                if (merchantLocks.incrementAndGet() == 1) {
                    contractLocked.countDown()
                    otherPastLookup.await(10, TimeUnit.SECONDS)
                }
            }
            val results = OnboardingFixture.concurrently(2) { i ->
                if (i == 0) {
                    onboarding.approveContractRevisionWithEvidence(command)
                } else {
                    executor.executeApproved(
                        AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, ApprovedCommandFixture.requestOf(other), other, ApprovalEvidenceFixtures.DEMO_WINDOW, listOf("race", other.fingerprint),
                    ) { work ->
                        otherPastLookup.countDown()
                        contractLocked.await(10, TimeUnit.SECONDS)
                        work.lockedNow()
                        work.beginWrites()
                        "mid_race"
                    }
                }
            }.map { it.getOrThrow() }
            results.map { r -> (r as? AdminCommandResult.Rejected)?.rejection?.name ?: r::class.simpleName } shouldContainExactlyInAnyOrder
                listOf("Succeeded", "APPROVAL_ID_ALREADY_USED")
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 1
            evidence(command.evidence.approvalId).shouldNotBeNull().outcome shouldBe "EXECUTED"
            faults.failures.map { (point, e) -> point to ((e as? PersistenceFailure.UniqueConflict)?.constraint) } shouldBe
                listOf(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD to StoreConstraint.APPROVAL_EVIDENCE)
        }
    }

    Given("invariant n-a (§9.3) over every approval-operation admin_request result written so far") {
        Then("each has exactly one evidence row with the same request_record_id, except APPROVAL_ID_ALREADY_USED which has none") {
            runtime(container).use { ApprovedCommandFixture.invariantViolations(it) }.shouldBeEmpty()
            // 반대 방향(근거 행 → admin_request): 실행기가 쓴 근거 행은 모두 같은 request_record_id의 mop1. 결과 행이 있다. 같은 컨텍스트의 MasterSchemaSpec이
            // DB 제약 시험용으로 직접 INSERT한 행(evidence_ref = SCHEMA_TEST_EVIDENCE_REF, admin_request 없음)만 예외다 — 그 밖의 고아 근거 행은 0이어야 한다.
            count(
                "SELECT COUNT(*) FROM admin_approval_evidence e WHERE e.evidence_ref <> ? AND NOT EXISTS " +
                    "(SELECT 1 FROM admin_request r WHERE r.request_record_id = e.request_record_id AND r.idempotency_key LIKE 'mop1.%')",
                ApprovedCommandFixture.SCHEMA_TEST_EVIDENCE_REF,
            ) shouldBe 0L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE 'mop1.%'") shouldBe
                count(
                    "SELECT COUNT(*) FROM admin_approval_evidence e WHERE EXISTS " +
                        "(SELECT 1 FROM admin_request r WHERE r.request_record_id = e.request_record_id AND r.idempotency_key LIKE 'mop1.%')",
                ) + count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE 'mop1.%' AND result_code = 'APPROVAL_ID_ALREADY_USED'")
        }
    }
})
