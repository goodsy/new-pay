package com.paycore.admin.account.application

import com.paycore.admin.account.adapter.integration.MicrometerAdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.Versioned
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.LoginName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/**
 * 활성 역할 gauge 갱신(ADMIN-BOOT-002 리뷰 Minor-1·2 — DB 없는 Application 시험). 시험 대역: 동기화만 하는 TX 관리자(읽기 전용 표시는 Spring이 동기화 상태에 싣는다),
 * 집계만 답하는 계정 저장소, 고정 시계. 지표는 실제 Micrometer adapter + SimpleMeterRegistry.
 */
class ActiveRoleHolderGaugeServiceSpec : BehaviorSpec({

    class RecordingTransactions : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    /** 집계만 답한다 — [failing]이면 DB 오류 대역(예외). 호출 시점의 TX 읽기 전용 표시를 기록한다. */
    class CountingAccounts(var counts: Map<AdminRole, Int>) : AdminAccountRepository {
        var failing = false
        val readOnlyFlags = mutableListOf<Boolean>()
        override fun countActiveHolders(role: AdminRole): Int {
            readOnlyFlags += TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            if (failing) throw IllegalStateException("db down")
            return counts.getValue(role)
        }
        override fun find(id: AdminActorId): Versioned<AdminAccount>? = error("unused")
        override fun findByLoginName(loginName: LoginName): Versioned<AdminAccount>? = error("unused")
        override fun lock(id: AdminActorId): Versioned<AdminAccount>? = error("unused")
        override fun lockAll(ids: Collection<AdminActorId>): Map<AdminActorId, Versioned<AdminAccount>> = error("unused")
        override fun insert(account: AdminAccount): Versioned<AdminAccount> = error("unused")
        override fun anyExists(): Boolean = error("unused")
        override fun update(current: Versioned<AdminAccount>, next: AdminAccount): Versioned<AdminAccount> = error("unused")
    }

    class FixedClock(var now: Instant) : AdminClock {
        override fun now(): Instant = now
    }

    val start = Instant.parse("2026-10-30T00:00:00Z")
    fun fixture(): Triple<ActiveRoleHolderGaugeService, Pair<CountingAccounts, FixedClock>, SimpleMeterRegistry> {
        val registry = SimpleMeterRegistry()
        val clock = FixedClock(start)
        val accounts = CountingAccounts(mapOf(AdminRole.SECURITY_ADMIN to 2, AdminRole.APPROVER to 2))
        return Triple(ActiveRoleHolderGaugeService(RecordingTransactions(), accounts, MicrometerAdminAccountMetrics(registry, clock), clock), accounts to clock, registry)
    }
    fun gauge(registry: SimpleMeterRegistry, role: String) = registry.find(MicrometerAdminAccountMetrics.ACTIVE_ROLE_HOLDERS).tag("role", role).gauge()?.value()
    fun refreshes(registry: SimpleMeterRegistry, result: String) = registry.find(MicrometerAdminAccountMetrics.ACTIVE_ROLE_REFRESH).tag("result", result).counter()?.count() ?: 0.0
    fun age(registry: SimpleMeterRegistry) = registry.find(MicrometerAdminAccountMetrics.ACTIVE_ROLE_REFRESH_AGE).gauge()?.value()

    Given("Minor-1: the refresh transaction") {
        Then("is read-only for every count") {
            val (service, deps, _) = fixture()
            service.refresh()
            deps.first.readOnlyFlags shouldBe listOf(true, true)
        }
    }

    Given("Minor-2: refresh success and failure") {
        Then("success sets the role gauges, counts SUCCESS and starts the age gauge at the success time (age measured at scrape time)") {
            val (service, deps, registry) = fixture()
            val clock = deps.second
            service.refresh() shouldBe mapOf("SECURITY_ADMIN" to 2, "APPROVER" to 2)
            listOf(gauge(registry, "SECURITY_ADMIN"), gauge(registry, "APPROVER"), refreshes(registry, "SUCCESS"), refreshes(registry, "FAILURE"), age(registry)) shouldBe
                listOf(2.0, 2.0, 1.0, 0.0, 0.0)
            clock.now = start.plusSeconds(90)
            age(registry) shouldBe 90.0
        }
        Then("a failed refresh keeps the previous role values, counts FAILURE, and the age keeps growing from the last success (stall alert)") {
            val (service, deps, registry) = fixture()
            val (accounts, clock) = deps
            service.refresh()
            accounts.counts = mapOf(AdminRole.SECURITY_ADMIN to 1, AdminRole.APPROVER to 1)
            accounts.failing = true
            clock.now = start.plusSeconds(60)
            shouldThrow<IllegalStateException> { service.refresh() }
            clock.now = start.plusSeconds(200)
            shouldThrow<IllegalStateException> { service.refresh() }
            listOf(gauge(registry, "SECURITY_ADMIN"), gauge(registry, "APPROVER"), refreshes(registry, "SUCCESS"), refreshes(registry, "FAILURE"), age(registry)) shouldBe
                listOf(2.0, 2.0, 1.0, 2.0, 200.0)
            accounts.failing = false
            service.refresh() shouldBe mapOf("SECURITY_ADMIN" to 1, "APPROVER" to 1)
            listOf(gauge(registry, "SECURITY_ADMIN"), refreshes(registry, "SUCCESS"), age(registry)) shouldBe listOf(1.0, 2.0, 0.0)
        }
    }
})
