package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.MerchantFixtures
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer

/**
 * MERCHANT-FLOW-001 FLOW-2b(§4A.2 M-1 — 리뷰 NIT-1): `outbox`·`publisher` 없이 만든 [AdminCommandExecutor](이력 전용 모드).
 * - `recordHistory`만 성공한다(변경 이력 1행, `outbox_event` 증가 0).
 * - `record`(Outbox)·`publishEntitlement`(Snapshot)는 결함으로 멈추고 TX 전체가 rollback된다 — `admin_request`·`master_change_history`·`outbox_event`에 남는 행 0.
 */
@MasterDbTest
class HistoryOnlyExecutorSpec(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    ids: MasterIdGenerator,
    clock: MutableMasterClock,
    metrics: MasterOperationalMetrics,
    fingerprints: RequestFingerprintDigester,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val executor = AdminCommandExecutor(transactionManager, requests, changeLog, null, ids, clock, metrics, null, fingerprints)

    fun count(sql: String, arg: String? = null): Long =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
            c.prepareStatement(sql).use { ps -> arg?.let { ps.setString(1, it) }; ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }
        }

    fun counts(aggregateId: String) = listOf(
        count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", aggregateId), count("SELECT COUNT(*) FROM outbox_event"),
    )

    Given("history-only executor") {
        Then("recordHistory writes one change-history row and no outbox row") {
            val id = "probe-" + System.nanoTime()
            val before = counts(id)
            executor.execute(AdminOperation.OPEN_CONTRACT_INTAKE, request(), listOf(id)) { work ->
                work.lockedNow(); work.beginWrites()
                work.recordHistory("ContractIntake", id, "Probe", mapOf("probe" to true))
                id
            }.shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            counts(id) shouldBe listOf(before[0] + 1, before[1])
        }

        Then("record (outbox) and publishEntitlement (snapshot) fail as defects and roll everything back — no admin_request, history or outbox row") {
            listOf<(AdminCommandExecutor.Work, String) -> Unit>(
                { work, id -> work.record("ContractIntake", id, 0, "Probe", mapOf("probe" to true)) },
                { work, id -> work.recordHistory("ContractIntake", id, "Probe", mapOf("probe" to true)); work.publishEntitlement(MerchantFixtures.registered()) },
            ).forEach { write ->
                val id = "probe-" + System.nanoTime()
                val req = request()
                val before = counts(id)
                shouldThrowAny {
                    executor.execute(AdminOperation.OPEN_CONTRACT_INTAKE, req, listOf(id)) { work ->
                        work.lockedNow(); work.beginWrites()
                        write(work, id)
                        id
                    }
                }
                counts(id) shouldBe before
                count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", req.idempotencyKey.value) shouldBe 0
            }
        }
    }
})
