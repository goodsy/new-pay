package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 장애·경합·복구(CP-API-001 명세 §7.3·§9, CP-DB-001 review L-1·L-4, n-3·n-4, D-CPC-004). 실서버 HTTP + 실제 MySQL(시험 lock wait 8초).
 */
@PaymentApiTest
class ApiFailureSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    private val identifiers: ScriptedIdentifierGenerator,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val port = env.getRequiredProperty("local.server.port").toInt()
    val api = ApiClient(port)
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    afterEach {
        faults.reset()
        clock.reset()
        identifiers.reset()
    }

    fun post(cred: TestCredentials.Issued, body: String, key: String) = api.post("/v1/payments", body, cred.key, key)
    fun code(r: ApiClient.Response) = s.json(r.body).get("error").get("code").stringValue()

    /**
     * 진단 전달(review round1 n-6): 응답 [r]의 correlationId 로그 줄에 분류·vendor 코드(1205)·SQLState가 있고 예외 메시지 원문은 없다.
     */
    fun diagnosticLogged(logs: CapturedLogs, r: ApiClient.Response, result: String) {
        val line = logs.lines().single { "operation=CreatePayment" in it && "correlationId=${r.header("PayCore-Request-Id")}" in it }
        line shouldContain "result=$result"
        line shouldContain "failure=LOCK_TIMEOUT"
        line shouldContain "vendorCode=1205"
        line shouldNotContain "sqlState=null"
        line shouldNotContain "Lock wait timeout exceeded"
    }

    class Hold(val reached: CountDownLatch, val release: CountDownLatch)

    /** [point]에서 한 번만 TX를 붙잡는다([condition]을 만족하는 결과일 때). [Hold.reached]가 내려가면 붙잡힌 상태, [Hold.release]를 내리면 계속한다. */
    fun holdOnce(point: FaultPoint, condition: (Any?) -> Boolean = { true }): Hold {
        val hold = Hold(CountDownLatch(1), CountDownLatch(1))
        val armed = AtomicBoolean(true)
        faults.after(point) { result ->
            if (condition(result) && armed.compareAndSet(true, false)) {
                hold.reached.countDown()
                hold.release.await(60, TimeUnit.SECONDS)
            }
        }
        return hold
    }

    Given("the commit succeeded but the caller could not learn it (CP-DB-001 review L-4)") {
        Then("an afterCommit failure answers 503 (same key, Retry-After) and the same key then replays the committed result with one row") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val armed = AtomicBoolean(true)
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) {
                if (armed.compareAndSet(true, false)) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() = throw IllegalStateException("injected failure after commit")
                    })
                }
            }
            val unknown = post(cred, body, key)
            unknown.status shouldBe 503
            code(unknown) shouldBe "TEMPORARILY_UNAVAILABLE"
            unknown.header("Retry-After") shouldBe "1"
            s.json(unknown.body).get("error").get("message").stringValue() shouldBe ApiErrorCode.OUTCOME_UNCONFIRMED.message
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)

            val replay = post(cred, body, key)
            replay.status shouldBe 200
            replay.header("Idempotent-Replayed") shouldBe "true"
            s.json(replay.body).get("paymentId").stringValue() shouldBe s.scalar("SELECT payment_id FROM payment WHERE merchant_account_id = ?", cred.mid)
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
        Then("a failure before commit (beforeCommit) is rolled back: 503, nothing stored, and the same key creates once (201)") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val armed = AtomicBoolean(true)
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) {
                if (armed.compareAndSet(true, false)) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = throw IllegalStateException("injected failure before commit")
                    })
                }
            }
            post(cred, body, key).status shouldBe 503
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            val retry = post(cred, body, key)
            retry.status shouldBe 201
            retry.header("Idempotent-Replayed") shouldBe "false"
        }
        Then("the response is lost (client closes the socket without reading): the same key replays the committed payment") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val bytes = body.toByteArray(Charsets.UTF_8)
            Socket("localhost", port).use { socket ->
                val request = "POST /v1/payments HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${cred.key}\r\nIdempotency-Key: $key\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII) + bytes)
                socket.getOutputStream().flush()
            }
            val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (s.rows(cred.mid).getValue("payment") == 0L && System.nanoTime() < deadline) Thread.sleep(50)
            val committed = s.scalar("SELECT payment_id FROM payment WHERE merchant_account_id = ?", cred.mid)
            val replay = post(cred, body, key)
            replay.status shouldBe 200
            replay.header("Idempotent-Replayed") shouldBe "true"
            s.json(replay.body).get("paymentId").stringValue() shouldBe committed
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("lock wait timeouts are classified by where they happen (contract §9, review L-1/L-4)") {
        Then("waiting on the same key's uncommitted request is 409 IDEMPOTENCY_REQUEST_IN_PROGRESS (retryable, Retry-After) and stores nothing") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val hold = holdOnce(FaultPoint.IDEMPOTENCY_RESERVE)
            val release = hold.release
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = CompletableFuture.supplyAsync({ post(cred, body, key) }, pool)
                hold.reached.await(30, TimeUnit.SECONDS) shouldBe true
                val waiting = CapturedLogs().use { logs ->
                    post(cred, body, key).also { r -> diagnosticLogged(logs, r, "IDEMPOTENCY_REQUEST_IN_PROGRESS") }
                }
                waiting.status shouldBe 409
                code(waiting) shouldBe "IDEMPOTENCY_REQUEST_IN_PROGRESS"
                s.json(waiting.body).get("error").get("retryable").booleanValue() shouldBe true
                waiting.header("Retry-After") shouldBe "1"
                release.countDown()
                first.get(60, TimeUnit.SECONDS).status shouldBe 201
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
        Then("waiting on the adjacent gap of another key's duplicate-key lock is also 409 IN_PROGRESS (same meaning: nothing stored, retry)") {
            val cred = TestCredentials.issue(db)
            val keyB = "kb-" + s.newKey()
            val keyA = "ka-" + s.newKey() // 같은 범위에서 keyB 바로 앞(binary 순서) — keyB 인덱스 레코드 앞 갭에 들어간다.
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), keyB).status shouldBe 201
            val bodyB = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            // keyB 재요청(다른 본문 → KEY_REUSED 경로)은 중복 키 IODKU로 keyB 인덱스 레코드에 next-key 잠금을 잡은 뒤 판정한다. 그 TX를 붙잡는다.
            val hold = holdOnce(FaultPoint.IDEMPOTENCY_RESERVE) { it is IdempotencyReservationOutcome.Existing }
            val release = hold.release
            val pool = Executors.newSingleThreadExecutor()
            try {
                val replayB = CompletableFuture.supplyAsync({ post(cred, bodyB, keyB) }, pool)
                hold.reached.await(30, TimeUnit.SECONDS) shouldBe true
                val waiting = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), keyA)
                waiting.status shouldBe 409
                code(waiting) shouldBe "IDEMPOTENCY_REQUEST_IN_PROGRESS"
                release.countDown()
                replayB.get(60, TimeUnit.SECONDS).status shouldBe 409
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
            db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ? AND idempotency_key = ?", cred.mid, keyA) shouldBe 0L
        }
        Then("waiting on the order control row (after the reservation) is 503 TEMPORARILY_UNAVAILABLE and the whole transaction is rolled back") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            val hold = holdOnce(FaultPoint.CONTROL_OPEN) { it is ControlAcquisition.Opened }
            val release = hold.release
            val pool = Executors.newSingleThreadExecutor()
            val secondKey = s.newKey()
            try {
                val first = CompletableFuture.supplyAsync({ post(cred, body, s.newKey()) }, pool)
                hold.reached.await(30, TimeUnit.SECONDS) shouldBe true
                val waiting = CapturedLogs().use { logs ->
                    post(cred, body, secondKey).also { r -> diagnosticLogged(logs, r, "TEMPORARILY_UNAVAILABLE") }
                }
                waiting.status shouldBe 503
                code(waiting) shouldBe "TEMPORARILY_UNAVAILABLE"
                waiting.header("Retry-After") shouldBe "1"
                // lock wait timeout은 문장만 되돌릴 수 있다 — 서비스가 TX 전체를 rollback했으므로 두 번째 요청의 PENDING 예약은 남지 않는다.
                db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ? AND idempotency_key = ?", cred.mid, secondKey) shouldBe 0L
                release.countDown()
                first.get(60, TimeUnit.SECONDS).status shouldBe 201
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
            val retry = post(cred, body, secondKey)
            retry.status shouldBe 200
            s.json(retry.body).get("outcome").stringValue() shouldBe "EXISTING_ACTIVE_PAYMENT"
        }
    }

    Given("eight concurrent HTTP requests with the same key (CP-03 at API level)") {
        Then("exactly one payment is created and every response carries the same paymentId") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val responses = try {
                (1..8).map { CompletableFuture.supplyAsync({ start.await(); post(cred, body, key) }, pool) }.also { start.countDown() }
                    .map { it.get(90, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            responses.count { it.status == 201 } shouldBe 1
            responses.map { it.status }.toSet() shouldBe setOf(200, 201)
            responses.map { s.json(it.body).get("paymentId").stringValue() }.toSet().size shouldBe 1
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("stored fingerprints of a version this instance cannot compute (contract §6.4)") {
        Then("a stored r2 request fingerprint is not compared (no KEY_REUSED): 503 and nothing changes") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            post(cred, body, key).status shouldBe 201
            db.withRoot { c -> with(db) { c.update("UPDATE idempotency_request SET request_fingerprint = ? WHERE merchant_account_id = ?", "r2:" + "a".repeat(64), cred.mid) } }
            CapturedLogs().use { logs ->
                val r = post(cred, body, key)
                r.status shouldBe 503
                code(r) shouldBe "TEMPORARILY_UNAVAILABLE"
                logs.lines().any { "failure=UNSUPPORTED_FINGERPRINT_VERSION" in it } shouldBe true
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
        Then("a stored v2 order-intent fingerprint is not compared (no ORDER_INTENT_CONFLICT): 503 and no new payment") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            post(cred, body, s.newKey()).status shouldBe 201
            db.withRoot { c -> with(db) { c.update("UPDATE order_payment_control SET fixed_intent_fingerprint = ? WHERE merchant_account_id = ?", "v2:" + "b".repeat(64), cred.mid) } }
            val r = post(cred, body, s.newKey())
            r.status shouldBe 503
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("server-generated identifiers that collide with existing ones (CP-10, spec §3.5)") {
        Then("one collision is retried with new identifiers (201); two collisions are 503, never a payload conflict; no key value is logged (n-4)") {
            val cred = TestCredentials.issue(db)
            val existing = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), s.newKey()).body)
            val existingKey = existing.get("paymentKey").stringValue()
            val existingId = existing.get("paymentId").stringValue()
            CapturedLogs().use { logs ->
                identifiers.enqueue(identifiers.real().copy(paymentKey = PaymentKey(existingKey)))
                val retried = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), s.newKey())
                retried.status shouldBe 201
                s.json(retried.body).get("paymentKey").stringValue() shouldNotBe existingKey

                identifiers.enqueue(identifiers.real().copy(paymentId = PaymentId(existingId)), identifiers.real().copy(paymentKey = PaymentKey(existingKey)))
                val failed = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), s.newKey())
                failed.status shouldBe 503
                code(failed) shouldBe "TEMPORARILY_UNAVAILABLE"
                logs.lines().any { "failure=IDENTIFIER_COLLISION" in it } shouldBe true
                logs.lines().forEach { line ->
                    line shouldNotContain existingKey
                    line shouldNotContain "Duplicate entry"
                }
            }
        }
        Then("concurrent first requests for one order (control insert race, 1062-prone paths) log no order or key values") {
            val cred = TestCredentials.issue(db)
            val orderId = "race-" + s.newOrderId()
            val keys = (1..6).map { "race-key-" + it + "-" + s.newKey() }
            CapturedLogs().use { logs ->
                val pool = Executors.newFixedThreadPool(6)
                try {
                    keys.map { k -> CompletableFuture.supplyAsync({ post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), k) }, pool) }
                        .map { it.get(90, TimeUnit.SECONDS).status }.toSet() shouldContainAll listOf(201)
                } finally {
                    pool.shutdownNow()
                }
                logs.lines().forEach { line ->
                    line shouldNotContain orderId
                    keys.forEach { line shouldNotContain it }
                }
            }
            s.rows(cred.mid).getValue("payment") shouldBe 1L
        }
    }

    Given("the idempotency record expires after 30 days (D-CPC-004)") {
        // AUTHN-001(D-AUTHN-005·판정 U-3) 기대값 갱신(§24.12): 30일 된 REQUESTED는 결제창 시각(Demo 30분)이 지나 갱신 요청의 주문 판정 TX에서 inline 만료된다 →
        // generation 2(201). 이전 기대: 200 EXISTING_ACTIVE_PAYMENT·payment 1·outbox 1. 활성 시도 재사용은 AUTHORIZING·UNKNOWN 행(아래)이 그대로 본다.
        Then("one millisecond before expiry it replays; at expiry the same key is a new request — the stale REQUESTED payment is expired inline and generation 2 is created") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val first = s.json(post(cred, body, key).body).get("paymentId").stringValue()
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(30)).minusMillis(1))
            post(cred, body, key).header("Idempotent-Replayed") shouldBe "true"
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(30)))
            val renewed = post(cred, body, key)
            renewed.status shouldBe 201
            renewed.header("Idempotent-Replayed") shouldBe "false"
            s.json(renewed.body).get("outcome").stringValue() shouldBe "CREATED"
            s.json(renewed.body).get("paymentId").stringValue() shouldNotBe first
            s.scalar("SELECT status FROM payment WHERE payment_id = ?", first) shouldBe "EXPIRED"
            s.rows(cred.mid) shouldBe s.rows(payment = 2, control = 1, idempotency = 1, outbox = 3)
        }
        /** T0에 같은 키로 만든 주문을 [prepare] 상태로 바꾸고 T0+30일에 같은 키·같은 본문을 다시 보낸다(명세 §9.3 행). */
        /** [legacy]가 있으면 domain 전이 대신 AUTH-001 이전 저장 행(L-3로 지금은 만들 수 없는 종료)으로 준비한다. */
        fun afterExpiry(
            legacy: Pair<String, TerminationEvidence>? = null,
            prepare: (com.paycore.payment.payment.domain.Payment, com.paycore.payment.payment.domain.OrderPaymentControl) -> Unit,
        ): Triple<TestCredentials.Issued, String, ApiClient.Response> {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val orderId = s.newOrderId()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            val paymentId = s.json(post(cred, body, key).body).get("paymentId").stringValue()
            if (legacy != null) db.terminateAsLegacy(paymentId, legacy.first, legacy.second.name) else s.change(cred, orderId, paymentId) { p, c -> prepare(p, c) }
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(30)))
            return Triple(cred, paymentId, post(cred, body, key))
        }

        listOf<Pair<String, (com.paycore.payment.payment.domain.Payment, com.paycore.payment.payment.domain.OrderPaymentControl) -> Unit>>(
            "AUTHORIZING" to { p, c -> c.startAuthorization(p) },
            "UNKNOWN" to { p, c -> c.startAuthorization(p); p.markUnknown() },
        ).forEach { (state, prepare) ->
            Then("at T0+30d with the original payment $state the same key reuses it: 200 EXISTING_ACTIVE_PAYMENT, no second payment (no double authorization path)") {
                val (cred, paymentId, r) = afterExpiry(prepare = prepare)
                r.status shouldBe 200
                r.header("Idempotent-Replayed") shouldBe "false"
                s.json(r.body).get("outcome").stringValue() shouldBe "EXISTING_ACTIVE_PAYMENT"
                s.json(r.body).get("paymentId").stringValue() shouldBe paymentId
                s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
            }
        }
        Then("at T0+30d an authorized order stays blocked: 409 ORDER_ALREADY_AUTHORIZED") {
            val (cred, paymentId, r) = afterExpiry { p, c -> c.startAuthorization(p); p.markAuthorized(); c.recordAuthorized(p) }
            code(r) shouldBe "ORDER_ALREADY_AUTHORIZED"
            s.json(r.body).get("error").get("referencePaymentId").stringValue() shouldBe paymentId
            s.rows(cred.mid).getValue("payment") shouldBe 1L
        }
        Then("at T0+30d a FAILED payment with insufficient evidence (legacy row, AUTH-001 L-3) blocks: 409 ORDER_RESULT_UNCONFIRMED") {
            val (cred, paymentId, r) = afterExpiry(legacy = "FAILED" to TerminationEvidence.TIMEOUT) { _, _ -> }
            code(r) shouldBe "ORDER_RESULT_UNCONFIRMED"
            s.json(r.body).get("error").get("referencePaymentId").stringValue() shouldBe paymentId
            s.rows(cred.mid).getValue("payment") shouldBe 1L
        }
        Then("at T0+30d a safely FAILED payment (sufficient evidence) lets the same key create generation 2 (201) — the legitimate second payment of contract §8") {
            val (cred, paymentId, r) = afterExpiry { p, c -> c.startAuthorization(p); p.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE) }
            r.status shouldBe 201
            s.json(r.body).get("paymentId").stringValue() shouldNotBe paymentId
            s.scalar("SELECT generation FROM order_payment_control WHERE merchant_account_id = ?", cred.mid).toString() shouldBe "2"
        }
        Then("at T0+30d the same key with the same credential and different content is a new request, never IDEMPOTENCY_KEY_REUSED") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val orderId = s.newOrderId()
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "first"), key).status shouldBe 201
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "changed"), key).let { code(it) shouldBe "IDEMPOTENCY_KEY_REUSED" }
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(30)))
            // 같은 주문·표시 정보만 다름 → 새 요청으로 판정, KEY_REUSED 아님. AUTHN-001(U-3, §24.12): 30일 된 REQUESTED는 inline 만료 → generation 2(201).
            // 이전 기대: 200 EXISTING_ACTIVE_PAYMENT.
            val sameOrder = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "changed"), key)
            sameOrder.status shouldBe 201
            s.json(sameOrder.body).get("outcome").stringValue() shouldBe "CREATED"
            // 다시 30일 뒤 같은 키로 다른 주문 → 새 요청으로 새 주문 생성(201).
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(60)))
            val otherOrder = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), key)
            otherOrder.status shouldBe 201
            listOf(sameOrder, otherOrder).forEach { it.body shouldNotContain "IDEMPOTENCY_KEY_REUSED" }
            // 이전 기대: payment 2·outbox 2. 이후: 첫 주문 generation 1(만료 — PaymentExpired) + 2, 둘째 주문 1 → payment 3·outbox 4.
            s.rows(cred.mid) shouldBe s.rows(payment = 3, control = 2, idempotency = 1, outbox = 4)
        }
        // AUTHN-001(U-3, §24.12): 갱신 1회가 inline 만료 + generation 2(201), 나머지 7은 그 결과 재생(200). 이전 기대: 전부 200·원 Payment·payment 1·outbox 1.
        Then("eight concurrent requests with the same key at T0+30d: exactly one renewal (inline expiry + generation 2), the rest replay its identical result") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val paymentId = s.json(post(cred, body, key).body).get("paymentId").stringValue()
            val originalRecord = s.scalar("SELECT request_record_id FROM idempotency_request WHERE merchant_account_id = ?", cred.mid)
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(30)))
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val responses = try {
                (1..8).map { CompletableFuture.supplyAsync({ start.await(); post(cred, body, key) }, pool) }.also { start.countDown() }
                    .map { it.get(90, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            responses.map { it.status }.sorted() shouldBe listOf(200, 200, 200, 200, 200, 200, 200, 201)
            responses.map { it.body }.toSet().size shouldBe 1
            s.json(responses[0].body).get("paymentId").stringValue() shouldNotBe paymentId
            responses.count { it.header("Idempotent-Replayed") == "false" } shouldBe 1
            s.rows(cred.mid) shouldBe s.rows(payment = 2, control = 1, idempotency = 1, outbox = 3)
            val renewed = db.withApp { c ->
                with(db) { c.rows("SELECT request_record_id r, result_code c, DATE_FORMAT(created_at, '%Y-%m-%d') d FROM idempotency_request WHERE merchant_account_id = ?", cred.mid) }
            }.single()
            (renewed["r"] != originalRecord) shouldBe true
            renewed["c"] shouldBe "CREATED"
            renewed["d"] shouldBe "2026-11-13"
        }
    }

    Given("unexpected defects (n-3)") {
        Then("the error handler logs classification fields, never the raw exception message") {
            val cred = TestCredentials.issue(db)
            val marker = "secret-marker-" + s.newKey()
            faults.after(FaultPoint.PAYMENT_INSERT) { throw IllegalStateException("boom $marker") }
            CapturedLogs().use { logs ->
                val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), s.newKey())
                r.status shouldBe 500
                code(r) shouldBe "INTERNAL_ERROR"
                r.body shouldNotContain marker
                logs.lines().any { "result=INTERNAL_ERROR" in it && "failure=DEFECT" in it && "IllegalStateException" in it } shouldBe true
                logs.lines().forEach { it shouldNotContain marker }
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }
})
