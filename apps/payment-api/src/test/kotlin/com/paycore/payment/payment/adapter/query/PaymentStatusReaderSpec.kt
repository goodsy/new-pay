package com.paycore.payment.payment.adapter.query

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.SnapshotProbe
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.PaymentStatusViews
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.provided.PaymentStatusQuery
import com.paycore.payment.payment.application.provided.PaymentStatusResult
import com.paycore.payment.payment.application.provided.PaymentViewStatus
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PaymentStatusRow
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * 결제 상태 조회 adapter(CP-QUERY-001 S2 — §6.1·§6.2·§6.3, §9 T3·C-1·C-2·C-3·Z-1)를 실 MySQL 8.4.11(runtime 계정·표 단위 권한)·운영 confirm/cancel 경로로 만든 상태에서 시험한다.
 * 기대값은 명세 표에서 직접 적는다. 상태는 공식 경로(Named Fixture `ConfirmFixture`·`CancelFixture`)로만 만든다.
 */
@PaymentApiTest
class PaymentStatusReaderSpec(
    private val reader: JooqPaymentStatusReader,
    private val query: PaymentStatusQuery,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val confirm: ConfirmPaymentUseCase,
    private val van: ProgrammableVan,
    private val faults: FaultInjector,
    private val db: DbAccess,
    private val dataSource: DataSource,
    private val clock: MutablePaymentClock,
    private val probe: SnapshotProbe,
    admission: ScriptedAdmissionPort,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)

    afterEach {
        faults.reset()
        clock.reset()
        probe.disarm()
    }

    fun own(a: CancelFixture.Authorized): PaymentStatusRow? = reader.find(a.prepared.order.environment, a.mid, a.paymentId)
    fun own(p: ConfirmFixture.Prepared): PaymentStatusRow? = reader.find(p.order.environment, p.order.merchantAccountId, p.paymentId)
    fun cancelled(r: CancelPaymentResult) = r.shouldBeInstanceOf<CancelPaymentResult.Accepted>().view

    /**
     * 저장 UTC 벽시계 원문: root 연결에서 `CAST(col AS CHAR)`(`yyyy-MM-dd HH:mm:ss.SSS`)로 읽어 UTC로 해석한다 — Reader의 `DATE_FORMAT` 방식과 독립된 기대값(시간대 해석 없음).
     * [column]·[table]은 시험 상수만 넣는다.
     */
    fun storedUtc(column: String, table: String, keyColumn: String, key: String, extra: String = ""): Instant = db.withRoot { c ->
        with(db) {
            val raw = c.scalar("SELECT CAST($column AS CHAR) FROM ${db.databaseName}.$table WHERE $keyColumn = ? $extra", key) as String
            java.time.LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC)
        }
    }

    fun <T> withJvmTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        try {
            ZoneId.systemDefault() shouldNotBe ZoneId.of("UTC")
            return block()
        } finally {
            TimeZone.setDefault(original)
            dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        }
    }

    Given("the owner's scope (T3 — CQ-02)") {
        Then("the owner reads it; another MID, the other environment and an unknown id read nothing (scope enforced in SQL)") {
            val a = fixture.authorized()
            val row = own(a)!!
            row.paymentId shouldBe a.paymentId
            row.status shouldBe PaymentStatus.AUTHORIZED
            row.money shouldBe a.money
            row.merchantOrderId shouldBe a.prepared.order.merchantOrderId
            row.authorization?.status shouldBe AuthorizationStatus.AUTHORIZED
            row.authorization?.authorizationId?.value shouldBe a.authorizationId
            row.authorization?.approved?.money shouldBe a.money
            row.balance shouldBe null
            row.cancellations.shouldBeEmpty()
            row.activeHold shouldBe false
            val other = if (a.prepared.order.environment == Environment.TEST) Environment.LIVE else Environment.TEST
            reader.find(other, a.mid, a.paymentId) shouldBe null
            reader.find(a.prepared.order.environment, MerchantAccountId(a.mid.value + "-other"), a.paymentId) shouldBe null
            reader.find(a.prepared.order.environment, a.mid, PaymentId("pay_01NOSUCHPAYMENT0000000000")) shouldBe null
            // 다른 MID의 Payment도 같은 null(존재 비노출 — 소유자 MID로 다른 사람 Payment를 지목).
            val b = fixture.authorized()
            reader.find(a.prepared.order.environment, a.mid, b.paymentId) shouldBe null
        }
    }

    Given("payments in each summary state made by the operational paths") {
        Then("REQUESTED has no authorization; a declined confirm is FAILED with its evidence; a pending confirm is UNKNOWN") {
            val requested = confirmFixture.prepared()
            own(requested)!!.let {
                it.status shouldBe PaymentStatus.REQUESTED
                it.authorization shouldBe null
            }
            val declined = confirmFixture.prepared()
            van.onAuth(declined.order.merchantAccountId.value) { van.decline(it) }
            confirm.confirm(declined.command())
            own(declined)!!.let {
                it.status shouldBe PaymentStatus.FAILED
                it.authorization?.status shouldBe AuthorizationStatus.FAILED
                it.authorization?.failureEvidence shouldBe TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
                it.authorization?.approved shouldBe null
            }
            val pending = confirmFixture.prepared()
            van.onAuth(pending.order.merchantAccountId.value) { van.pending(it) }
            confirm.confirm(pending.command())
            own(pending)!!.let {
                it.status shouldBe PaymentStatus.UNKNOWN
                it.authorization?.status shouldBe AuthorizationStatus.UNKNOWN
            }
            listOf(requested, declined, pending).map { (query.find(it.order.environment, it.order.merchantAccountId, it.paymentId.value) as PaymentStatusResult.Found).view.status } shouldBe
                listOf(PaymentViewStatus.REQUESTED, PaymentViewStatus.FAILED, PaymentViewStatus.UNKNOWN)
        }
    }

    Given("an authorized Payment with an operator correction cancel, a merchant cancel and a declined merchant cancel (D-CPQ-002)") {
        Then("every cancel is read with its initiator, the CANCEL transaction of completed ones, and the return balance totals of the same snapshot") {
            val a = fixture.authorized()
            // 요청 순서가 시각으로 드러나게 시계를 1초씩 민다(시험 시계는 고정 — 같은 시각이면 순서는 cancellationId 순, §6.2).
            clock.set(ConfirmFixture.T0.plusSeconds(1))
            val op = operator.cancel(a.operatorCommand(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>().view
            clock.set(ConfirmFixture.T0.plusSeconds(2))
            val merchant = cancelled(cancel.cancel(a.command(money = Money(3_000, a.money.currency))))
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            clock.set(ConfirmFixture.T0.plusSeconds(3))
            val declined = cancelled(cancel.cancel(a.command(money = Money(2_000, a.money.currency))))
            val row = own(a)!!
            row.cancellations.map { it.cancellationId } shouldBe listOf(op.cancellationId, merchant.cancellationId, declined.cancellationId)
            row.cancellations.map { it.byOperator } shouldBe listOf(true, false, false)
            row.cancellations[0].merchantCancelId shouldBe null
            row.cancellations.map { it.status } shouldBe listOf(CancellationStatus.COMPLETED, CancellationStatus.COMPLETED, CancellationStatus.FAILED)
            row.cancellations[1].cancelTransactionId shouldBe merchant.cancelTransactionId
            row.cancellations[1].confirmedAt shouldBe merchant.cancelledAt
            row.cancellations[2].failureEvidence shouldBe TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
            row.balance?.let { listOf(it.authorizedMinor, it.completedMinor, it.reservedMinor, it.forcedMinor) } shouldBe listOf(10_000L, 4_000L, 0L, 0L)
            val view = (query.find(a.prepared.order.environment, a.mid, a.paymentId.value) as PaymentStatusResult.Found).view
            view.returnSummary?.cancellableMinor shouldBe 6_000
            view.cancellations.size shouldBe 3
        }
    }

    Given("an active incident hold on the Payment (D-CPQ-001)") {
        Then("activeHold is true for that Payment only") {
            val a = fixture.authorized()
            val b = fixture.authorized()
            fixture.holdOn(a)
            own(a)!!.activeHold shouldBe true
            own(b)!!.activeHold shouldBe false
        }
    }

    Given("a read while the approval TX2 has flushed but not committed (C-1, §6.4 flush ≠ commit)") {
        Then("the read sees the whole previous state (IN_PROGRESS, no transaction) and, after commit, the whole new state") {
            val p = confirmFixture.prepared()
            val during = AtomicReference<PaymentStatusRow?>()
            val once = AtomicBoolean(false)
            faults.after(FaultPoint.OUTBOX_APPEND) {
                if (once.compareAndSet(false, true)) during.set(CompletableFuture.supplyAsync { own(p) }.get(10, TimeUnit.SECONDS))
            }
            confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>()
            during.get()!!.let {
                it.status shouldBe PaymentStatus.AUTHORIZING
                it.authorization?.status shouldBe AuthorizationStatus.OPEN
                it.authorization?.approved shouldBe null
                // 그 시점에 읽은 행 그대로 view를 만든다(불변식 위반 없이 IN_PROGRESS).
                PaymentStatusViews.of(it).status shouldBe PaymentViewStatus.IN_PROGRESS
            }
            own(p)!!.authorization?.status shouldBe AuthorizationStatus.AUTHORIZED
        }
        Then("the same holds for a cancel TX2: in flight 3,000 reserved, then completed 3,000") {
            val a = fixture.authorized()
            val during = AtomicReference<PaymentStatusRow?>()
            val once = AtomicBoolean(false)
            faults.after(FaultPoint.OUTBOX_APPEND) {
                if (once.compareAndSet(false, true)) during.set(CompletableFuture.supplyAsync { own(a) }.get(10, TimeUnit.SECONDS))
            }
            cancelled(cancel.cancel(a.command(money = Money(3_000, a.money.currency)))).cancelTransactionId shouldNotBe null
            during.get()!!.let {
                it.cancellations.single().status shouldBe CancellationStatus.OPEN
                it.balance?.let { b -> listOf(b.completedMinor, b.reservedMinor) } shouldBe listOf(0L, 3_000L)
            }
            own(a)!!.let {
                it.cancellations.single().status shouldBe CancellationStatus.COMPLETED
                it.balance?.let { b -> listOf(b.completedMinor, b.reservedMinor) } shouldBe listOf(3_000L, 0L)
            }
        }
    }

    // 검토 #1 m-1: 이력 문장은 본 행 문장의 결과(gate)에 기대지 않고 스스로 범위(environment·MID)를 건다 — 이력 문장만 따로 시험한다.
    Given("the cancellation-history statement on its own (m-1)") {
        Then("it returns the owner's history and nothing for another MID or the other environment") {
            val a = fixture.authorized()
            cancelled(cancel.cancel(a.command(money = Money(1_000, a.money.currency))))
            val env = a.prepared.order.environment
            val other = if (env == Environment.TEST) Environment.LIVE else Environment.TEST
            reader.cancellationHistory(env, a.mid, a.paymentId).size shouldBe 1
            reader.cancellationHistory(env, MerchantAccountId(a.mid.value + "-other"), a.paymentId).shouldBeEmpty()
            reader.cancellationHistory(other, a.mid, a.paymentId).shouldBeEmpty()
        }
    }

    // 검토 #1 m-3: 결정적 스냅샷 증거 — 문장 1이 끝난 직후(같은 TX, 문장 2 전) 다른 연결의 취소 TX2 커밋을 끝까지 기다린다. 문장 2는 커밋 전 이력을 봐야 하고 view 불일치가 없어야 한다.
    Given("a cancel TX2 committed between the reader's two statements (m-3)") {
        Then("the second statement still sees the snapshot of the first (REPEATABLE READ, read-only) and the view is consistent") {
            val a = fixture.authorized()
            val reached = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val once = AtomicBoolean(false)
            faults.after(FaultPoint.OUTBOX_APPEND) {
                if (once.compareAndSet(false, true)) {
                    reached.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "the probe did not release the cancel TX2" }
                }
            }
            val cancelling = CompletableFuture.supplyAsync { cancelled(cancel.cancel(a.command(money = Money(3_000, a.money.currency)))) }
            check(reached.await(20, TimeUnit.SECONDS)) { "the cancel did not reach its TX2" }
            probe.arm("`active_hold`") {
                release.countDown()
                cancelling.get(20, TimeUnit.SECONDS) // 취소 TX2 커밋 완료까지(동기)
            }
            val row = own(a)!!
            probe.observed.get() shouldBe SnapshotProbe.TxState("REPEATABLE-READ", 1)
            cancelling.isDone shouldBe true
            row.cancellations.single().status shouldBe CancellationStatus.OPEN
            row.balance?.let { listOf(it.completedMinor, it.reservedMinor) } shouldBe listOf(0L, 3_000L)
            PaymentStatusViews.of(row).cancellations.single().status shouldBe com.paycore.payment.payment.application.provided.CancellationViewStatus.IN_PROGRESS
            // 다음 조회(새 TX)는 커밋된 결과.
            own(a)!!.cancellations.single().status shouldBe CancellationStatus.COMPLETED
        }
    }

    /**
     * PAYMENT-QUERY-PLAN-FLAKE-001: EXPLAIN 직전에 두 문장이 읽는 표 전부의 InnoDB 영속 통계를 `ANALYZE TABLE`로 다시 계산한다.
     * 원인 — 영속 통계 자동 재계산은 행 10% 변경 뒤 background 스레드가 비동기로(표당 최소 10초 간격) 하므로, 방금 만든 행이 통계에 없을 수 있다
     * (예: `payment_transaction` n_rows=0·`fk_payment_transaction_cancellation` n_diff=0). 그러면 optimizer가 `ct`를 hash join 전체 스캔으로,
     * 이력 정렬을 `uk_payment_cancellation_merchant_key` + filesort로 고른다(게이트 실패 계획과 같음). 통계를 데이터와 맞춰 계획을 결정적으로 만든다 — 단언은 그대로다.
     * 대상 표 목록은 캡처한 문장의 FROM/JOIN 표와 정확히 같아야 한다(문장에 표가 늘면 여기서 실패 — 통계 고정 누락 방지).
     * runtime 계정은 ANALYZE에 필요한 INSERT 권한이 없는 표가 있어(`incident_hold_release` — 1142) 시험 root 연결로 실행한다. 일회용 컨테이너 값이며 운영 DB가 아니다.
     */
    fun analyzeTablesOf(statements: List<String>) {
        val planTables = setOf("payment", "payment_authorization", "payment_transaction", "return_balance", "incident_hold", "incident_hold_release", "payment_cancellation")
        val named = statements.flatMap { sql -> Regex("(?:from|join) `(\\w+)`").findAll(sql).map { it.groupValues[1] }.toList() }.toSet()
        withClue("tables named by the reader's statements") { named shouldBe planTables }
        val results = db.withRoot { c -> with(db) { c.rows("ANALYZE TABLE " + planTables.joinToString { "${db.databaseName}.`$it`" }) } }
        withClue("ANALYZE TABLE result $results") {
            results.map { "${it["Table"]} ${it["Msg_type"]} ${it["Msg_text"]}" }.toSet() shouldBe planTables.map { "${db.databaseName}.$it status OK" }.toSet()
        }
    }

    // P-1(§6.2, 검토 #1 지시 — S2에서 S3로 옮김): 두 문장의 실행 계획 — 모든 표(활성 hold EXISTS·해제 NOT EXISTS 포함)가 인덱스 탐색이고 이력의 ORDER BY는 정렬 없이 인덱스 순서다.
    Given("the execution plans of the reader's statements (P-1)") {
        Then("no full table scan in either statement, including the hold EXISTS subqueries, and no filesort for the history ORDER BY") {
            // PAYMENT-QUERY-PLAN-FLAKE-001 데이터 모양: 다른 MID의 승인·취소된 결제 5건을 먼저 둔다. 표가 1~2행뿐이면 통계가 정확해도 전체 스캔이 인덱스 탐색보다 싸다고
            // 계산되어(측정: 다른 결제 0~1건이면 `t` type=ALL, 2건 이상이면 ref) 계획이 앞선 시험이 남긴 행 수에 좌우된다. 이 시험이 스스로 필요한 양을 만든다.
            repeat(5) { fixture.authorized().let { other -> cancelled(cancel.cancel(other.command(money = Money(1_000, other.money.currency)))) } }
            val a = fixture.authorized()
            cancelled(cancel.cancel(a.command(money = Money(1_000, a.money.currency))))
            fixture.holdOn(a)
            probe.capture()
            own(a)!!.activeHold shouldBe true
            val statements = probe.captured.toList()
            probe.disarm()
            statements.size shouldBe 2
            analyzeTablesOf(statements)
            statements.forEach { sql ->
                val plan = db.withApp { c -> with(db) { c.rows("EXPLAIN $sql") } }
                withClue("$sql\n$plan") {
                    plan.map { it["type"] }.filter { it == "ALL" }.shouldBeEmpty()
                    plan.filter { (it["Extra"] as String?)?.contains("filesort") == true }.shouldBeEmpty()
                }
            }
            // 첫 문장에 hold 표 둘(EXISTS·NOT EXISTS)이 계획에 있다(조건이 계획에서 사라지지 않았음).
            db.withApp { c -> with(db) { c.rows("EXPLAIN ${statements[0]}") } }.map { it["table"] }.let { tables ->
                tables.contains("incident_hold") shouldBe true
                tables.contains("incident_hold_release") shouldBe true
            }
        }
    }

    Given("reads racing with cancel commits (C-2)") {
        Then("every read is one consistent snapshot: the view never reports an inconsistency") {
            val a = fixture.authorized()
            val stop = AtomicBoolean(false)
            val reads = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val readerThread = Thread {
                while (!stop.get()) {
                    try {
                        query.find(a.prepared.order.environment, a.mid, a.paymentId.value).shouldBeInstanceOf<PaymentStatusResult.Found>()
                        reads.incrementAndGet()
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            }.also { it.start() }
            // 검토 #1 m-3: 계획 N = 50(명세 §9 C-2) — 100원 취소 50회 커밋과 경합.
            repeat(50) { cancelled(cancel.cancel(a.command(money = Money(100, a.money.currency)))) }
            stop.set(true)
            readerThread.join(10_000)
            failure.get() shouldBe null
            (reads.get() >= 50) shouldBe true
            own(a)!!.balance?.completedMinor shouldBe 5_000
        }
    }

    Given("other connections holding row locks of the Payment (C-3 — the query takes no locks)") {
        Then("the read returns at once, without waiting for the locks") {
            val a = fixture.authorized()
            cancelled(cancel.cancel(a.command(money = Money(1_000, a.money.currency))))
            db.app().use { c ->
                c.autoCommit = false
                with(db) {
                    c.rows("SELECT * FROM order_payment_control WHERE merchant_account_id = ? FOR UPDATE", a.mid.value)
                    c.rows("SELECT * FROM payment WHERE payment_id = ? FOR UPDATE", a.paymentId.value)
                    c.rows("SELECT * FROM payment_authorization WHERE payment_id = ? FOR UPDATE", a.paymentId.value)
                    c.rows("SELECT * FROM return_balance WHERE payment_id = ? FOR UPDATE", a.paymentId.value)
                    c.rows("SELECT * FROM payment_cancellation WHERE payment_id = ? FOR UPDATE", a.paymentId.value)
                }
                val started = System.nanoTime()
                own(a)!!.balance?.completedMinor shouldBe 1_000
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000) shouldBe true
                c.rollback()
            }
        }
    }

    Given("a JVM whose default time zone is Asia/Seoul (Z-1 — AC 3, §6.3, TZ-FIX-001)") {
        Then("every time is the stored UTC wall clock (not shifted by 9 hours) and equals the cancel API value") {
            val a = fixture.authorized()
            val c = cancelled(cancel.cancel(a.command(money = Money(2_000, a.money.currency))))
            withJvmTimeZone("Asia/Seoul") {
                val row = own(a)!!
                row.createdAt shouldBe storedUtc("created_at", "payment", "payment_id", a.paymentId.value)
                row.authorization?.approvedAt shouldBe storedUtc("confirmed_at", "payment_transaction", "authorization_id", a.authorizationId, "AND type = 'AUTHORIZATION'")
                row.cancellations.single().openedAt shouldBe storedUtc("opened_at", "payment_cancellation", "cancellation_id", c.cancellationId.value)
                row.cancellations.single().confirmedAt shouldBe storedUtc("confirmed_at", "payment_transaction", "cancellation_id", c.cancellationId.value)
                row.cancellations.single().confirmedAt shouldBe c.cancelledAt
            }
        }
    }
})
