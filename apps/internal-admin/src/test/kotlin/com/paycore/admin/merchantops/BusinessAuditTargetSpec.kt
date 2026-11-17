package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.BusinessAudit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.time.Duration

/**
 * BUSINESS-PROFILE-001 BIZ-3 — `BusinessAudit`는 ApprovalAuditTargetSourceSpec 허용 목록의 감사 대상 열 작성 지점이다. 그 조건:
 * 대상 열은 사업자 ID(`biz_<ULID>`)나 null만 쓰고, 가맹점·승인 대상 표기(`mer_`·코드)는 쓰지 않고 감사 실패(false)로 닫는다.
 */
class BusinessAuditTargetSpec : FunSpec({

    val view = AdminSessionView("adm_01JC00000000000000000000V1", "Operator", "ACTIVE", 1, 1, setOf("OPERATOR"))
    val ctx = AdminRequestContext("req-a", "127.0.0.1")

    fun fixture(): Pair<BusinessAudit, MutableList<AuditRecord>> {
        val rows = mutableListOf<AuditRecord>()
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val sessions = object : AdminSessionQuery {
            override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
            override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
        }
        val recorder = object : AdminAuditRecorder {
            override fun record(record: AuditRecord) {
                rows += record
            }
        }
        return BusinessAudit(tx, sessions, recorder) to rows
    }

    test("a business id or null target is written") {
        val (audit, rows) = fixture()
        audit.record(view, ctx, "mos.s1", "BUSINESS_VIEWED", "OK", null, "biz_01JC0000000000000000000001") shouldBe true
        audit.record(view, ctx, "mos.s2", "BUSINESS_SEARCHED", "OK", null, null) shouldBe true
        rows.map { it.targetRef } shouldBe listOf("biz_01JC0000000000000000000001", null)
    }

    test("a merchant or other target is refused fail-closed: no row, audit failure") {
        listOf("mer_01JC0000000000000000000001", "demoshop01", "biz_short", "").forEach { target ->
            val (audit, rows) = fixture()
            audit.record(view, ctx, "mos.s3", "BUSINESS_VIEWED", "OK", null, target) shouldBe false
            rows.shouldBeEmpty()
        }
    }
})
