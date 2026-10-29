package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.ApiClient
import com.paycore.payment.payment.adapter.web.CreatePaymentRequests
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.TestCredentials
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 관리 조회 장애 비전파(ADMIN-PAYMENT-OPS-001 §4.7 M-7 `AdminReadIsolationSpec` — 실제 HTTP 두 connector, 실제 DB 연결 점유): 관리 대상 조회 4건이 각각 **실제 풀 연결에서 `SELECT SLEEP(6)`**를
 * 실행해 조회 permit 4개와 연결 4개를 붙잡은 동안, 5번째 관리 조회는 대기 없이 503 `ADMIN_READ_BUSY`(< 200ms), 공개 포트 CreatePayment 20건 + confirm 20건(공식 준비 경로 ConfirmFixture로 만든
 * 인증 완료 Payment) 동시 요청은 모두 성공(5xx·timeout 0건)하고 **모두 관리 조회 4건보다 먼저 끝난다**(review M-3). p95는 클라이언트 timeout(60초)의 50% 미만. 지연 p50·p95는 포화 없는
 * 기준선과 함께 표준 출력에 남긴다. 점유는 시험 hook(대상 조회 직후, 같은 요청 스레드·permit 안)이 연다(I-61).
 */
@PaymentApiTest
class AdminReadIsolationSpec(
    env: SpringEnvironment,
    private val internal: InternalConnector,
    private val faults: FaultInjector,
    private val db: DbAccess,
    private val dataSource: DataSource,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val publicApi = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val admin = AdminOpsTestClient(internal.localPort)
    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)

    afterEach { faults.reset() }

    fun target() = admin.get("/internal/v1/admin/payments/${AdminOpsTestClient.newPaymentId()}/operator-cancel-target")

    /** 결과: (상태, 지연 ms, 완료 nanoTime). */
    data class Done(val status: Int, val millis: Long, val endNanos: Long)

    /** 부하 준비(인증 완료 Payment는 점유 전에 만든다 — 준비 시간이 점유 구간을 먹지 않게). */
    class Load(val cred: TestCredentials.Issued, val prepared: List<ConfirmFixture.Prepared>)

    fun prepare(confirms: Int): Load {
        val cred = TestCredentials.issue(db, environment = Environment.TEST)
        return Load(cred, (1..confirms).map {
            confirmFixture.prepared(order = OrderKey(cred.environment, MerchantAccountId(cred.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().take(12))))
        })
    }

    fun publicLoad(creates: Int, load: Load): List<Done> {
        val cred = load.cred
        val prepared = load.prepared
        val calls: List<() -> Int> = (1..creates).map {
            {
                publicApi.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = "ord-" + UUID.randomUUID().toString().take(12)), cred.key, "idem-" + UUID.randomUUID().toString().take(12)).status
            }
        } + prepared.map { p ->
            {
                val body = """{"authenticationRef":"${p.authenticationRef.value}","merchantOrderId":"${p.order.merchantOrderId.value}","amount":${p.money.amountMinor},"currency":"${p.money.currency.value}"}"""
                publicApi.post("/v1/payments/${p.paymentId.value}/confirm", body, cred.key, ConfirmFixture.newKey()).status
            }
        }
        val pool = Executors.newFixedThreadPool(calls.size)
        val start = CountDownLatch(1)
        val futures = calls.map { call ->
            pool.submit<Done> {
                start.await()
                val t0 = System.nanoTime()
                val status = call()
                val end = System.nanoTime()
                Done(status, (end - t0) / 1_000_000, end)
            }
        }
        start.countDown()
        return futures.map { it.get(90, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    fun pct(ms: List<Long>, p: Double): Long = ms.sorted()[((ms.size - 1) * p).toInt()]
    fun statuses(d: List<Done>) = d.map { it.status }.toSet()

    Given("all admin read permits holding real database connections") {
        Then("one more admin read is ADMIN_READ_BUSY at once; 20 CreatePayment and 20 confirm requests all succeed and finish before the held admin reads") {
            val baseline = publicLoad(20, prepare(20))
            val loadUnderPressure = prepare(20)
            withClue("baseline") { statuses(baseline) shouldBe setOf(201, 200) }
            val allHeld = CountDownLatch(4)
            val held = AtomicInteger()
            faults.after(FaultPoint.OPERATOR_TARGET_FIND) {
                dataSource.connection.use { c ->
                    held.incrementAndGet()
                    allHeld.countDown()
                    c.createStatement().use { it.execute("SELECT SLEEP(6)") }
                }
            }
            val readers = Executors.newFixedThreadPool(4)
            val reads = (1..4).map { readers.submit<Done> { val t0 = System.nanoTime(); val r = target(); val e = System.nanoTime(); Done(r.status, (e - t0) / 1_000_000, e) } }
            allHeld.await(10, TimeUnit.SECONDS) shouldBe true
            val t0 = System.nanoTime()
            val busy = target()
            val busyMillis = (System.nanoTime() - t0) / 1_000_000
            busy.status shouldBe 503
            busy.body shouldBe """{"error":"ADMIN_READ_BUSY"}"""
            (busyMillis < 200) shouldBe true
            val loaded = publicLoad(20, loadUnderPressure)
            val adminDone = reads.map { it.get(30, TimeUnit.SECONDS) }
            readers.shutdown()
            held.get() shouldBe 4
            adminDone.map { it.status }.toSet() shouldBe setOf(404)
            statuses(loaded) shouldBe setOf(201, 200)
            loaded.count { it.status == 200 } shouldBe 20
            (loaded.maxOf { it.endNanos } < adminDone.minOf { it.endNanos }) shouldBe true
            val p95 = pct(loaded.map { it.millis }, 0.95)
            println(
                "AdminReadIsolationSpec latency ms: baseline p50=${pct(baseline.map { it.millis }, 0.5)} p95=${pct(baseline.map { it.millis }, 0.95)}; " +
                    "saturated p50=${pct(loaded.map { it.millis }, 0.5)} p95=$p95; busy admin read=${busyMillis}ms",
            )
            (Duration.ofMillis(p95) < Duration.ofSeconds(30)) shouldBe true
        }
    }
})
