package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.evidenceRow
import com.paycore.master.merchant.application.ApprovedCommandFixture.merchantCodeChange
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.provided.MerchantCodeReconciliationQuery
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterFixture
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.sql.SQLException
import java.time.Instant

/**
 * MERCHANT-CODE-001 §8 MC-1b-3·5·6(실제 MySQL — §3.7.3, D-MCODE-002 Q-MC-7·Q-MC-10): 근거 있는 코드 변경(`ChangeMerchantCode`)의 MASTER 로컬 TX 하나
 * (seq 2 배정 행·merchant version +1·이력/Outbox `MerchantCodeChanged`·`admin_request`·근거 EXECUTED 행). 거절은 저장된 거절 + 근거 REJECTED 행이고 업무 행·이력·Outbox 불변.
 * 결정 고정(Q-MC-10): 승인자 = 원 가맹점 등록자(≠ 요청자)도 성공, 요청자 = 승인자는 기존 `APPROVAL_SAME_ACTOR`. 은퇴 코드 재사용 불가(MC-1b-5), 사후 대사 질의(MC-1b-6).
 */
@MasterDbTest
class MerchantCodeChangeApprovalSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val decisions: ApprovalDecisionUseCase,
    private val reconciliation: MerchantCodeReconciliationQuery,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun code(m: MerchantId, seq: Int = 1): String = text("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = ?", m.value, seq)!!
    fun evidence(id: String) = runtime(container).use { evidenceRow(it, id) }

    data class Rows(val assignments: Long, val version: Long, val history: Long, val outbox: Long)

    fun rows(m: MerchantId) = Rows(
        assignments = count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_id = ?", m.value),
        version = count("SELECT COALESCE((SELECT version FROM merchant WHERE merchant_id = ?), -1)", m.value),
        history = count("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'MerchantCodeChanged' AND aggregate_id = ?", m.value),
        outbox = count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MerchantCodeChanged' AND aggregate_id = ?", m.value),
    )

    /** 계약 승인 전 가맹점(등록자 [by] — 기본 operator). 계약은 등록만(Revision 1 승인 전). */
    val mine = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun registered(by: ActorRef = OnboardingFixture.operator): MerchantId {
        val business = BusinessId(succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Demo Business")))
        return MerchantId(succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(actor = by), business, DisplayName("Demo Merchant"), MerchantCodeFixtures.unique()))))
            .also { mine += it.value }
    }

    fun change(m: MerchantId, newCode: String = MerchantCodeFixtures.unique().value, current: String = code(m), evidence: com.paycore.master.merchant.domain.ApprovalEvidence =
        ApprovalEvidenceFixtures.distinctActors(evidenceRef = ApprovedCommandFixture.MERCHANT_REQUEST)) = merchantCodeChange(m, current, newCode, evidence)

    fun rejected(result: AdminCommandResult): MasterRejection = result.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection

    Given("MC-1b-3 success: one MASTER local TX") {
        Then("seq 2 row (CHANGE, replaces = old, approval_id, reason, locked-now time, approver) + merchant version +1 + history/Outbox (old·new·reason·approvalId) + evidence EXECUTED (mer_)") {
            val m = registered()
            val old = code(m)
            val before = rows(m)
            val command = change(m)
            val result = onboarding.changeMerchantCodeWithEvidence(command)
            result shouldBe AdminCommandResult.Succeeded(m.value, replayed = false)
            text(
                "SELECT CONCAT(merchant_code, '|', replaces_code, '|', source, '|', approval_id, '|', reason_code, '|', assigned_by, '|', CAST(assigned_at AS CHAR)) " +
                    "FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = 2", m.value,
            ) shouldBe "${command.newCode.value}|$old|CHANGE|${command.evidence.approvalId}|TYPO_CORRECTION|${ApprovalEvidenceFixtures.APPROVER.value}|2026-10-09 02:00:00.000"
            rows(m) shouldBe Rows(before.assignments + 1, before.version + 1, 1, 1)
            text(
                "SELECT CONCAT(JSON_UNQUOTE(JSON_EXTRACT(detail, '$.oldCode')), '|', JSON_UNQUOTE(JSON_EXTRACT(detail, '$.newCode')), '|', " +
                    "JSON_UNQUOTE(JSON_EXTRACT(detail, '$.reasonCode')), '|', JSON_UNQUOTE(JSON_EXTRACT(detail, '$.approvalId')), '|', actor_ref) " +
                    "FROM master_change_history WHERE change_type = 'MerchantCodeChanged' AND aggregate_id = ?", m.value,
            ) shouldBe "$old|${command.newCode.value}|TYPO_CORRECTION|${command.evidence.approvalId}|${ApprovalEvidenceFixtures.APPROVER.value}"
            text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.oldCode')) FROM outbox_event WHERE event_type = 'MerchantCodeChanged' AND aggregate_id = ?", m.value) shouldBe old
            val e = evidence(command.evidence.approvalId).shouldNotBeNull()
            listOf(e.operation, e.outcome, e.resultReference, e.decidedVia) shouldBe listOf("ChangeMerchantCode", "EXECUTED", m.value, "EXECUTE")
        }
        Then("the same bytes again replay (replayed = true, rows unchanged)") {
            val m = registered()
            val command = change(m)
            onboarding.changeMerchantCodeWithEvidence(command)
            val after = rows(m)
            onboarding.changeMerchantCodeWithEvidence(command) shouldBe AdminCommandResult.Succeeded(m.value, replayed = true)
            rows(m) shouldBe after
        }
        Then("invariant: every seq 2 row ↔ exactly one EXECUTED ChangeMerchantCode evidence row with the same approval_id (and vice versa); n-a invariant holds") {
            // 공유 시험 DB에는 스키마 시험(MC-1-4)이 root로 직접 넣은 seq 2 행이 있다 — 이 spec이 명령 경로로 만든 가맹점의 행만 본다(반대 방향은 근거 행 전체).
            val placeholders = mine.joinToString(",") { "?" }
            count(
                "SELECT COUNT(*) FROM merchant_code_assignment a WHERE a.assignment_seq = 2 AND a.merchant_id IN ($placeholders) AND (SELECT COUNT(*) FROM admin_approval_evidence e " +
                    "WHERE e.approval_id = a.approval_id AND e.operation = 'ChangeMerchantCode' AND e.outcome = 'EXECUTED') <> 1",
                *mine.toTypedArray(),
            ) shouldBe 0
            (count("SELECT COUNT(*) FROM merchant_code_assignment WHERE assignment_seq = 2 AND merchant_id IN ($placeholders)", *mine.toTypedArray()) > 0) shouldBe true
            count(
                "SELECT COUNT(*) FROM admin_approval_evidence e WHERE e.operation = 'ChangeMerchantCode' AND e.outcome = 'EXECUTED' AND e.evidence_ref <> ? AND " +
                    "(SELECT COUNT(*) FROM merchant_code_assignment a WHERE a.approval_id = e.approval_id AND a.assignment_seq = 2) <> 1",
                ApprovedCommandFixture.SCHEMA_TEST_EVIDENCE_REF,
            ) shouldBe 0
            (count("SELECT COUNT(*) FROM merchant_code_assignment WHERE assignment_seq = 2") > 0) shouldBe true
            runtime(container).use { ApprovedCommandFixture.invariantViolations(it) }.shouldBeEmpty()
        }
    }

    Given("MC-1b-3 rejections: stored rejection + REJECTED evidence row, business rows / history / Outbox unchanged") {
        Then("LOCKED (after contract approval), ALREADY_CHANGED, MISMATCH, RESERVED, TAKEN (another merchant's code), MERCHANT_NOT_FOUND") {
            val approved = readyMerchant(onboarding).merchantId
            val changed = registered().also { onboarding.changeMerchantCodeWithEvidence(change(it)) }
            val mismatch = registered()
            val reserved = registered()
            val taken = registered()
            val other = registered()
            val cases = listOf(
                approved to change(approved),
                changed to change(changed),
                mismatch to change(mismatch, current = "otherabc12"),
                reserved to change(reserved, newCode = "paycore2026"),
                taken to change(taken, newCode = code(other)),
                MasterFixture.merchantId() to merchantCodeChange(MasterFixture.merchantId(), "shopabcd12", "shopabcd99"),
            )
            val before = cases.map { (m, _) -> rows(m) }
            cases.map { (_, c) -> rejected(onboarding.changeMerchantCodeWithEvidence(c)) } shouldBe listOf(
                MasterRejection.MERCHANT_CODE_LOCKED, MasterRejection.MERCHANT_CODE_ALREADY_CHANGED, MasterRejection.MERCHANT_CODE_MISMATCH,
                MasterRejection.MERCHANT_CODE_RESERVED, MasterRejection.MERCHANT_CODE_TAKEN, MasterRejection.MERCHANT_NOT_FOUND,
            )
            cases.mapIndexed { i, (m, _) -> withClue(i) { rows(m) shouldBe before[i] } }
            cases.map { (_, c) -> evidence(c.evidence.approvalId).shouldNotBeNull().let { it.outcome to it.resultReference } }.toSet() shouldBe setOf("REJECTED" to null)
            // 거절 응답에 reference 없음(C-12 — 어느 가맹점이 그 코드를 쓰는지 알리지 않는다).
            (onboarding.changeMerchantCodeWithEvidence(cases[4].second) as AdminCommandResult.Rejected).let { it.reference shouldBe null; it.replayed shouldBe true }
        }
        Then("expired after the lock → APPROVAL_EXPIRED (the hook runs after merchants.lock), no business rows") {
            val m = registered()
            val command = change(m, evidence = ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            val before = rows(m)
            rejected(onboarding.changeMerchantCodeWithEvidence(command)) shouldBe MasterRejection.APPROVAL_EXPIRED
            rows(m) shouldBe before
        }
        Then("deadline confirmation before the deadline is NOT_YET_DUE and never runs the body (no seq 2, no admin_request row)") {
            val m = registered()
            val command = change(m)
            decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.NotYetDue
            rows(m).assignments shouldBe 1
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value) shouldBe 0
        }
    }

    Given("D-MCODE-002 Q-MC-10 decision pinning (requester ≠ approver only)") {
        Then("approvedBy = merchant.created_by (≠ requestedBy) → EXECUTED and one seq 2 row") {
            val m = registered(by = ApprovalEvidenceFixtures.APPROVER)
            text("SELECT created_by FROM merchant WHERE merchant_id = ?", m.value) shouldBe ApprovalEvidenceFixtures.APPROVER.value
            val command = change(m)
            command.evidence.approvedBy shouldBe ApprovalEvidenceFixtures.APPROVER
            onboarding.changeMerchantCodeWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = 2", m.value) shouldBe 1
            evidence(command.evidence.approvalId)!!.outcome shouldBe "EXECUTED"
        }
        Then("control: approvedBy = requestedBy → existing APPROVAL_SAME_ACTOR (no seq 2)") {
            val m = registered()
            val command = change(m, evidence = ApprovalEvidenceFixtures.sameActor())
            rejected(onboarding.changeMerchantCodeWithEvidence(command)) shouldBe MasterRejection.APPROVAL_SAME_ACTOR
            rows(m).assignments shouldBe 1
        }
    }

    Given("MC-1b-5 a retired code is never reused") {
        Then("new merchant with the old code → TAKEN; another merchant changing to it → TAKEN; the same merchant reverting → ALREADY_CHANGED; DB: seq 3 INSERT → 3819") {
            val m = registered()
            val old = code(m)
            onboarding.changeMerchantCodeWithEvidence(change(m)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            val business = BusinessId(succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Demo Business")))
            rejected(onboarding.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Demo Merchant"), MerchantCode(old)))) shouldBe MasterRejection.MERCHANT_CODE_TAKEN
            val other = registered()
            rejected(onboarding.changeMerchantCodeWithEvidence(change(other, newCode = old))) shouldBe MasterRejection.MERCHANT_CODE_TAKEN
            rejected(onboarding.changeMerchantCodeWithEvidence(change(m, newCode = old, current = code(m, 2)))) shouldBe MasterRejection.MERCHANT_CODE_ALREADY_CHANGED
            val error = shouldThrow<SQLException> {
                runtime(container).use { c ->
                    c.prepareStatement(
                        "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                            "VALUES (?, 3, ?, ?, 'CHANGE', ?, 'TYPO_CORRECTION', UTC_TIMESTAMP(3), 'admin:adm_01JCAP0000000000000000APR1')",
                    ).use { ps ->
                        ps.setString(1, m.value); ps.setString(2, MerchantCodeFixtures.unique().value); ps.setString(3, code(m, 2)); ps.setString(4, ApprovalEvidenceFixtures.approvalId())
                        ps.executeUpdate()
                    }
                }
            }
            error.errorCode shouldBe 3819
            // 1회 변경 한도의 DB 방어선은 두 겹이다: ck_mca_seq(seq ∈ {1,2})와 ck_mca_assignment(seq별 필드 조합 — seq 3은 어느 분기에도 맞지 않는다, MySQL은 이쪽을 보고한다).
            // 변이 MC-1b-11(c) "seq CHECK 제거"는 행동이 같아 지므로(등가 변이) seq CHECK 존재를 스키마로 단언한다.
            text(
                "SELECT REPLACE(LOWER(CHECK_CLAUSE), ' ', '') FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'ck_mca_seq'",
            ) shouldBe "(`assignment_seq`in(1,2))"
        }
    }

    Given("MC-1b-6 reconciliation query (history id order = serialization order)") {
        Then("normal flows are not reported; a MerchantCodeChanged history row after the merchant's ContractRevisionApproved row is detected") {
            val normal = registered().also { onboarding.changeMerchantCodeWithEvidence(change(it)) }
            val approvedFirst = readyMerchant(onboarding).merchantId
            reconciliation.changesAfterContractApproval().map { it.value } shouldNotContain normal.value
            reconciliation.changesAfterContractApproval().map { it.value } shouldNotContain approvedFirst.value
            // 위반 순서 재현: 승인 이력 뒤에 변경 이력 행을 직접 넣는다(root — 정상 경로는 판정 9가 막는다).
            MasterDbAccounts.root(container).use { c ->
                c.prepareStatement(
                    "INSERT INTO master_change_history (request_record_id, operation, aggregate_type, aggregate_id, change_type, actor_ref, occurred_at, detail) " +
                        "VALUES (?, 'ChangeMerchantCode', 'Merchant', ?, 'MerchantCodeChanged', 'admin:adm_01JCAP0000000000000000APR1', ?, JSON_OBJECT('merchantId', ?))",
                ).use { ps ->
                    ps.setString(1, java.util.UUID.randomUUID().toString()); ps.setString(2, approvedFirst.value)
                    ps.setObject(3, java.time.LocalDateTime.ofInstant(Instant.parse("2026-10-09T02:00:00Z"), java.time.ZoneOffset.UTC)); ps.setString(4, approvedFirst.value)
                    ps.executeUpdate()
                }
            }
            reconciliation.changesAfterContractApproval().map { it.value } shouldContain approvedFirst.value
            reconciliation.changesAfterContractApproval().map { it.value } shouldNotContain normal.value
        }
    }
})
