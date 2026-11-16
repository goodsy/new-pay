package com.paycore.payment.payment.adapter.query

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.SnapshotProbe
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentQuery
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * 관리자 거래 조회 jOOQ Reader(TXN-OPS-001 §3.2–§3.4·AC 3·4·5)를 실 MySQL 8.4.11(runtime 계정·표 단위 권한)·운영 confirm/cancel 경로로 만든 상태에서 시험한다. 상태는 Named Fixture
 * (`ConfirmFixture`·`CancelFixture`)로만 만들고, 표시용 주문명 길이만 root 연결로 바꾼다(주문명은 고정 입력이 아닌 표시값 — D-CPC-001, 판정에 쓰이지 않는다).
 * 시험마다 새 MID(또는 다른 시험과 겹치지 않는 시각대)를 써 공유 DB의 다른 행과 섞이지 않게 한다.
 */
@PaymentApiTest
class AdminPaymentReaderSpec(
    private val reader: JooqAdminPaymentReader,
    private val query: AdminPaymentQuery,
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
    val t0 = ConfirmFixture.T0.truncatedTo(ChronoUnit.MILLIS)

    afterEach {
        faults.reset()
        clock.reset()
        probe.disarm()
    }

    fun newMid() = MerchantAccountId("mid-" + UUID.randomUUID().toString().replace("-", "").take(16))
    fun order(mid: MerchantAccountId, environment: Environment = Environment.TEST) =
        OrderKey(environment, mid, MerchantOrderId("ord-" + UUID.randomUUID().toString().replace("-", "").take(16)))

    /** `ConfirmFixture.prepared`는 Payment를 `base − 120초`에 만든다. */
    fun createdAt(base: Instant): Instant = base.minusSeconds(120)

    fun page(criteria: AdminPaymentSearchCriteria) = query.search(criteria)
    fun ids(criteria: AdminPaymentSearchCriteria) = page(criteria).items.map { it.paymentId }
    fun period(from: Instant, to: Instant) = AdminPaymentSearchCriteria(createdFrom = from, createdTo = to)

    fun setOrderName(paymentId: String, name: String?) = db.withRoot { c -> with(db) { c.update("UPDATE ${db.databaseName}.payment SET order_name = ? WHERE payment_id = ?", name, paymentId) } }

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

    fun cancelled(r: CancelPaymentResult) = r.shouldBeInstanceOf<CancelPaymentResult.Accepted>().view

    Given("payments of one MID created at different times, in two environments and two statuses") {
        Then("the period is [from, to), the environment, MID and status filters are exact and the list is newest first") {
            val mid = newMid()
            val p1 = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(60)) // created t0 − 60s
            val p2 = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(120)) // created t0
            val p3 = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(180)) // created t0 + 60s
            val live = confirmFixture.prepared(order = order(mid, Environment.LIVE), base = t0.plusSeconds(120))
            val authorized = fixture.authorized(order = order(mid)) // created t0 − 120s, AUTHORIZED
            val other = confirmFixture.prepared(order = order(newMid()), base = t0.plusSeconds(120))
            val wide = AdminPaymentSearchCriteria(createdFrom = t0.minusSeconds(3600), createdTo = t0.plusSeconds(3600), merchantAccountId = mid.value)

            val all = page(wide)
            // p2·live는 같은 created_at(t0) — 동률은 paymentId 내림차순.
            all.items.map { it.paymentId } shouldBe
                listOf(p3.paymentId.value) + listOf(p2.paymentId.value, live.paymentId.value).sortedDescending() + listOf(p1.paymentId.value, authorized.paymentId.value)
            all.nextCursor shouldBe null
            all.items.first().let {
                it.environment shouldBe "TEST"
                it.merchantAccountId shouldBe mid.value
                it.merchantOrderId shouldBe p3.order.merchantOrderId.value
                it.amountMinor shouldBe 10_000
                it.currency shouldBe "KRW"
                it.status shouldBe "REQUESTED"
                it.createdAt shouldBe createdAt(t0.plusSeconds(180))
            }
            ids(wide.copy(environment = "LIVE")) shouldBe listOf(live.paymentId.value)
            ids(wide.copy(status = "AUTHORIZED")) shouldBe listOf(authorized.paymentId.value)
            // [from, to): p2·live(t0)는 to = t0에서 빠지고 from = t0에서 들어온다.
            ids(wide.copy(createdFrom = t0.minusSeconds(60), createdTo = t0)) shouldBe listOf(p1.paymentId.value)
            ids(wide.copy(createdFrom = t0, createdTo = t0.plusSeconds(60))).toSet() shouldBe setOf(p2.paymentId.value, live.paymentId.value)
            ids(wide).contains(other.paymentId.value) shouldBe false
        }
    }

    Given("payments created at the same instant (ties on created_at)") {
        Then("ties on created_at are broken by paymentId: pages never skip or repeat a row") {
            val mid = newMid()
            val same = (1..5).map { confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(300)) }
            val newer = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(301))
            val older = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(299))
            val expected = listOf(newer.paymentId.value) + same.map { it.paymentId.value }.sortedDescending() + listOf(older.paymentId.value)
            val criteria = AdminPaymentSearchCriteria(createdFrom = t0, createdTo = t0.plusSeconds(3600), merchantAccountId = mid.value, limit = 2)
            val seen = mutableListOf<String>()
            var cursor: AdminPaymentCursor? = null
            var pages = 0
            do {
                val p = page(criteria.copy(after = cursor))
                p.items.size shouldBe (if (p.nextCursor == null) p.items.size else 2)
                seen += p.items.map { it.paymentId }
                cursor = p.nextCursor
                pages++
            } while (cursor != null && pages < 10)
            seen shouldBe expected
            pages shouldBe 4
        }
    }

    Given("a time range no other test uses") {
        Then("a period search without a MID sees every environment and MID of that range, newest first, and honours the limit") {
            val base = t0.plus(Duration.ofDays(400L + (System.nanoTime() % 3000)))
            val a = confirmFixture.prepared(order = order(newMid()), base = base)
            val b = confirmFixture.prepared(order = order(newMid(), Environment.LIVE), base = base.plusSeconds(1))
            val criteria = period(createdAt(base).minusSeconds(1), createdAt(base).plusSeconds(10))
            ids(criteria) shouldBe listOf(b.paymentId.value, a.paymentId.value)
            page(criteria.copy(limit = 1)).let {
                it.items.map { i -> i.paymentId } shouldBe listOf(b.paymentId.value)
                it.nextCursor shouldBe AdminPaymentCursor(createdAt(base).plusSeconds(1), b.paymentId.value)
            }
        }
    }

    Given("the exact lookups") {
        Then("the paymentId lookup returns that payment or nothing; the order lookup returns only that order of that MID without a period") {
            val mid = newMid()
            val a = confirmFixture.prepared(order = order(mid))
            val sameMidOtherOrder = confirmFixture.prepared(order = order(mid))
            ids(AdminPaymentSearchCriteria(paymentId = a.paymentId.value)) shouldBe listOf(a.paymentId.value)
            ids(AdminPaymentSearchCriteria(paymentId = AdminOpsTestClient.newPaymentId())).shouldBeEmpty()
            ids(AdminPaymentSearchCriteria(merchantAccountId = mid.value, merchantOrderId = a.order.merchantOrderId.value)) shouldBe listOf(a.paymentId.value)
            ids(AdminPaymentSearchCriteria(merchantAccountId = mid.value, merchantOrderId = a.order.merchantOrderId.value, environment = "LIVE")).shouldBeEmpty()
            ids(AdminPaymentSearchCriteria(merchantAccountId = newMid().value, merchantOrderId = a.order.merchantOrderId.value)).shouldBeEmpty()
            ids(AdminPaymentSearchCriteria(merchantAccountId = mid.value, merchantOrderId = sameMidOtherOrder.order.merchantOrderId.value)) shouldBe
                listOf(sameMidOtherOrder.paymentId.value)
        }
    }

    Given("order names of 14, 10 and no characters (D-TXOPS-003)") {
        Then("the list shows only the first 10 characters of the order name and whether it was cut") {
            val mid = newMid()
            val long = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(3))
            val ten = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(2))
            val none = confirmFixture.prepared(order = order(mid), base = t0.plusSeconds(1))
            val full = "가나다라마바사아자차카타파하"
            setOrderName(long.paymentId.value, full)
            setOrderName(ten.paymentId.value, "0123456789")
            setOrderName(none.paymentId.value, null)
            val items = page(AdminPaymentSearchCriteria(createdFrom = t0.minusSeconds(600), createdTo = t0.plusSeconds(600), merchantAccountId = mid.value)).items
            items.map { Triple(it.paymentId, it.orderNamePreview, it.orderNameTruncated) } shouldBe listOf(
                Triple(long.paymentId.value, "가나다라마바사아자차", true),
                Triple(ten.paymentId.value, "0123456789", false),
                Triple(none.paymentId.value, null, false),
            )
            // TXN-LIST-ENRICH-001 T-E1-1: 목록 항목에 결제수단(PAYMENT 소유 `payment.payment_method`).
            items.map { it.paymentMethod } shouldBe listOf("CARD", "CARD", "CARD")
            // 목록 경로의 어떤 값에도 전체 이름이 없다(SQL LEFT로만 읽는다 — I-5).
            JsonMapper.builder().build().writeValueAsString(items) shouldNotContain "카타파하"
            // 상세(감사 대상)는 전체 이름.
            reader.detail(long.paymentId.value)!!.payment.orderName shouldBe full
        }
    }

    Given("an authorized payment with an operator correction cancel, a merchant cancel, a declined cancel and a hold, next to another payment") {
        Then("the detail reads payment, authorization, transactions, cancellations, return balance and hold of that payment only") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val b = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            clock.set(ConfirmFixture.T0.plusSeconds(1))
            val op = operator.cancel(a.operatorCommand(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>().view
            clock.set(ConfirmFixture.T0.plusSeconds(2))
            val merchant = cancelled(cancel.cancel(a.command(money = Money(3_000, a.money.currency))))
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            clock.set(ConfirmFixture.T0.plusSeconds(3))
            val declined = cancelled(cancel.cancel(a.command(money = Money(2_000, a.money.currency))))
            cancelled(cancel.cancel(b.command(money = Money(500, b.money.currency))))
            fixture.holdOn(a)

            val d = reader.detail(a.paymentId.value)!!
            d.payment.paymentId shouldBe a.paymentId.value
            d.payment.environment shouldBe a.prepared.order.environment.name
            d.payment.merchantAccountId shouldBe a.mid.value
            d.payment.merchantOrderId shouldBe a.prepared.order.merchantOrderId.value
            d.payment.orderName shouldBe "Demo order"
            d.payment.status shouldBe "AUTHORIZED"
            d.payment.paymentMethod shouldBe "CARD"
            d.onHold shouldBe true
            d.terminationEvidence.shouldBeEmpty()
            d.authorization!!.let {
                it.authorizationId shouldBe a.authorizationId
                it.status shouldBe "AUTHORIZED"
                it.planKind shouldBe "LUMP_SUM"
                it.cardType shouldBe "CREDIT"
            }
            d.transactions.map { it.type } shouldBe listOf("AUTHORIZATION", "CANCEL", "CANCEL")
            d.transactions[0].let {
                it.amountMinor shouldBe 10_000
                it.approvalNumber shouldNotBe null
                it.providerTransactionId.isNotBlank() shouldBe true
                it.cancellationId shouldBe null
            }
            d.transactions.drop(1).map { it.cancellationId } shouldBe listOf(op.cancellationId.value, merchant.cancellationId.value)
            d.transactions.drop(1).map { it.originalTransactionId }.toSet() shouldBe setOf(d.transactions[0].transactionId)
            d.cancellations.map { it.cancellationId } shouldBe listOf(op.cancellationId.value, merchant.cancellationId.value, declined.cancellationId.value)
            d.cancellations.map { it.initiatorKind } shouldBe listOf("OPERATOR", "MERCHANT", "MERCHANT")
            d.cancellations.map { it.status } shouldBe listOf("COMPLETED", "COMPLETED", "FAILED")
            d.cancellations[0].merchantCancelId shouldBe null
            d.cancellations[2].failureEvidence shouldBe "INSTITUTION_CONFIRMED_DECLINE"
            val balance = checkNotNull(d.returnBalance)
            balance shouldBe AdminPaymentDetail.ReturnBalance("KRW", 10_000, 4_000, 0, 0, balance.revision)
            (balance.revision >= 2) shouldBe true
            reader.detail(b.paymentId.value)!!.let {
                it.onHold shouldBe false
                it.cancellations.size shouldBe 1
            }
            reader.detail(AdminOpsTestClient.newPaymentId()) shouldBe null
        }
        Then("non-exposure: the institution account and the operator reason exist in the database but in no field of the detail") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            operator.cancel(a.operatorCommand(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
            val account = db.withRoot { c ->
                with(db) { c.scalar("SELECT institution_account FROM ${db.databaseName}.authorization_attempt WHERE authorization_id = ? LIMIT 1", a.authorizationId) as String }
            }
            val reason = db.withRoot { c ->
                with(db) { c.scalar("SELECT operator_reason FROM ${db.databaseName}.payment_cancellation WHERE payment_id = ?", a.paymentId.value) as String }
            }
            account.isNotBlank() shouldBe true
            reason.isNotBlank() shouldBe true
            val json = JsonMapper.builder().build().writeValueAsString(reader.detail(a.paymentId.value))
            withClue("control: the serialized detail carries the payment") { json.contains(a.paymentId.value) shouldBe true }
            json shouldNotContain account
            json shouldNotContain reason
            json shouldNotContain "case-demo-0001"
        }
    }

    // CP-QUERY-001 m-3 방식: 첫 문장(Payment·hold) 직후 같은 TX 안에서 다른 연결의 취소 TX2 커밋을 끝까지 기다린다 — 뒤 문장들은 커밋 전 스냅샷을 봐야 한다.
    Given("a cancel TX2 committed between the detail's first statement and the rest") {
        Then("every statement of one detail read runs in one read-only REPEATABLE READ transaction (no torn detail)") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            val once = AtomicBoolean(false)
            faults.after(FaultPoint.OUTBOX_APPEND) {
                if (once.compareAndSet(false, true)) {
                    reached.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "the probe did not release the cancel TX2" }
                }
            }
            val cancelling = CompletableFuture.supplyAsync { cancelled(cancel.cancel(a.command(money = Money(3_000, a.money.currency)))) }
            check(reached.await(20, TimeUnit.SECONDS)) { "the cancel did not reach its TX2" }
            probe.arm("`admin_on_hold`") {
                release.countDown()
                cancelling.get(20, TimeUnit.SECONDS)
            }
            val d = reader.detail(a.paymentId.value)!!
            probe.observed.get() shouldBe SnapshotProbe.TxState("REPEATABLE-READ", 1)
            cancelling.isDone shouldBe true
            d.cancellations.single().status shouldBe "OPEN"
            d.transactions.map { it.type } shouldBe listOf("AUTHORIZATION")
            d.returnBalance!!.let { listOf(it.completedMinor, it.reservedMinor) } shouldBe listOf(0L, 3_000L)
            reader.detail(a.paymentId.value)!!.cancellations.single().status shouldBe "COMPLETED"
        }
    }

    Given("a JVM whose default time zone is Asia/Seoul (TZ-FIX-001)") {
        Then("list and detail times are the stored UTC wall clock (not shifted by 9 hours)") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val c = cancelled(cancel.cancel(a.command(money = Money(2_000, a.money.currency))))
            withJvmTimeZone("Asia/Seoul") {
                val d = reader.detail(a.paymentId.value)!!
                d.payment.createdAt shouldBe storedUtc("created_at", "payment", "payment_id", a.paymentId.value)
                d.authorization!!.openedAt shouldBe storedUtc("opened_at", "payment_authorization", "authorization_id", a.authorizationId)
                d.transactions[0].confirmedAt shouldBe storedUtc("confirmed_at", "payment_transaction", "authorization_id", a.authorizationId, "AND type = 'AUTHORIZATION'")
                d.cancellations.single().openedAt shouldBe storedUtc("opened_at", "payment_cancellation", "cancellation_id", c.cancellationId.value)
                ids(AdminPaymentSearchCriteria(paymentId = a.paymentId.value)) shouldBe listOf(a.paymentId.value)
                page(AdminPaymentSearchCriteria(paymentId = a.paymentId.value)).items.single().createdAt shouldBe d.payment.createdAt
                // 기간 경계도 UTC 리터럴로 비교한다(바인딩 시간대 변환 없음).
                ids(AdminPaymentSearchCriteria(createdFrom = d.payment.createdAt, createdTo = d.payment.createdAt.plusMillis(1), merchantAccountId = a.mid.value)) shouldBe
                    listOf(a.paymentId.value)
            }
        }
    }

    // V31 색인(§3.2): 목록 문장은 전체 표 스캔 없이 새 색인을 쓰고 정렬도 색인 순서다(filesort 없음).
    Given("the execution plans of the list statements") {
        Then("the period search uses ix_payment_created, the MID search ix_payment_mid_created, the order lookup the order key; no full scan, no filesort") {
            val mid = newMid()
            val a = confirmFixture.prepared(order = order(mid))
            fun plan(criteria: AdminPaymentSearchCriteria): List<Map<String, Any?>> {
                probe.capture()
                page(criteria)
                val statements = probe.captured.toList()
                probe.disarm()
                statements.size shouldBe 1
                return db.withApp { c -> with(db) { c.rows("EXPLAIN ${statements.single()}") } }
            }
            val cases = mapOf(
                period(t0.minusSeconds(3600), t0.plusSeconds(3600)) to "ix_payment_created",
                AdminPaymentSearchCriteria(createdFrom = t0.minusSeconds(3600), createdTo = t0.plusSeconds(3600), merchantAccountId = mid.value) to "ix_payment_mid_created",
                AdminPaymentSearchCriteria(merchantAccountId = mid.value, merchantOrderId = a.order.merchantOrderId.value) to "uk_payment_order_generation",
                AdminPaymentSearchCriteria(paymentId = a.paymentId.value) to "PRIMARY",
            )
            cases.forEach { (criteria, index) ->
                val rows = plan(criteria)
                withClue("$criteria\n$rows") {
                    rows.single { it["table"] == "p" || it["table"] == "payment" }["key"] shouldBe index
                    rows.map { it["type"] }.filter { it == "ALL" }.shouldBeEmpty()
                    rows.filter { (it["Extra"] as String?)?.contains("filesort") == true && index != "uk_payment_order_generation" }.shouldBeEmpty()
                }
            }
        }
    }
})
