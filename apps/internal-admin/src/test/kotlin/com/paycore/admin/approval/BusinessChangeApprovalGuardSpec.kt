package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.approval.application.BusinessChangeApprovalService
import com.paycore.admin.approval.application.OwnerOutcomeApplier
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.BusinessChangeCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.application.required.ReconciliationFindingStore
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.BusinessChangeClient
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalRequest
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalResult
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequestOutcome
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.merchantops.application.provided.BusinessPendingApprovals
import com.paycore.admin.merchantops.application.provided.MerchantApprovalClient
import com.paycore.admin.merchantops.application.provided.MerchantApprovalContract
import com.paycore.admin.merchantops.application.provided.MerchantApprovalPolicy
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BIZ-CRIT-APPROVAL-001 S3 리뷰(MAJOR-T1 ④·⑦, MINOR-2): 실패 주입이 필요한 [BusinessChangeApprovalService] 경로. 실제 MySQL·실제 저장 Port, MASTER는 실제 HTTP 대역.
 * - ④ TX1 commit 결과 불명([OwnerFailureInjectionConfig]): 커밋됐으면 저장 바이트 1회 POST로 수렴(EXECUTED), 커밋 안 됐으면 원 실패 전파·PENDING·POST 없음.
 * - ⑦ 원문 열람 감사 저장 실패 → 503 `AUDIT_UNAVAILABLE`(원문 미반환) — 감사 recorder만 실패를 던지는 대역으로 감싼 서비스 인스턴스(시험 전용, 나머지는 실제 bean).
 * - MINOR-2 제안 요약이 명령 행 Domain 규칙을 어기면(응답 모양 검사를 지난 결함 — 대역 channel) 무결성 위반: 승인 행 없음, 요청 단계 감사, 지표.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, OwnerFailureInjectionConfig::class)
@Suppress("LongParameterList")
class BusinessChangeApprovalGuardSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    requests: BusinessChangeRequests,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    registry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    repository: ApprovalRequestRepository,
    commands: BusinessChangeCommandStore,
    executions: OwnerExecutionRepository,
    findings: ReconciliationFindingStore,
    executor: AdminOwnedCommandExecutor,
    failures: AuthenticationFailureRecorder,
    sessions: AdminSessionQuery,
    recorder: AdminAuditRecorder,
    environment: ApprovalEnvironment,
    metrics: ApprovalMetrics,
    opsMetrics: OwnerOpsMetrics,
    channel: BusinessChangeClient,
    client: MerchantApprovalClient,
    contract: MerchantApprovalContract,
    policy: MerchantApprovalPolicy,
    applier: OwnerOutcomeApplier,
    pending: BusinessPendingApprovals,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = BusinessChangeApprovalFixture(base, requests, owners, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun executes(id: String) = StubMasterOpsServer.requestsForPath(BusinessChangeApprovalFixture.EXECUTE).count { base.idOf(it.body) == id }
    fun rows(b: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", b) as Number).toLong() }
    fun businessAudits(b: String) = root().use { it.column("SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_ref = ? ORDER BY event_id", b) }
    fun integrityCount(check: String) = registry.find("paycore.admin.approval.integrity_violation").tags("check", check).counters().sumOf { it.count() }

    /** 감사 저장 실패 대역(켜 둔 동안 원문 열람 감사만 실패). */
    val failUnmasked = AtomicBoolean(false)
    val failingRecorder = object : AdminAuditRecorder {
        override fun record(record: AuditRecord) {
            if (failUnmasked.get() && record.action == "APPROVAL_PROPOSAL_UNMASKED") throw IllegalStateException("injected audit failure")
            recorder.record(record)
        }
    }

    /** 응답 모양 검사를 지난 결함 제안 요약(중복 항목 이름) — 나머지는 실제 channel. */
    val defectiveChannel = object : BusinessChangeClient by channel {
        override fun propose(request: BusinessChangeProposalRequest): BusinessChangeProposalResult = BusinessChangeProposalResult.Proposed(
            proposalId = f.proposalIdOf(request.businessId), baseVersion = 0, contentDigest = BusinessChangeApprovalFixture.DIGEST,
            changedFields = listOf("displayName", "displayName"), representativeChanged = false, subjectCorrection = false, requestedBy = "admin:" + request.actorId,
            replayed = false,
        )
    }

    fun service(channelOf: BusinessChangeClient = channel, recorderOf: AdminAuditRecorder = recorder) = BusinessChangeApprovalService(
        transactionManager, repository, commands, executions, findings, executor, failures, sessions, recorderOf, environment, metrics, opsMetrics, channelOf, client, contract, policy,
        applier, pending,
    )

    Given("MAJOR-T1 ④ the approval TX1 commit outcome is unknown (connection lost at commit)") {
        Then("committed: converges to the stored bytes — exactly one execution POST, EXECUTED") {
            val (id, b) = f.requested()
            base.executeReplies[id] = f.succeeded(b)
            CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.COMMITTED_THEN_LOST)
            val outcome = try {
                f.approve(id)
            } finally {
                CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.OFF)
            }
            outcome shouldBe ApprovalOutcome.Executed(id)
            listOf(executes(id), status(id)) shouldBe listOf(1, "EXECUTED")
        }
        Then("not committed: the original commit failure propagates, PENDING, no execution row, no POST") {
            val (id, b) = f.requested()
            base.executeReplies[id] = f.succeeded(b)
            CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.NOT_COMMITTED)
            val thrown = try {
                f.approve(id)
                null
            } catch (e: RuntimeException) {
                e
            } finally {
                CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.OFF)
            }
            thrown?.javaClass?.simpleName shouldBe "CommitOutcomeUnknownException"
            listOf(executes(id), status(id)) shouldBe listOf(0, "PENDING")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() } shouldBe 0L
        }
    }

    Given("MAJOR-T1 ⑦ the original-view audit cannot be stored") {
        Then("503 AUDIT_UNAVAILABLE, the content is not returned and no UNMASKED row exists; with a working recorder the same view is Found") {
            val (id, b) = f.requested()
            f.original(b)
            val approver = base.approver().account.id.value
            failUnmasked.set(true)
            val failed = try {
                service(recorderOf = failingRecorder).original(approver, id, path.context())
            } finally {
                failUnmasked.set(false)
            }
            failed shouldBe BusinessChangeOriginalResult.Unavailable("AUDIT_UNAVAILABLE")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_PROPOSAL_UNMASKED'", id) as Number).toLong() } shouldBe 0L
            (service(recorderOf = failingRecorder).original(approver, id, path.context()) is BusinessChangeOriginalResult.Found) shouldBe true
        }
    }

    Given("MINOR-2 a proposal summary that breaks the command-row rules (duplicate field names past the shape check)") {
        Then("integrity violation: no approval or command row, one request-stage APPROVAL_INTEGRITY_VIOLATION audit, metric integrity_violation{check=command_snapshot} +1") {
            val b = f.newBusinessId().also { f.state(it) }
            val before = integrityCount("command_snapshot")
            val operator = base.operator()
            service(channelOf = defectiveChannel).request(
                operator.id.value, storage.load(operator.id).sessionGeneration,
                com.paycore.admin.merchantops.application.provided.BusinessProfileRequest.Change(
                    com.paycore.admin.merchantops.application.provided.BusinessProfileOperation.UPDATE, b, java.util.UUID.randomUUID().toString().replace("-", ""), 0,
                    BusinessChangeApprovalFixture.REASON, f.content(), BusinessChangeApprovalFixture.EVIDENCE,
                ),
                path.context(),
            ) shouldBe BusinessChangeRequestOutcome.IntegrityViolation
            rows(b) shouldBe 0L
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_business_change_command WHERE business_id = ?", b) as Number).toLong() } shouldBe 0L
            businessAudits(b) shouldBe listOf("APPROVAL_INTEGRITY_VIOLATION|FAILED|kind=BUSINESS_CRITICAL_CHANGE;column=command_snapshot")
            (integrityCount("command_snapshot") - before) shouldBe 1.0
        }
    }
})
