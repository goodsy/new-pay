package com.paycore.simulator.van

import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import com.paycore.simulator.support.VanTestClient
import com.paycore.simulator.van.adapter.persistence.JpaVanCancelLedgerStore
import com.paycore.simulator.van.application.provided.CancelAtVanUseCase
import com.paycore.simulator.van.application.provided.InquireCancelAtVanUseCase
import com.paycore.simulator.van.application.provided.VanCancelInquiryRequest
import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.domain.VanApprovedCancels
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanCancelRequest
import com.paycore.simulator.van.domain.VanCancelScope
import com.paycore.simulator.van.domain.VanNamespace
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * Simulator 격리 수준(CANCEL-001 검토 #2 n-3 — PAYMENT `TransactionIsolationSpec`과 같은 방식): runtime 연결 기본값은 MySQL 기본 REPEATABLE READ이고, 가상 VAN 취소·취소
 * 조회 TX는 REPEATABLE READ로 **명시 고정**한다. 승인된 취소 n·Σ는 한 SELECT(m-6)이고 순번 UNIQUE 1062 뒤 재판정은 새 TX(새 스냅샷)라 같은 원 승인의 동시 취소가 잔액을 넘지
 * 않는다(K15). 격리 수준이 바뀌면(설정·기본값 변경) 이 시험이 먼저 깨진다. 관측은 Store를 감싼 시험 대역이 TX 안에서 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class, SimulatorTransactionIsolationSpec.ObservingStoreConfig::class)
class SimulatorTransactionIsolationSpec(
    private val dataSource: DataSource,
    private val cancel: CancelAtVanUseCase,
    private val inquire: InquireCancelAtVanUseCase,
    private val clock: MutableSimulatorClock,
) : BehaviorSpec({

    Given("the runtime DataSource and the virtual VAN cancel transactions") {
        Then("the pool default is REPEATABLE READ and cancel / cancel inquiry transactions are pinned to REPEATABLE READ") {
            dataSource.connection.use { it.transactionIsolation } shouldBe Connection.TRANSACTION_REPEATABLE_READ
            observed.clear()
            val cr = VanTestClient.cancelRequestId(clock.now().minusSeconds(1))
            cancel.cancel(
                "pg-demo",
                VanCancelRequest(
                    requestId = cr, institutionAccount = "SIMMERCH0001", merchantRef = "mid-demo-0001", environment = "TEST", routeRef = "SIMVAN01",
                    originalRequestId = VanTestClient.requestId(clock.now().minusSeconds(5)), originalProviderTransactionId = "SIMPTX-0000000001",
                    originalApprovalNumber = "30000001", originalTransactionDate = LocalDate.parse("2026-10-16"), cancelAmountMinor = 1_000, currency = "KRW",
                    scope = VanCancelScope.FULL,
                ),
            )
            inquire.inquire("pg-demo", VanCancelInquiryRequest(VanTestClient.cancelInquiryId(), cr, "SIMMERCH0001", "TEST", closeIfAbsent = false))
            // 취소 1회 + 조회 1회 — 각 TX 안의 첫 Store 읽기에서 관측.
            observed.toList() shouldBe listOf(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ)
        }
    }
}) {
    /** 운영 Store를 감싸 TX 안의 격리 수준을 기록한다(시험 전용 — 동작은 위임). */
    @TestConfiguration(proxyBeanMethods = false)
    class ObservingStoreConfig {
        @Bean
        @Primary
        fun observingCancelLedgerStore(real: JpaVanCancelLedgerStore): VanCancelLedgerStore = object : VanCancelLedgerStore {
            override fun find(namespace: VanNamespace): VanCancelEntry? = real.find(namespace).also { record() }

            override fun approvedTotals(clientId: String, environment: String, institutionAccount: String, originalRequestId: String): VanApprovedCancels =
                real.approvedTotals(clientId, environment, institutionAccount, originalRequestId)

            override fun insert(entry: VanCancelEntry) = real.insert(entry)

            private fun record() {
                observed += TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()
            }
        }
    }

    companion object {
        val observed = CopyOnWriteArrayList<Int?>()
    }
}
