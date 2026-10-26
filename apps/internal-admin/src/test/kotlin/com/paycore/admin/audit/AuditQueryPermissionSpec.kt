package com.paycore.admin.audit

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.audit.application.provided.AdminAuditQuery
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.audit.application.provided.AuditUnavailable
import com.paycore.admin.audit.application.provided.AuditViewer
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * 감사 조회 권한(m-11, D-ADMIN1-008 — `AuditQueryPermissionSpec`)과 jOOQ Reader(최신순 keyset·UTC 시각·필터). 범위는 서버가 정하고, `AUDIT_VIEWED`를 먼저 커밋한 뒤에만
 * 결과를 돌려준다. 행 준비는 기록 Port(같은 TX 규칙)로 한다.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, SwitchableAuditStoreConfig::class)
class AuditQueryPermissionSpec(
    private val query: AdminAuditQuery,
    private val recorder: AdminAuditRecorder,
    private val auditStore: SwitchableAuditStore,
    private val storage: AdminAccountStorage,
) : BehaviorSpec({

    afterEach { auditStore.failOn(null) }
    val base = Instant.parse("2026-10-25T00:00:00.123Z")
    fun write(actor: String, action: String, at: Instant, target: String? = null) = storage.inTx {
        recorder.record(AuditRecord(at, "ADMIN", actor, "표시명", action, "SUCCESS", "corr-seed", targetActorId = target, detail = "k=v"))
    }

    Given("audit rows by two admins") {
        val viewer = AdminAccountFixture.newActorId().value
        val other = AdminAccountFixture.newActorId().value
        write(viewer, "ACCOUNT_CREATED", base)
        write(other, "ACCOUNT_SUSPENDED", base.plusSeconds(1), target = viewer)
        write(other, "ROLE_REVOKED", base.plusSeconds(2), target = viewer)

        Then("a viewer without the all-audit permission sees only their own rows even when filtering by another actor; AUDIT_VIEWED is recorded first") {
            val result = query.search(AuditViewer(viewer, "조회자", canViewAll = false), AuditFilter(actorId = other), "corr-self", "10.0.0.5")
            result.all { it.actorId == viewer } shouldBe true
            result.first().let { listOf(it.action, it.correlationId, it.detail) } shouldBe
                listOf("AUDIT_VIEWED", "corr-self", "scope=SELF;actor=none;target=none;action=none;range=none;page=first;limit=50")
            result.map { it.action }.toSet() shouldBe setOf("AUDIT_VIEWED", "ACCOUNT_CREATED")
        }
        Then("a viewer with the all-audit permission can filter by actor, target, action and a UTC time range") {
            val all = AuditViewer(AdminAccountFixture.newActorId().value, "보안관리자", canViewAll = true)
            query.search(all, AuditFilter(actorId = other), "corr-all", null).map { it.action } shouldBe listOf("ROLE_REVOKED", "ACCOUNT_SUSPENDED")
            query.search(all, AuditFilter(targetActorId = viewer, action = "ACCOUNT_SUSPENDED"), "corr-all", null).map { it.occurredAt } shouldBe listOf(base.plusSeconds(1))
            query.search(all, AuditFilter(actorId = other, from = base.plusSeconds(1), to = base.plusSeconds(2)), "corr-all", null).map { it.action } shouldBe
                listOf("ACCOUNT_SUSPENDED")
        }
        Then("pages are newest first and continue with the keyset (beforeEventId)") {
            val all = AuditViewer(AdminAccountFixture.newActorId().value, "보안관리자", canViewAll = true)
            val first = query.search(all, AuditFilter(actorId = other, limit = 1), "corr-page", null)
            val second = query.search(all, AuditFilter(actorId = other, limit = 1, beforeEventId = first.single().eventId), "corr-page", null)
            (first + second).map { it.action } shouldBe listOf("ROLE_REVOKED", "ACCOUNT_SUSPENDED")
        }
        Then("when AUDIT_VIEWED cannot be recorded no results are returned") {
            auditStore.failOn { it.action == "AUDIT_VIEWED" }
            shouldThrow<AuditUnavailable> { query.search(AuditViewer(viewer, "조회자", canViewAll = true), AuditFilter(), "corr-fail", null) }
        }
    }

    Given("audit row validation") {
        Then("a correlation id longer than 64 characters is refused before any write") {
            shouldThrow<IllegalArgumentException> {
                storage.inTx { recorder.record(AuditRecord(base, "ADMIN", "adm_x", null, "X", "SUCCESS", UUID.randomUUID().toString().repeat(2))) }
            }
        }
    }
})
