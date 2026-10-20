package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.CommitFaults
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import org.springframework.core.env.Environment as SpringEnvironment

/** 로그의 keyId 접두 길이(CP-QUERY-001 CQ-16 — keyId는 비밀이 아니지만 전체 대신 앞 6자만). 시험이 독립적으로 정한 기대값. */
private const val KEY_ID_PREFIX = 6

/**
 * GetPayment 공개 API(CP-QUERY-001 S3 — §4·§5·§7, §9 T1·T2·T4·T5·T6·H-1·E-1). 실제 Tomcat·보안 필터·MySQL(runtime 계정)·운영 Credential 검증·운영 confirm/cancel 경로.
 * 기대값은 명세 표(§4.4 필드 집합, §4.5 오류, §5.1 상태)에서 직접 적는다.
 */
@PaymentApiTest
class GetPaymentApiSpec(
    env: SpringEnvironment,
    private val admission: ScriptedAdmissionPort,
    private val van: ProgrammableVan,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val expiry: ExpireElapsedRequestedPaymentsUseCase,
    private val clock: MutablePaymentClock,
    private val registry: MeterRegistry,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val port = env.getRequiredProperty("local.server.port").toInt()
    val api = ApiClient(port)
    val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val mapper = JsonMapper.builder().build()

    afterEach {
        CommitFaults.reset()
        clock.reset()
    }

    fun order(cred: TestCredentials.Issued) = OrderKey(cred.environment, MerchantAccountId(cred.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().take(12)))
    fun authorized(cred: TestCredentials.Issued) = fixture.authorized(order = order(cred))
    fun get(cred: TestCredentials.Issued?, paymentId: String) = api.get("/v1/payments/$paymentId", cred?.key)
    fun json(s: String): JsonNode = mapper.readTree(s)
    fun keys(n: JsonNode) = n.properties().map { it.key }.toSet()
    fun raw(method: String, path: String, cred: TestCredentials.Issued, body: String? = null): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(30))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer ${cred.key}").build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    /** 최종 검토 m-1: 이 요청의 결과 로그 줄(`operation=GetPayment … keyIdPrefix=… result=…` — 진단 줄과 구별해 keyIdPrefix가 있는 줄만). */
    fun outcomeLines(logs: CapturedLogs, requestId: String) =
        logs.lines().filter { it.contains("operation=GetPayment") && it.contains("correlationId=$requestId") && it.contains("keyIdPrefix=") }
    fun queryCount(result: String) = registry.find("paycore.payment.query").tag("result", result).counter()?.count() ?: 0.0

    Given("the owner's credential and an authorized Payment (§4.4)") {
        Then("200 with exactly the contract fields, the confirm-shaped authorization, a full return summary, no history, onHold false, Cache-Control no-store, no ETag") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            val before = queryCount("FOUND")
            val logs = CapturedLogs()
            val r = logs.use { get(cred, a.paymentId.value) }
            withClue(r.body) { r.status shouldBe 200 }
            // 최종 검토 m-1(CQ-09·§7): 결과마다 INFO 한 줄 — correlationId·keyId 접두·result, FOUND만 paymentId.
            outcomeLines(logs, r.header("PayCore-Request-Id")!!).single().let { line ->
                line shouldContain "result=FOUND"
                line shouldContain "keyIdPrefix=${cred.keyId.take(KEY_ID_PREFIX)}"
                line shouldNotContain cred.keyId
                line shouldContain "paymentId=${a.paymentId.value}"
            }
            val b = json(r.body)
            keys(b) shouldBe setOf(
                "paymentId", "merchantOrderId", "amount", "currency", "paymentCreatedAt", "status", "onHold", "authorization", "returnSummary", "cancellations",
            )
            b.get("paymentId").stringValue() shouldBe a.paymentId.value
            b.get("merchantOrderId").stringValue() shouldBe a.prepared.order.merchantOrderId.value
            b.get("amount").isIntegralNumber shouldBe true
            b.get("amount").longValue() shouldBe 10_000
            b.get("status").stringValue() shouldBe "AUTHORIZED"
            b.get("onHold").booleanValue() shouldBe false
            keys(b.get("authorization")) shouldBe setOf("authorizationId", "status", "approvedAmount", "currency", "plan", "cardType", "approvalNumber", "approvedAt")
            b.get("authorization").get("authorizationId").stringValue() shouldBe a.authorizationId
            b.get("authorization").get("plan").get("kind").stringValue() shouldBe "LUMP_SUM"
            keys(b.get("returnSummary")) shouldBe setOf("authorizedAmount", "cancelledAmount", "cancelInProgressAmount", "cancellableAmount", "returnStatus")
            b.get("returnSummary").get("cancellableAmount").longValue() shouldBe 10_000
            b.get("returnSummary").get("returnStatus").stringValue() shouldBe "NONE"
            b.get("cancellations").isArray shouldBe true
            b.get("cancellations").size() shouldBe 0
            // 최종 검토 n-4(MW3): no-store 보장은 두 겹 — Controller가 쓰는 `no-store`와 Spring Security 기본 header writer(`no-cache, no-store, max-age=0, must-revalidate`).
            // 그래서 Controller 쪽 header만 지우는 변이는 관찰 차이가 없다. 이 단언은 "no-store 포함"이라는 계약만 본다.
            (r.header("Cache-Control") ?: "") shouldContain "no-store"
            r.header("ETag") shouldBe null
            r.header("PayCore-Request-Id") shouldNotBe null
            // 비노출(§4.4): paymentKey·기관 계약계정·PTX·내부 버전 없음.
            r.body shouldNotContain "paymentKey"
            r.body shouldNotContain ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF
            r.body shouldNotContain (db.withApp { c -> with(db) { c.scalar("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ?", a.authorizationId) } } as String)
            r.body shouldNotContain "Version"
            queryCount("FOUND") shouldBe before + 1
        }
    }

    Given("requests that must not reveal whether the Payment exists (§4.3, T1·T2)") {
        Then("another MID, the same MID's other environment, an unknown id and a malformed id are the same 404; no echo in body, headers or logs") {
            val owner = TestCredentials.issue(db, environment = Environment.LIVE)
            val live = confirmFixture.prepared(order = order(owner)) // LIVE REQUESTED Payment
            get(owner, live.paymentId.value).status shouldBe 200
            val otherMid = TestCredentials.issue(db, environment = Environment.LIVE)
            val sameMidTest = TestCredentials.issue(db, mid = owner.mid, environment = Environment.TEST)
            val marker = "pay_ZZNOSUCHMARKER" + UUID.randomUUID().toString().replace("-", "").take(10)
            val before = queryCount("NOT_FOUND")
            CapturedLogs().use { logs ->
                val responses = listOf(
                    get(otherMid, live.paymentId.value),
                    get(sameMidTest, live.paymentId.value),
                    get(owner, marker),
                    get(owner, "%E2%82%AC" + marker), // 비ASCII(형식 밖)
                    get(owner, "x".repeat(65)),
                )
                responses.forEach { withClue(it.body) { it.status shouldBe 404 } }
                val bodies = responses.map { json(it.body).get("error") }
                bodies.map { it.get("code").stringValue() }.toSet() shouldBe setOf("PAYMENT_NOT_FOUND")
                bodies.map { (it as tools.jackson.databind.node.ObjectNode).deepCopy().also { n -> n.remove("requestId") }.toString() }.toSet().size shouldBe 1
                responses.map { r -> r.headers.keys.map { it.lowercase() }.toSet() }.toSet().size shouldBe 1
                responses.forEach { r ->
                    r.body shouldNotContain live.paymentId.value
                    r.body shouldNotContain marker
                    r.headers.values.flatten().joinToString().let { h ->
                        h shouldNotContain marker
                        h shouldNotContain live.paymentId.value
                    }
                }
                logs.lines().joinToString("\n").let { all ->
                    all shouldNotContain marker
                    all shouldNotContain live.paymentId.value
                }
                // 최종 검토 m-1: 404마다 결과 줄 하나(result=NOT_FOUND, 요청한 키의 접두), paymentId 없음.
                responses.zip(listOf(otherMid, sameMidTest, owner, owner, owner)).forEach { (r, cred) ->
                    outcomeLines(logs, r.header("PayCore-Request-Id")!!).single().let { line ->
                        line shouldContain "result=NOT_FOUND"
                        line shouldContain "keyIdPrefix=${cred.keyId.take(KEY_ID_PREFIX)}"
                        line shouldNotContain "paymentId="
                    }
                }
            }
            queryCount("NOT_FOUND") shouldBe before + 5
        }
        Then("a revoked credential is 401 with no payment data") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            TestCredentials.revoke(db, cred)
            val r = get(cred, a.paymentId.value)
            r.status shouldBe 401
            r.body shouldNotContain a.paymentId.value
        }
    }

    Given("each summary state through the API (§5.1, T4)") {
        Then("REQUESTED has a null authorization and summary; a declined confirm is FAILED(DECLINED); a pending confirm is UNKNOWN with the notice") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val requested = confirmFixture.prepared(order = order(cred))
            json(get(cred, requested.paymentId.value).body).let {
                it.get("status").stringValue() shouldBe "REQUESTED"
                it.get("authorization").isNull shouldBe true
                it.get("returnSummary").isNull shouldBe true
                it.has("failureCategory") shouldBe false
                it.has("notice") shouldBe false
            }
            val declined = confirmFixture.prepared(order = order(cred))
            van.onAuth(cred.mid) { van.decline(it) }
            confirm.confirm(declined.command())
            json(get(cred, declined.paymentId.value).body).let {
                it.get("status").stringValue() shouldBe "FAILED"
                it.get("failureCategory").stringValue() shouldBe "DECLINED"
                keys(it.get("authorization")) shouldBe setOf("authorizationId", "status", "failureCategory")
                it.get("returnSummary").isNull shouldBe true
                it.get("cancellations").size() shouldBe 0
            }
            val pending = confirmFixture.prepared(order = order(cred))
            van.onAuth(cred.mid) { van.pending(it) }
            confirm.confirm(pending.command())
            json(get(cred, pending.paymentId.value).body).let {
                it.get("status").stringValue() shouldBe "UNKNOWN"
                it.get("authorization").get("status").stringValue() shouldBe "UNKNOWN"
                it.get("notice").stringValue() shouldContain "do not retry with a new key"
            }
        }
    }

    Given("an authorized Payment with an operator correction cancel and merchant cancels (§4.4, D-CPQ-002, T6)") {
        Then("the history shows every cancel in order; the operator one only as initiator OPERATOR; the summary matches the history; no operator data") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            clock.set(ConfirmFixture.T0.plusSeconds(1))
            val opCommand = a.operatorCommand(money = Money(1_000, a.money.currency))
            operator.cancel(opCommand).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
            clock.set(ConfirmFixture.T0.plusSeconds(2))
            val mCommand = a.command(money = Money(3_000, a.money.currency))
            cancel.cancel(mCommand).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            clock.set(ConfirmFixture.T0.plusSeconds(3))
            cancel.cancel(a.command(money = Money(2_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
            val r = get(cred, a.paymentId.value)
            r.status shouldBe 200
            val b = json(r.body)
            val history = b.get("cancellations")
            history.size() shouldBe 3
            // Jackson 3 JsonNode의 `map`은 Kotlin Iterable.map이 아니다 — 원소를 색인으로 꺼낸다.
            val items = (0 until history.size()).map { history[it] }
            withClue(r.body) {
                items.map { it.get("initiator").stringValue() } shouldBe listOf("OPERATOR", "MERCHANT", "MERCHANT")
                items.map { it.get("status").stringValue() } shouldBe listOf("COMPLETED", "COMPLETED", "FAILED")
            }
            keys(history[0]) shouldBe setOf("cancellationId", "initiator", "status", "amount", "currency", "requestedAt", "cancelTransactionId", "cancelApprovalNumber", "cancelledAt")
            history[1].get("merchantCancelId").stringValue() shouldBe mCommand.merchantCancelId.value
            history[2].get("failureCategory").stringValue() shouldBe "DECLINED"
            b.get("returnSummary").let {
                it.get("cancelledAmount").longValue() shouldBe 4_000
                it.get("cancelInProgressAmount").longValue() shouldBe 0
                it.get("cancellableAmount").longValue() shouldBe 6_000
                it.get("returnStatus").stringValue() shouldBe "PARTIALLY_CANCELLED"
            }
            r.body shouldNotContain opCommand.requestedBy.value
            r.body shouldNotContain opCommand.approvedBy.value
            r.body shouldNotContain opCommand.approvalId.value
            r.body shouldNotContain opCommand.reason
        }
        Then("an active hold on the Payment is onHold true without a reason (D-CPQ-001)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            fixture.holdOn(a)
            val r = get(cred, a.paymentId.value)
            json(r.body).get("onHold").booleanValue() shouldBe true
            r.body shouldNotContain "CANCEL_RESULT_CONFLICT"
        }
    }

    Given("the create response and the current state (D-CP-003, T5)") {
        Then("after the payment expires, GET shows EXPIRED while the same-key create replay still shows the creation-time result byte for byte") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val key = "idem-" + UUID.randomUUID().toString().take(12)
            val orderId = "ord-" + UUID.randomUUID().toString().take(12)
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            val first = api.post("/v1/payments", body, cred.key, key)
            first.status shouldBe 201
            val paymentId = json(first.body).get("paymentId").stringValue()
            json(get(cred, paymentId).body).get("status").stringValue() shouldBe "REQUESTED"
            clock.set(ApiTestTimes.T0.plus(Duration.ofDays(1)))
            expiry.expireElapsed(ExpireElapsedRequestedPaymentsCommand("cpq-t5-" + UUID.randomUUID().toString().take(8), 100))
            json(get(cred, paymentId).body).get("status").stringValue() shouldBe "EXPIRED"
            val replay = api.post("/v1/payments", body, cred.key, key)
            replay.header("Idempotent-Replayed") shouldBe "true"
            replay.body shouldBe first.body
        }
    }

    Given("other methods, query parameters and bodies (H-1, CQ-01)") {
        Then("HEAD is 200 without a body; POST is 405; a query parameter or a body is 400 INVALID_REQUEST") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            raw("HEAD", "/v1/payments/${a.paymentId.value}", cred).let {
                it.statusCode() shouldBe 200
                it.body() shouldBe ""
            }
            raw("POST", "/v1/payments/${a.paymentId.value}", cred, "{}").statusCode() shouldBe 405
            CapturedLogs().use { logs ->
                raw("GET", "/v1/payments/${a.paymentId.value}?expand=all", cred).let {
                    it.statusCode() shouldBe 400
                    json(it.body()).get("error").get("code").stringValue() shouldBe "INVALID_REQUEST"
                    // 최종 검토 m-1: INVALID_REQUEST 결과 줄 — 제시한 paymentId 없음.
                    outcomeLines(logs, it.headers().firstValue("PayCore-Request-Id").get()).single().let { line ->
                        line shouldContain "result=INVALID_REQUEST"
                        line shouldContain "keyIdPrefix=${cred.keyId.take(KEY_ID_PREFIX)}"
                        line shouldNotContain a.paymentId.value
                    }
                }
            }
            raw("GET", "/v1/payments/${a.paymentId.value}", cred, "{\"x\":1}").let {
                it.statusCode() shouldBe 400
                json(it.body()).get("error").get("code").stringValue() shouldBe "INVALID_REQUEST"
            }
        }
    }

    Given("the reader's transaction cannot begin (E-1, m-2)") {
        Then("503 TEMPORARILY_UNAVAILABLE with the query wording and Retry-After; the log has the failure class only") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            CommitFaults.armBeginFailure("JooqPaymentStatusReader")
            CapturedLogs().use { logs ->
                val r = get(cred, a.paymentId.value)
                r.status shouldBe 503
                val e = json(r.body).get("error")
                e.get("code").stringValue() shouldBe "TEMPORARILY_UNAVAILABLE"
                e.get("retryable").booleanValue() shouldBe true
                e.get("message").stringValue() shouldNotContain "Idempotency-Key"
                r.header("Retry-After") shouldNotBe null
                r.body shouldNotContain a.paymentId.value
                logs.lines().joinToString("\n") shouldContain "operation=GetPayment"
                logs.lines().joinToString("\n") shouldNotContain "injected: could not acquire"
                outcomeLines(logs, r.header("PayCore-Request-Id")!!).single().let { line ->
                    line shouldContain "result=UNAVAILABLE"
                    line shouldNotContain a.paymentId.value
                }
            }
            get(cred, a.paymentId.value).status shouldBe 200
        }
    }

    Given("a snapshot that breaks a CQ-07 rule (Invalid Fixture — a stored row changed underneath)") {
        Then("500 INTERNAL_ERROR without any payment data; the log names the rule only") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            db.withRoot { c ->
                with(db) { c.update("UPDATE ${db.databaseName}.payment SET status = 'AUTHORIZING' WHERE payment_id = ?", a.paymentId.value) }
            }
            CapturedLogs().use { logs ->
                val r = get(cred, a.paymentId.value)
                r.status shouldBe 500
                json(r.body).get("error").get("code").stringValue() shouldBe "INTERNAL_ERROR"
                r.body shouldNotContain a.paymentId.value
                logs.lines().joinToString("\n") shouldContain "rule=PAYMENT_AUTHORIZATION_SUMMARY"
                outcomeLines(logs, r.header("PayCore-Request-Id")!!).single().let { line ->
                    line shouldContain "result=INTERNAL_ERROR"
                    line shouldNotContain a.paymentId.value
                }
            }
        }
    }
})
