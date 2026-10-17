package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.intent
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.CreatePaymentFixture.newKey
import com.paycore.payment.payment.application.CreatePaymentFixture.rows
import com.paycore.payment.payment.application.CreatePaymentFixture.rowsOf
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.domain.OrderKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.persistence.EntityManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실제 MySQL 8.4 **다중 connection** 경합(Mock 아님). 각 요청은 자기 스레드·자기 DB 연결·자기 로컬 TX다. 경합이 실제로 일어났다는
 * 관찰 증거로 InnoDB 잠금 대기 트랜잭션 수(`performance_schema.data_lock_waits`, [DbAccess.lockWaitingTransactions])를 확인한 뒤 먼저 들어간 TX를 놓아 준다.
 * 초안 CP-03(같은 키 동시)·CP-04(다른 키 같은 주문 동시·최초 control insert 경쟁)·CP-10(교착·잠금 timeout·연결 오류 분리).
 */
@PaymentDbTest
class CreatePaymentConcurrencySpec(
    private val service: CreatePaymentUseCase,
    private val faults: FaultInjector,
    private val eligibility: RecordingEligibilityPort,
    private val entityManager: EntityManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val n = 8

    /** [point]에서 조건을 만족한 첫 TX를, 나머지 [waiters]개 TX가 실제 DB 잠금 대기에 들어갈 때까지 붙잡는다. */
    fun holdFirstUntilWaiters(point: FaultPoint, waiters: Int, isWinner: (Any?) -> Boolean) {
        val held = AtomicBoolean(false)
        faults.after(point) { result ->
            if (isWinner(result) && held.compareAndSet(false, true)) db.awaitLockWaiters(waiters)
        }
    }

    fun deadlocks(): Long =
        db.withRoot { c -> with(db) { (c.scalar("SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'") as Number).toLong() } }

    fun accepted(results: List<Result<CreatePaymentResult>>) = results.map { it.getOrThrow().shouldBeInstanceOf<CreatePaymentResult.Accepted>() }

    Given("CP-03: the same key with the same content is sent by $n connections at once") {
        Then("exactly one creates; the others wait on the idempotency row and replay the same result") {
            val order = newOrder()
            val cmd = command(order)
            holdFirstUntilWaiters(FaultPoint.IDEMPOTENCY_RESERVE, n - 1) { it == IdempotencyReservationOutcome.Reserved }

            val results = accepted(concurrently(n) { service.create(cmd.copy(newIdentifiers = newIdentifiers())) })

            results.count { !it.replayed } shouldBe 1
            results.map { it.payment.paymentId }.toSet() shouldHaveSize 1
            results.map { it.outcome }.toSet() shouldBe setOf(CreatePaymentOutcome.CREATED)
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("CP-03: the same key with different contents is sent by $n connections at once") {
        Then("the first committed content wins; every other request is IdempotencyKeyReused and creates nothing") {
            val order = newOrder()
            val key = newKey()
            holdFirstUntilWaiters(FaultPoint.IDEMPOTENCY_RESERVE, n - 1) { it == IdempotencyReservationOutcome.Reserved }

            val results = concurrently(n) { i -> service.create(command(order, key = key, orderName = "name-$i")) }
                .map { it.getOrThrow() }

            results.filterIsInstance<CreatePaymentResult.Accepted>().single().outcome shouldBe CreatePaymentOutcome.CREATED
            results.count { it == CreatePaymentResult.IdempotencyKeyReused } shouldBe n - 1
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("CP-04: $n different keys for the same new order with the same fixed input at once (first control insert race)") {
        Then("one creates; the others wait on the control row and converge to the same active payment, each key recorded") {
            val order = newOrder()
            holdFirstUntilWaiters(FaultPoint.CONTROL_OPEN, n - 1) { it is ControlAcquisition.Opened }

            val results = accepted(concurrently(n) { i -> service.create(command(order, orderName = "display-$i")) })

            results.count { it.outcome == CreatePaymentOutcome.CREATED } shouldBe 1
            results.count { it.outcome == CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT } shouldBe n - 1
            results.map { it.payment.paymentId }.toSet() shouldHaveSize 1
            results.none { it.replayed } shouldBe true
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = n.toLong(), outbox = 1)
        }
    }

    Given("CP-04: $n different keys for the same new order with different amounts at once") {
        Then("exactly one payment exists; every other request is FIXED_INTENT_CONFLICT referencing it") {
            val order = newOrder()
            holdFirstUntilWaiters(FaultPoint.CONTROL_OPEN, n - 1) { it is ControlAcquisition.Opened }

            val results = concurrently(n) { i -> service.create(command(order, intent(amount = 10_000L + i))) }
                .map { it.getOrThrow() }

            val created = results.filterIsInstance<CreatePaymentResult.Accepted>().single()
            created.outcome shouldBe CreatePaymentOutcome.CREATED
            val rejected = results.filterIsInstance<CreatePaymentResult.Rejected>()
            rejected shouldHaveSize n - 1
            rejected.all { it.rejection == CreatePaymentRejection.FIXED_INTENT_CONFLICT && it.referencePaymentId == created.payment.paymentId } shouldBe
                true
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = n.toLong(), outbox = 1)
        }
    }

    Given("a mixed workload under the fixed lock order (idempotency record -> control -> payment)") {
        Then("16 concurrent requests over 2 orders with shared and distinct keys converge with no failures") {
            val orders = listOf(newOrder(), newOrder())
            val shared = orders.associateWith { listOf(command(it), command(it)) }
            val tasks = orders.flatMap { o ->
                val (k1, k2) = shared.getValue(o)
                listOf(k1, k1, k1, k2, k2) + List(3) { command(o) }
            }
            val before = deadlocks()

            val results = accepted(concurrently(tasks.size) { i -> service.create(tasks[i].copy(newIdentifiers = newIdentifiers())) })

            orders.forEach { o ->
                val mine = results.filterIndexed { i, _ -> tasks[i].orderKey == o }
                mine.map { it.payment.paymentId }.toSet() shouldHaveSize 1
                mine.count { it.outcome == CreatePaymentOutcome.CREATED && !it.replayed } shouldBe 1
                db.rowsOf(o) shouldBe rows(payment = 1, control = 1, idempotency = 5, outbox = 1)
            }
            // 교착이 생겼다면 서비스가 rollback 확인 후 재시도했다(실패 결과 없음). 관찰 값은 보고용으로 남긴다.
            println("mixed-workload InnoDB lock_deadlocks delta = ${deadlocks() - before}")
        }
    }

    Given("CP-10: a deadlock between a CreatePayment and a transaction that locks in the reverse order (control -> idempotency)") {
        Then("InnoDB rolls the CreatePayment back, the service retries the same intent in a new transaction and converges") {
            // 만료(30일)된 같은 키 재요청: CreatePayment는 기존 멱등 행을 잠근 채(갱신) control을 기다린다. 행이 계속 존재하므로
            // 재시도 TX도 같은 행을 기다리게 되어 결과가 결정적이다.
            val order = newOrder()
            val original = command(order)
            service.create(original).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            val cmd = original.copy(requestedAt = original.requestedAt.plus(Duration.ofDays(30)), newIdentifiers = newIdentifiers())

            val before = deadlocks()

            db.app().use { reverse ->
                reverse.autoCommit = false
                with(db) {
                    reverse.update("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED")
                    reverse.update("SET SESSION innodb_lock_wait_timeout = 30")
                    lockControl(db, reverse, order)
                    // 역순 TX를 무겁게(undo 많게) 만들어 InnoDB가 가벼운 CreatePayment TX를 교착 희생자로 고르게 한다.
                    val heavyMid = unique("heavy")
                    repeat(40) { reverse.update(INSERT_PENDING, heavyMid, "k-$it", "rec-$it") }
                }
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val future = pool.submit<CreatePaymentResult> { service.create(cmd) }
                    db.awaitLockWaiters(1) // CreatePayment가 멱등 행을 쥔 채 control 잠금을 기다린다.
                    with(db) {
                        // 역순: control을 쥔 TX가 CreatePayment가 쥔 멱등 행을 잠그려 한다 → 순환 대기 → 교착 감지.
                        reverse.rows(
                            "SELECT id FROM idempotency_request WHERE environment = ? AND merchant_account_id = ? AND operation = 'CreatePayment' " +
                                "AND idempotency_key = ? FOR UPDATE",
                            order.environment.name, order.merchantAccountId.value, cmd.idempotencyKey.value,
                        ) shouldHaveSize 1
                    }
                    db.awaitLockWaiters(1) // 재시도한 새 TX가 이번에는 멱등 행에서 기다린다.
                    reverse.rollback()
                    val result = future.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
                    // AUTHN-001(D-AUTHN-005·U-3): 30일 된 REQUESTED는 결제창 시각이 지나 같은 TX에서 만료 → generation 2(이전 기대: EXISTING_ACTIVE_PAYMENT, §24.12).
                    result.outcome shouldBe CreatePaymentOutcome.CREATED
                    result.replayed shouldBe false
                } finally {
                    pool.shutdownNow()
                }
            }
            // 원 요청 1 + 교착으로 rollback된 TX 1 + 재시도 TX 1(재시도 TX마다 그 TX 안에서 다시 평가한다).
            eligibility.callsFor(order).size shouldBe 3
            (deadlocks() - before) shouldBeGreaterThanOrEqual 1L
            // 이전 기대: payment 1·outbox 1(§24.12). 재시도 TX만 커밋 — 만료 1회(PaymentExpired 1) + 새 Payment 1(PaymentRequested 1).
            db.rowsOf(order) shouldBe rows(payment = 2, control = 1, idempotency = 1, outbox = 3)
            db.count(
                "SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key = ? AND request_record_id = ?",
                cmd.idempotencyKey.value, cmd.newIdentifiers.idempotencyRecordId.value,
            ) shouldBe 1
        }
    }

    Given("CP-10: the same key is still being processed by an uncommitted transaction") {
        Then("the waiting request times out as RequestInProgress (not a conflict) and stores nothing; it succeeds after") {
            val order = newOrder()
            val cmd = command(order)
            db.app().use { other ->
                other.autoCommit = false
                with(db) {
                    other.update(INSERT_PENDING_SCOPE, order.environment.name, order.merchantAccountId.value, cmd.idempotencyKey.value)
                }
                val started = System.nanoTime()
                shouldThrow<CreatePaymentFailure.RequestInProgress> { service.create(cmd) }
                Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThanOrEqual 7_500
                other.rollback()
            }
            db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>().outcome shouldBe CreatePaymentOutcome.CREATED
        }
    }

    Given("CP-10: another transaction holds the order control longer than the lock wait timeout") {
        Then("the request is TemporarilyUnavailable, its idempotency reservation is rolled back, and a retry with the same key converges") {
            val order = newOrder()
            val first = service.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            val cmd = command(order)
            db.app().use { other ->
                other.autoCommit = false
                lockControl(db, other, order)
                shouldThrow<CreatePaymentFailure.TemporarilyUnavailable> { service.create(cmd) }
                other.rollback()
            }
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
            val retried = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            retried.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
            retried.payment.paymentId shouldBe first.payment.paymentId
        }
    }

    Given("CP-10: the database connection is lost in the middle of the transaction") {
        Then("the request fails as TemporarilyUnavailable, nothing is committed, and the same key later creates exactly once") {
            val order = newOrder()
            val cmd = command(order)
            faults.after(FaultPoint.PAYMENT_INSERT) {
                val connectionId = (entityManager.createNativeQuery("SELECT CONNECTION_ID()").singleResult as Number).toLong()
                db.withRoot { root -> with(db) { root.update("KILL CONNECTION $connectionId") } }
            }
            shouldThrow<CreatePaymentFailure.TemporarilyUnavailable> { service.create(cmd) }
            faults.reset()

            db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            val retried = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            retried.outcome shouldBe CreatePaymentOutcome.CREATED
            retried.replayed shouldBe false
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("CP-10: the connection is lost during commit") {
        Then("the outcome is reported as unknown; re-sending the same key reveals the real state (here: not committed) and creates once") {
            val order = newOrder()
            val cmd = command(order)
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) {
                val connectionId = (entityManager.createNativeQuery("SELECT CONNECTION_ID()").singleResult as Number).toLong()
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            db.withRoot { root -> with(db) { root.update("KILL CONNECTION $connectionId") } }
                        }
                    },
                )
            }
            shouldThrow<CreatePaymentFailure.OutcomeUnknown> { service.create(cmd) }
            faults.reset()

            val again = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            again.outcome shouldBe CreatePaymentOutcome.CREATED
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("after all scenarios") {
        Then("no transaction is left waiting on locks") {
            db.lockWaitingTransactions() shouldBe 0
        }
    }
}) {
    private companion object {
        const val INSERT_PENDING = """
            INSERT INTO idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id,
                request_fingerprint, result_kind, created_at, expires_at, updated_at, version)
            VALUES ('TEST', ?, 'CreatePayment', ?, ?, 'r1:x', 'PENDING', '2026-10-07 00:00:00.000', '2026-11-06 00:00:00.000',
                '2026-10-07 00:00:00.000', 0)
        """

        const val INSERT_PENDING_SCOPE = """
            INSERT INTO idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id,
                request_fingerprint, result_kind, created_at, expires_at, updated_at, version)
            VALUES (?, ?, 'CreatePayment', ?, 'in-flight', 'r1:x', 'PENDING', '2026-10-07 00:00:00.000', '2026-11-06 00:00:00.000',
                '2026-10-07 00:00:00.000', 0)
        """

        fun lockControl(db: DbAccess, connection: Connection, order: OrderKey) {
            with(db) {
                connection.scalar(
                    "SELECT generation FROM order_payment_control WHERE environment = ? AND merchant_account_id = ? AND merchant_order_id = ? FOR UPDATE",
                    order.environment.name, order.merchantAccountId.value, order.merchantOrderId.value,
                )
            }
        }
    }
}
