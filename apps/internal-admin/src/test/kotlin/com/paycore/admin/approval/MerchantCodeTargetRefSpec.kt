package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * MERCHANT-CODE-001 MC-3a(§5.1·§5.3·§5.6.4 — rev 0.4.8) 저장값·ref/key 분리·전 작성 지점: MC-3-9·10·11·12(승인 행)·13·15·23. 실제 MySQL 8.4(V15), MASTER는 실제 HTTP 대역.
 * 모든 승인 행은 공식 요청 경로(Named Fixture — [MerchantOpsApprovalFixture] 등, Fixture Monkey 미사용)로 만든다. "MC-3a 전 형태" 행은 [legacy]가 공식 경로로 만든 행을
 * 배포 전 모양(target_ref = mer_, pinned·스냅샷에 merchantCode 없음)으로 되돌린 **의도된 Fixture**다(MC-3a 배포 경계 — 그 모양의 행이 DB에 남아 있는 상황).
 * 가맹점 코드는 MASTER 대역 상세의 현재 코드 [CODE](Demo 값).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantCodeTargetRefSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    approvals: ApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    merchantRecovery: com.paycore.admin.approval.application.MerchantOpsApprovalService,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val code = MerchantCodeChangeApprovalFixture(base, merchants, path, storage)
    val activation = MerchantActivationApprovalFixture(base, merchants, path, storage)
    val currency = MerchantCurrencyApprovalFixture(base, merchants, path, storage)
    code.install()
    activation.install()
    currency.install()
    afterSpec {
        code.uninstall()
        activation.uninstall()
        currency.uninstall()
    }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    val baseline = root().use { (it.scalar("SELECT IFNULL(MAX(event_id), 0) FROM admin_audit_event") as Number).toLong() }

    /** 승인 행: target_ref | target_merchant_id | unresolved_key | status. */
    fun row(id: String) = root().use {
        it.scalar(
            "SELECT CONCAT(target_ref, '|', IFNULL(target_merchant_id, '-'), '|', IFNULL(unresolved_key, '-'), '|', status) FROM admin_approval_request WHERE approval_id = ?", id,
        ) as String
    }

    /** 이 승인 건의 감사 행: action | target_ref | target_merchant_id(event_id 순). */
    fun audits(id: String) = root().use {
        it.column("SELECT CONCAT(action, '|', IFNULL(target_ref, '-'), '|', IFNULL(target_merchant_id, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id)
    }

    /** 승인 행 없는(approval_id NULL) 이 가맹점 관련 감사 행: action | target_ref | target_merchant_id. */
    fun loose(m: String) = root().use {
        it.column(
            "SELECT CONCAT(action, '|', IFNULL(target_ref, '-'), '|', IFNULL(target_merchant_id, '-')) FROM admin_audit_event " +
                "WHERE approval_id IS NULL AND (target_merchant_id = ? OR target_ref = ?) AND event_id > ? ORDER BY event_id",
            m, m, baseline,
        )
    }

    fun pinned(id: String) = json.readTree(root().use { it.scalar("SELECT pinned_target FROM admin_merchant_ops_command WHERE approval_id = ?", id) as String })
    fun snapshot(id: String) = json.readTree(root().use { it.scalar("SELECT CAST(target_snapshot AS CHAR) FROM admin_approval_request WHERE approval_id = ?", id) as String })

    /**
     * MC-3a 배포 전 모양 Fixture(의도된 상태 — 배포 경계): 공식 경로로 만든 행을 target_ref = mer_(target_merchant_id 그대로 — MC-1b 행), pinned·대상 스냅샷의
     * merchantCode 제거로 되돌린다. 기존 감사 행은 건드리지 않는다.
     */
    fun legacy(id: String) = root().use {
        it.update("UPDATE admin_approval_request SET target_ref = target_merchant_id, target_snapshot = JSON_REMOVE(target_snapshot, '$.merchantCode') WHERE approval_id = ?", id)
        it.update("UPDATE admin_merchant_ops_command SET pinned_target = JSON_REMOVE(pinned_target, '$.merchantCode') WHERE approval_id = ?", id)
    }

    fun due(id: String) = root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
    fun run(id: String) {
        due(id)
        recovery.recoverDue()
    }
    fun reference(m: String) = base.contractOf(m) + "#2"
    fun gen(actorId: String) = storage.load(com.paycore.admin.account.domain.AdminActorId(actorId)).sessionGeneration

    /** 실행 결과 불명(EXECUTION_UNKNOWN) 계약 승인 — (승인 ID, 가맹점 ID). */
    fun unknown(): Pair<String, String> {
        val (id, m) = base.requested()
        base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        return id to m
    }

    Given("MC-3-10 new merchant-target requests (6 kinds with a request path): target_ref = the code at request time, target_merchant_id = mer_, key on mer_") {
        Then("contract · first MID · activation · site review · currency · code change: row, unresolved key, request audit (code + mer_), pinned merchantCode (5 kinds — not the code change)") {
            val made = listOf(base.requested(), base.requestedMid(), activation.requestedActivation(), activation.requestedSiteReview(), currency.requested(), code.requested())
            val groups = listOf("MERCHANT:", "MERCHANT:", "MERCHANT:PM:", "MERCHANT:SITE_REVIEW:", "MERCHANT:CURRENCY:", "MERCHANT:")
            made.map { (id, _) -> row(id) } shouldBe made.zip(groups).map { (p, g) -> "$CODE|${p.second}|$g${p.second}|PENDING" }
            made.map { (id, _) -> audits(id) } shouldBe made.map { (_, m) -> listOf("APPROVAL_REQUESTED|$CODE|$m") }
            made.take(5).map { (id, _) -> listOf(pinned(id).get("merchantCode")?.asString(), snapshot(id).get("merchantCode")?.asString()) } shouldBe List(5) { listOf(CODE, CODE) }
            // 코드 변경 건의 표기 = 요청 시점(옛) 코드(§5.5 — 감사 detail from=;to=는 그대로).
            pinned(made[5].first).has("merchantCode") shouldBe false
        }
        Then("a pre-MC-3a PENDING row of the same merchant and group still holds the key: the new request is 409 DUPLICATE_UNRESOLVED and its refusal audit (no approval row) is mer_ + mer_") {
            val (old, m) = base.requested()
            legacy(old)
            row(old) shouldBe "$m|$m|MERCHANT:$m|PENDING"
            base.detail(m)
            base.request(base.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            loose(m) shouldBe listOf("APPROVAL_DENIED|$m|$m")
        }
        Then("request-stage refusals without an approval row keep mer_ + mer_ (C-32): MASTER 404, advisory not eligible (code change after the contract approval)") {
            val gone = base.newMerchantId()
            base.request(base.operator(), gone) shouldBe ApprovalOutcome.Rejected("MERCHANT_NOT_FOUND")
            val locked = base.newMerchantId().also { code.detail(it, changeable = false) }
            code.request(base.operator(), locked) shouldBe ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY")
            listOf(loose(gone), loose(locked)) shouldBe listOf(listOf("APPROVAL_DENIED|$gone|$gone"), listOf("APPROVAL_DENIED|$locked|$locked"))
        }
        Then("(m-5) a MASTER detail without merchantCode is refused: no approval row, no mer_-labelled audit row (never silently a mer_ target)") {
            val m = base.newMerchantId()
            base.detail(m, merchantCode = null)
            base.request(base.operator(), m) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_merchant_id = ? OR target_ref = ?", m, m) as Number).toLong() } shouldBe 0
            loose(m) shouldBe emptyList()
        }
    }

    Given("MC-3-9 deployment boundary: pre-MC-3a rows (target_ref mer_, pinned without merchantCode) are approved and recovered after MC-3a") {
        Then("a pending pre-MC-3a contract approval: approve → EXECUTED; MASTER read with mer_; new audit rows follow the approval row (mer_ + mer_)") {
            val (id, m) = base.requested()
            legacy(id)
            pinned(id).has("merchantCode") shouldBe false
            val reads = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size
            base.executeReplies[id] = base.succeeded(reference(m))
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            row(id) shouldBe "$m|$m|-|EXECUTED"
            (StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size - reads) shouldBe 1
            audits(id).drop(1) shouldBe listOf("APPROVAL_EXECUTING|$m|$m", "APPROVAL_EXECUTED|$m|$m")
        }
        Then("an EXECUTION_UNKNOWN pre-MC-3a approval: the recovery job resends the stored bytes and closes it EXECUTED (mer_ + mer_ audit)") {
            val (id, m) = unknown()
            legacy(id)
            val before = audits(id).size
            base.executeReplies[id] = base.succeeded(reference(m))
            run(id)
            row(id) shouldBe "$m|$m|-|EXECUTED"
            audits(id).drop(before) shouldBe listOf("APPROVAL_EXECUTED|$m|$m")
        }
    }

    Given("MC-3-11 ref/key split on a code-labelled row") {
        Then("approve uses the merchant key: A-stage MASTER read on mer_, command-row merchant check passes, EXECUTED; the stored body is the mer_-keyed body") {
            val (id, m) = base.requested()
            row(id).substringBefore('|') shouldBe CODE
            val reads = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size
            base.executeReplies[id] = base.succeeded(reference(m))
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            (StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size - reads) shouldBe 1
            row(id) shouldBe "$CODE|$m|-|EXECUTED"
        }
        Then("first MID and code change bodies carry merchantId = mer_ (never the code)") {
            val (mid, m1) = base.requestedMid()
            base.executeReplies[mid] = base.succeeded(base.midOf(m1))
            base.approve(mid) shouldBe ApprovalOutcome.Executed(mid)
            val (change, m2) = code.requested()
            base.executeReplies[change] = base.succeeded(m2)
            base.approve(change) shouldBe ApprovalOutcome.Executed(change)
            listOf(
                StubMasterOpsServer.requestsForPath(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS).filter { base.idOf(it.body) == mid }
                    .map { json.readTree(it.body).get("merchantId").asString() }.distinct(),
                StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANT_CODE_CHANGES).filter { base.idOf(it.body) == change }
                    .map { json.readTree(it.body).get("merchantId").asString() }.distinct(),
            ) shouldBe listOf(listOf(m1), listOf(m2))
            audits(change).last() shouldBe "APPROVAL_EXECUTED|${MerchantCodeChangeApprovalFixture.CURRENT}|$m2"
        }
        Then("the 0-stage separate audit of the single-TX path (ApprovalService) keeps the target on a code row (code + mer_ — no lost target)") {
            val (id, m) = base.requested()
            val approver = base.approver()
            approvals.approve(DecideApproval(id, approver.account.id.value, gen(approver.account.id.value), TestTotp.code(approver.secret), path.context())) shouldBe
                ApprovalOutcome.IntegrityViolation
            audits(id).last() shouldBe "APPROVAL_INTEGRITY_VIOLATION|$CODE|$m"
        }
        Then("the reconciliation finding of an unresolvable code-labelled approval names the merchant key (mer_ — internal record, C-31)") {
            val (id, m) = unknown()
            base.executeReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            run(id)
            root().use {
                it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
            }
            base.deadlineReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            run(id)
            root().use { it.column("SELECT CONCAT(kind, '|', IFNULL(payment_ref, '-')) FROM admin_ops_reconciliation_finding WHERE approval_id = ?", id) } shouldBe
                listOf("OWNER_UNRESOLVABLE|$m")
        }
    }

    Given("MC-3-13 pinned merchantCode is display-only (C-19)") {
        Then("a MASTER re-read where only the code differs is not TARGET_CHANGED: contract approval and site review approval still execute") {
            val (id, m) = base.requested()
            base.detail(m, merchantCode = "shopnew2026")
            base.executeReplies[id] = base.succeeded(reference(m))
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            val (site, s) = activation.requestedSiteReview()
            activation.detail(s, merchantCode = "shopnew2026")
            base.executeReplies[site] = base.succeeded(activation.secondSiteOf(s) + "#1")
            base.approve(site) shouldBe ApprovalOutcome.Executed(site)
        }
    }

    Given("MC-3-23 every audit writer on a code-labelled merchant approval (state transition completes, audit = code + mer_)") {
        Then("withdraw → WITHDRAWN") {
            val operator = base.operator()
            val (id, m) = base.requested(operator)
            approvals.withdraw(WithdrawApproval(id, operator.id.value, storage.load(operator.id).sessionGeneration, path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            row(id) shouldBe "$CODE|$m|-|WITHDRAWN"
            audits(id).last() shouldBe "APPROVAL_WITHDRAWN|$CODE|$m"
        }
        Then("reject → REJECTED") {
            val (id, m) = base.requested()
            val approver = base.approver()
            approvals.reject(DecideApproval(id, approver.account.id.value, gen(approver.account.id.value), TestTotp.code(approver.secret), path.context())) shouldBe
                ApprovalOutcome.Closed("REJECTED")
            row(id) shouldBe "$CODE|$m|-|REJECTED"
            audits(id).last() shouldBe "APPROVAL_REJECTED|$CODE|$m"
        }
        Then("expiry job → EXPIRED (not skipped)") {
            val (id, m) = base.requested()
            root().use { it.update("UPDATE admin_approval_request SET requested_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY, expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE approval_id = ?", id) }
            approvals.expireDue()
            row(id) shouldBe "$CODE|$m|-|EXPIRED"
            audits(id).last() shouldBe "APPROVAL_EXPIRED|$CODE|$m"
        }
        Then("approval-stage refusal APPROVER_IS_REGISTRANT → separate audit, the approval stays PENDING") {
            // APPROVER_NOT_ELIGIBLE은 0단계 holdsNow 선검사로 경쟁 시에만 닿음 — 같은 audited/Refusal→separateAudit 경로인 APPROVER_IS_REGISTRANT로 검증
            val approver = base.approver()
            val m = base.newMerchantId()
            base.detail(m, registeredBy = "admin:" + approver.account.id.value)
            val id = (base.request(base.operator(), m) as ApprovalOutcome.Created).approvalId
            base.approve(id, approver) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            row(id) shouldBe "$CODE|$m|MERCHANT:$m|PENDING"
            audits(id).last() shouldBe "APPROVAL_DENIED|$CODE|$m"
        }
        Then("result EXECUTED (unresolved key released) · OWNER_REJECTED · EXECUTION_UNKNOWN then resolved by the recovery job") {
            val (ok, m1) = base.requested()
            base.executeReplies[ok] = base.succeeded(reference(m1))
            base.approve(ok) shouldBe ApprovalOutcome.Executed(ok)
            val (no, m2) = base.requested()
            base.executeReplies[no] = { Reply(200, StubMasterOpsServer.rejected("REVISION_ALREADY_APPROVED")) }
            base.approve(no) shouldBe ApprovalOutcome.OwnerRejected("REVISION_ALREADY_APPROVED")
            val (later, m3) = unknown()
            base.executeReplies[later] = base.succeeded(reference(m3))
            run(later)
            listOf(row(ok), row(no), row(later)) shouldBe listOf("$CODE|$m1|-|EXECUTED", "$CODE|$m2|-|OWNER_REJECTED", "$CODE|$m3|-|EXECUTED")
            audits(ok).drop(1) shouldBe listOf("APPROVAL_EXECUTING|$CODE|$m1", "APPROVAL_EXECUTED|$CODE|$m1")
            audits(no).last() shouldBe "APPROVAL_OWNER_REJECTED|$CODE|$m2"
            audits(later).drop(1) shouldBe listOf("APPROVAL_EXECUTING|$CODE|$m3", "APPROVAL_EXECUTION_UNKNOWN|$CODE|$m3", "APPROVAL_EXECUTED|$CODE|$m3")
        }
        Then("APPROVAL_RESEND_HALTED (requester lost OPERATOR) by the recovery job") {
            val operator = base.operator()
            val (id, m) = base.requested(operator)
            base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            root().use { it.update("UPDATE admin_account SET active_roles = '' WHERE actor_id = ?", operator.id.value) }
            run(id)
            audits(id).last() shouldBe "APPROVAL_RESEND_HALTED|$CODE|$m"
        }
        Then("applier integrity violation (TX2 finds an execution row of another kind) → separate audit with the code label and mer_ key") {
            val (id, m) = base.requested()
            base.executeReplies[id] = { body ->
                root().use { it.update("UPDATE admin_owner_execution SET fingerprint = CONCAT('acd1', SUBSTRING(fingerprint, 5)) WHERE approval_id = ?", base.idOf(body)) }
                Reply(200, StubMasterOpsServer.succeeded(reference(m)))
            }
            base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTING")
            audits(id).last() shouldBe "APPROVAL_INTEGRITY_VIOLATION|$CODE|$m"
        }
        Then("control: a pre-MC-3a row through the same writers is mer_ + mer_ (MC-3-9 pair) — withdraw and expiry") {
            val operator = base.operator()
            val (w, m1) = base.requested(operator)
            legacy(w)
            approvals.withdraw(WithdrawApproval(w, operator.id.value, storage.load(operator.id).sessionGeneration, path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            val (e, m2) = base.requested()
            legacy(e)
            root().use { it.update("UPDATE admin_approval_request SET requested_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY, expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE approval_id = ?", e) }
            approvals.expireDue()
            listOf(audits(w).last(), audits(e).last()) shouldBe listOf("APPROVAL_WITHDRAWN|$m1|$m1", "APPROVAL_EXPIRED|$m2|$m2")
        }
        Then("(rev 0.4.8 MINOR-1) recovery integrity reports on a code-labelled row: command row missing (column=command), execution kind mismatch (column=execution_kind) → code + mer_") {
            val (missing, m1) = unknown()
            root().use { it.update("DELETE FROM admin_merchant_ops_command WHERE approval_id = ?", missing) }
            run(missing)
            val (mismatch, m2) = unknown()
            root().use { it.update("UPDATE admin_owner_execution SET fingerprint = CONCAT('acd1', SUBSTRING(fingerprint, 5)) WHERE approval_id = ?", mismatch) }
            run(mismatch)
            fun violation(id: String) = root().use {
                it.column(
                    "SELECT CONCAT(actor_kind, '|', detail, '|', IFNULL(target_ref, '-'), '|', IFNULL(target_merchant_id, '-'), '|', IFNULL(target_actor_id, '-')) " +
                        "FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_INTEGRITY_VIOLATION'", id,
                )
            }
            listOf(violation(missing), violation(mismatch)) shouldBe listOf(
                listOf("SYSTEM_JOB|column=command|$CODE|$m1|-"), listOf("SYSTEM_JOB|column=execution_kind|$CODE|$m2|-"),
            )
            // 리뷰 판정 4: 가맹점 회복 경로에서 승인 행 자체가 없으면(column=approval) 대상 열은 모두 NULL(승인 행이 대상 종류를 정한다 — 문자열로 만들지 않음).
            val orphan = "apr_" + base.newMerchantId().removePrefix("mer_")
            merchantRecovery.recover(orphan, java.time.Instant.now())
            violation(orphan) shouldBe listOf("SYSTEM_JOB|column=approval|-|-|-")
        }
    }

    Given("MC-3-12·15 reconciliation queries over everything this spec wrote") {
        Then("no merchant-related audit row (mer_ or code target_ref) and no merchant approval row without target_merchant_id; DB CHECKs agree") {
            root().use {
                listOf(
                    (it.scalar(
                        "SELECT COUNT(*) FROM admin_audit_event WHERE event_id > ? AND target_merchant_id IS NULL " +
                            "AND (REGEXP_LIKE(target_ref, '^mer_[0-9A-HJKMNP-TV-Z]{26}$', 'c') OR REGEXP_LIKE(target_ref, '^[a-z][a-z0-9]{5,13}$', 'c'))",
                        baseline,
                    ) as Number).toLong(),
                    (it.scalar(
                        "SELECT COUNT(*) FROM admin_approval_request WHERE target_kind = 'MERCHANT' AND target_merchant_id IS NULL AND REGEXP_LIKE(target_ref, '^[a-z][a-z0-9]{5,13}$', 'c')",
                    ) as Number).toLong(),
                    (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE event_id > ? AND REGEXP_LIKE(target_ref, '^[a-z][a-z0-9]{5,13}$', 'c')", baseline) as Number).toLong() > 0,
                )
            } shouldBe listOf(0L, 0L, true)
        }
    }
}) {
    companion object {
        /** MASTER 대역 상세의 현재 가맹점 코드(StubMasterOpsServer.detailJson 기본값 — Demo 값). */
        const val CODE = "shopabcd12"
    }
}
