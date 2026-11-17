package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.audit.application.provided.AdminAuditQuery
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.audit.application.provided.AuditViewer
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * MERCHANT-CODE-001 MC-3a(§5.6.4 — C-30) 조회 쪽: MC-3-4(연결 조회 — 옛 코드·새 코드·mer_ 행이 내부 키 하나로), MC-3-16(승인·감사 응답 `targetMerchantId`, 감사 필터 범위 규칙,
 * `AUDIT_VIEWED` 값 없음, 색인 사용 계획). 실제 MySQL 8.4, MASTER 대역. 승인 행은 공식 요청 경로, "MC-3a 전" 감사 행은 그 모양(target_ref = mer_, 내부 키 없음)을 감사 기록기로 쓴 의도된 Fixture.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantTargetQuerySpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    query: ApprovalQuery,
    audits: AdminAuditQuery,
    recorder: AdminAuditRecorder,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val code = MerchantCodeChangeApprovalFixture(base, merchants, path, storage)
    val activation = MerchantActivationApprovalFixture(base, merchants, path, storage)
    code.install()
    activation.install()
    afterSpec {
        code.uninstall()
        activation.uninstall()
    }
    fun root() = AdminDbAccounts.root(container)

    /** MC-3a 전 모양 감사 행(가맹점 상세 조회 — target_ref = mer_, 내부 키 열 없음). */
    fun legacyAudit(m: String) = storage.inTx {
        recorder.record(
            AuditRecord(Instant.now().truncatedTo(ChronoUnit.MILLIS), "ADMIN", "adm_" + "L".repeat(26), "Legacy", "MERCHANT_VIEWED", "OK", "corr-legacy", targetRef = m),
        )
    }

    Given("MC-3-4 one merchant across a code change: a pre-MC-3a mer_ row, rows under the old code and rows under the new code") {
        val operator = base.operator()
        val (change, m) = code.requested(operator)
        legacyAudit(m)
        base.executeReplies[change] = base.succeeded(m)
        base.approve(change) shouldBe ApprovalOutcome.Executed(change)
        activation.detail(m, merchantCode = MerchantCodeChangeApprovalFixture.NEW)
        (activation.requestSiteReview(operator, m) is ApprovalOutcome.Created) shouldBe true
        val admin = AuditViewer("adm_" + "S".repeat(26), "Security", canViewAll = true)

        Then("the link query by the internal key returns every row of the merchant: mer_ (legacy), old code, new code — all with targetMerchantId = mer_") {
            val rows = audits.search(admin, AuditFilter(targetMerchantId = m), "corr-link", null)
            rows.map { it.targetRef }.toSet() shouldBe setOf(m, MerchantCodeChangeApprovalFixture.CURRENT, MerchantCodeChangeApprovalFixture.NEW)
            rows.map { it.targetMerchantId }.toSet() shouldBe setOf(m)
            rows.count { it.targetRef == m } shouldBe 1
        }
        Then("MC-3-16 scope rule unchanged: an OPERATOR (SELF) sees only its own rows inside the merchant filter; AUDIT_VIEWED carries no key value") {
            val self = AuditViewer(operator.id.value, "Operator", canViewAll = false)
            val rows = audits.search(self, AuditFilter(targetMerchantId = m), "corr-self", null)
            (rows.isNotEmpty() && rows.all { it.actorId == operator.id.value && it.targetMerchantId == m }) shouldBe true
            root().use { it.column("SELECT detail FROM admin_audit_event WHERE action = 'AUDIT_VIEWED' AND correlation_id IN ('corr-link', 'corr-self') ORDER BY event_id") }
                .map { listOf(it.endsWith(";targetMerchant=1"), it.contains("mer_"), it.contains(m)) } shouldBe List(2) { listOf(true, false, false) }
        }
        Then("MC-3-16 filter format: anything but mer_ + ULID is refused before any read (codes are resolved by MASTER, never by this column — C-30)") {
            listOf("shopabcd12", "mer_lowercase00000000000000", "biz_01JC0000000000000000000001", "").forEach { bad ->
                shouldThrow<IllegalArgumentException> { AuditFilter(targetMerchantId = bad) }
            }
        }
    }

    Given("MC-3-16 approval responses carry targetMerchantId") {
        Then("merchant target = mer_ for a new code-labelled row and a pre-MC-3a row; an account target = null") {
            val approver = base.approver()
            val (fresh, m1) = base.requested()
            val (old, m2) = base.requested()
            root().use { it.update("UPDATE admin_approval_request SET target_ref = target_merchant_id WHERE approval_id = ?", old) }
            val account = path.request("ROLE_GRANT", storage.save(AdminAccountFixture.enrolledActive()).id, grantedRole = "VIEWER")
            listOf(fresh, old, account).map { query.detail(approver.account.id.value, it)?.let { v -> v.targetRef to v.targetMerchantId } } shouldBe listOf(
                MerchantCodeTargetRefSpec.CODE to m1, m2 to m2, null to null,
            )
            query.list(approver.account.id.value, pendingOnly = true)!!.filter { it.approvalId in setOf(fresh, old, account) }.associate { it.approvalId to it.targetMerchantId } shouldBe
                mapOf(fresh to m1, old to m2, account to null)
        }
    }

    Given("MC-3-16 plan: the merchant-key filter uses ix_admin_audit_event_target_merchant (≥ 5 merchants through the official path, ANALYZE TABLE)") {
        Then("EXPLAIN of the reader's shape (target_merchant_key = ? ORDER BY event_id DESC LIMIT 50) picks the index, never a full scan") {
            val made = (1..6).map { base.requested().second }
            val plan = root().use { c ->
                c.column("ANALYZE TABLE admin_audit_event")
                c.prepareStatement("EXPLAIN SELECT event_id, target_ref FROM admin_audit_event WHERE target_merchant_key = ? ORDER BY event_id DESC LIMIT 50").use { ps ->
                    ps.setString(1, made[2])
                    ps.executeQuery().use { rs -> rs.next(); rs.getString("type") to rs.getString("key") }
                }
            }
            plan shouldBe ("ref" to "ix_admin_audit_event_target_merchant")
            root().use { (it.scalar("SELECT COUNT(DISTINCT target_merchant_key) FROM admin_audit_event") as Number).toLong() >= 5 } shouldBe true
        }
    }
})
