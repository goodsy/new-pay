package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * 격리 수준(검토 #3 n-4, ARCHITECTURE-CONVENTIONS §9): runtime DataSource 연결 기본값은 MySQL 기본 REPEATABLE READ이고, Use Case TX(`LocalTransactions`)는 READ COMMITTED로
 * 연다. 잠금 읽기(`PessimisticRowLock.lockRow`)는 두 격리 수준 모두에서 최신 커밋 값을 돌려준다 — REPEATABLE READ(시험 `TransactionTemplate` 기본)에서의 증명은
 * `LockReadFreshnessSpec`이다(이미 잠근 행을 다시 읽지 않는 이유).
 */
@PaymentDbTest
class TransactionIsolationSpec(
    private val dataSource: DataSource,
    private val create: CreatePaymentUseCase,
    private val faults: FaultInjector,
) : BehaviorSpec({

    afterEach { faults.reset() }

    Given("the runtime DataSource and a use case transaction") {
        Then("the pool connection default is REPEATABLE READ and the use case transaction runs READ COMMITTED") {
            dataSource.connection.use { it.transactionIsolation } shouldBe Connection.TRANSACTION_REPEATABLE_READ
            var observed: Int? = null
            faults.after(FaultPoint.PAYMENT_INSERT) { observed = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() }
            create.create(command(newOrder()))
            observed shouldBe Connection.TRANSACTION_READ_COMMITTED
        }
    }
})
