package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
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
import com.paycore.payment.payment.domain.Money
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 관리자 거래 조회 ADMIN 채널(TXN-OPS-001 §3·AC 1·2·3·5 — 실제 보안 필터 체인 + 내부 connector + 실제 MySQL): 새 경로 2개의 서명 인증, 응답 모양, 400/404 code(요청 값 없음),
 * 응답 바이트 비노출(대조군 — 기관 내부 계정 값·운영자 사유가 DB에 실제로 있다), 반복 조회 동일·PAYMENT 표 불변(Idempotency), 로그에 서명·주문명·주문번호 없음.
 */
@PaymentApiTest
class AdminPaymentApiSpec(
    private val internal: InternalConnector,
    private val db: DbAccess,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val clock: MutablePaymentClock,
    confirm: ConfirmPaymentUseCase,
    van: ProgrammableVan,
    admission: ScriptedAdmissionPort,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = AdminOpsTestClient(internal.localPort)
    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val search = "/internal/v1/admin/payment-searches"
    fun detail(id: String) = "/internal/v1/admin/payments/$id"
    val fullName = "가나다라마바사아자차카타파하"

    fun unsigned(method: String, path: String, body: String = ""): Int {
        val r = HttpRequest.newBuilder(URI.create("http://localhost:${internal.localPort}$path")).timeout(Duration.ofSeconds(30))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build()
        return HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * 승인 + 운영자 보정 취소 1,000 + 가맹점 취소 2,000, 주문명 14자(표시값 — root로 바꾼다). 취소 순서가 시각으로 드러나게 시험 시계를 1초씩 민다(같은 시각이면 순서는 cancellationId 순 — §3.4).
     */
    fun scenario(): CancelFixture.Authorized {
        val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
        try {
            clock.set(ConfirmFixture.T0.plusSeconds(1))
            operator.cancel(a.operatorCommand(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
            clock.set(ConfirmFixture.T0.plusSeconds(2))
            cancel.cancel(a.command(money = Money(2_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
        } finally {
            clock.reset()
        }
        db.withRoot { c -> with(db) { c.update("UPDATE ${db.databaseName}.payment SET order_name = ? WHERE payment_id = ?", fullName, a.paymentId.value) } }
        return a
    }

    fun orderSearch(a: CancelFixture.Authorized) = """{"merchantAccountId":"${a.mid.value}","merchantOrderId":"${a.prepared.order.merchantOrderId.value}"}"""

    Given("the two new admin read paths") {
        Then("unsigned calls are 401 UNAUTHENTICATED (the existing signature filter); a query string is 400") {
            unsigned("POST", search, "{}") shouldBe 401
            unsigned("GET", detail(AdminOpsTestClient.newPaymentId())) shouldBe 401
            // 서명 필터 판정 순서: query 검사가 서명 검사보다 먼저(§4.2) — 서명이 맞아도 query가 있으면 400.
            api.get(detail(AdminOpsTestClient.newPaymentId()) + "?x=1").let {
                it.status shouldBe 400
                it.body shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
            }
        }
    }

    Given("an authorized payment with an operator correction cancel and a merchant cancel") {
        val a = scenario()
        Then("search returns the list shape with the 10-character order name; detail returns the full shape; unknown and malformed ids are 404 PAYMENT_NOT_FOUND") {
            val list = api.post(search, orderSearch(a))
            list.status shouldBe 200
            list.json.propertyNames().toList() shouldBe listOf("items", "nextCursor")
            list.json.get("nextCursor").isNull shouldBe true
            val item = list.json.get("items").single()
            item.propertyNames().toList() shouldBe listOf(
                // TXN-LIST-ENRICH-001 T-E1-1(기대값 변경): 11키 — `paymentMethod`가 `currency` 뒤·`status` 앞.
                "paymentId", "environment", "merchantAccountId", "merchantOrderId", "orderNamePreview", "orderNameTruncated", "amountMinor", "currency", "paymentMethod",
                "status", "createdAt",
            )
            item.get("paymentMethod").asString() shouldBe "CARD"
            item.get("paymentId").asString() shouldBe a.paymentId.value
            item.get("orderNamePreview").asString() shouldBe "가나다라마바사아자차"
            item.get("orderNameTruncated").asBoolean() shouldBe true
            item.get("createdAt").asString() shouldBe "2026-10-14T01:00:03.456Z"
            list.body shouldNotContain "카타파하"

            val d = api.get(detail(a.paymentId.value))
            d.status shouldBe 200
            d.json.propertyNames().toList() shouldBe listOf("payment", "onHold", "terminationEvidence", "authorization", "transactions", "cancellations", "returnBalance")
            d.json.get("payment").get("orderName").asString() shouldBe fullName
            d.json.get("payment").propertyNames().toList() shouldBe listOf(
                "paymentId", "environment", "merchantAccountId", "merchantSiteId", "merchantOrderId", "orderName", "amountMinor", "currency", "paymentMethod", "status", "createdAt",
            )
            d.json.get("authorization").propertyNames().toList() shouldBe
                listOf("authorizationId", "status", "failureEvidence", "planKind", "installmentMonths", "cardType", "openedAt")
            d.json.get("transactions").iterator().asSequence().map { it.get("type").asString() }.toList() shouldBe listOf("AUTHORIZATION", "CANCEL", "CANCEL")
            d.json.get("transactions").first().propertyNames().toList() shouldBe listOf(
                "transactionId", "type", "amountMinor", "currency", "planKind", "installmentMonths", "cardType", "approvalNumber", "providerTransactionId", "cancellationId",
                "originalTransactionId", "cancelScope", "institutionTransactionDate", "institutionBusinessDate", "confirmedAt", "recordedBy",
            )
            d.json.get("cancellations").iterator().asSequence().map { it.get("initiatorKind").asString() }.toList() shouldBe listOf("OPERATOR", "MERCHANT")
            d.json.get("cancellations").first().propertyNames().toList() shouldBe
                listOf("cancellationId", "initiatorKind", "merchantCancelId", "scope", "status", "failureEvidence", "amountMinor", "currency", "openedAt")
            d.json.get("returnBalance").propertyNames().toList() shouldBe listOf("currency", "authorizedMinor", "completedMinor", "reservedMinor", "forcedMinor", "revision")
            d.json.get("returnBalance").get("completedMinor").asLong() shouldBe 3_000

            listOf(AdminOpsTestClient.newPaymentId(), "pay_x", "PAY_01JC0000000000000000000001").forEach { id ->
                api.get(detail(id)).let {
                    it.status shouldBe 404
                    it.body shouldBe """{"error":"PAYMENT_NOT_FOUND"}"""
                }
            }
        }
        Then("a malformed search body is 400 REQUEST_MALFORMED without echoing any value; a not-found search has the same shape as a found one") {
            listOf(
                """{"merchantOrderId":"${a.prepared.order.merchantOrderId.value}"}""",
                """{"createdFrom":"2026-10-01T00:00:00.000Z","createdTo":"2026-11-02T00:00:00.000Z","merchantAccountId":"${a.mid.value}"}""",
                """{"paymentId":"${a.paymentId.value}","environment":"TEST"}""", "not json",
            ).forEach { body ->
                withClue(body) {
                    val r = api.post(search, body)
                    r.status shouldBe 400
                    r.body shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
            val none = api.post(search, """{"paymentId":"${AdminOpsTestClient.newPaymentId()}"}""")
            none.status shouldBe 200
            none.body shouldBe """{"items":[],"nextCursor":null}"""
            api.post(search, """{"paymentId":"${a.paymentId.value}"}""").json.get("items").size() shouldBe 1
        }
        Then("non-exposure: response bytes carry no institution account and no operator reason or purpose, though the database has them (control)") {
            val account = db.withRoot { c ->
                with(db) { c.scalar("SELECT institution_account FROM ${db.databaseName}.authorization_attempt WHERE authorization_id = ? LIMIT 1", a.authorizationId) as String }
            }
            val (reason, purposeRef, requestedBy) = db.withRoot { c ->
                with(db) {
                    c.rows("SELECT operator_reason, purpose_ref, requested_by FROM ${db.databaseName}.payment_cancellation WHERE payment_id = ? AND initiator_kind = 'OPERATOR'", a.paymentId.value)
                        .single().let { Triple(it["operator_reason"] as String, it["purpose_ref"] as String, it["requested_by"] as String) }
                }
            }
            listOf(account, reason, purposeRef, requestedBy).forEach { it.isNotBlank() shouldBe true }
            val bodies = listOf(api.post(search, orderSearch(a)).body, api.get(detail(a.paymentId.value)).body)
            bodies.forEach { body ->
                body shouldContain a.paymentId.value
                body shouldNotContain account
                body shouldNotContain reason
                body shouldNotContain purposeRef
                body shouldNotContain requestedBy
                // 기관 내부 계정 값의 키 이름도 없다(거래 사실의 기관 거래일·업무일 키는 명세 §3.4 표시 값이다).
                body shouldNotContain "institutionAccount"
                body shouldNotContain "institution_account"
                body shouldNotContain "operatorReason"
                body shouldNotContain "paymentKey"
            }
        }
        Then("Idempotency: repeating the same searches and detail returns identical bodies and leaves every PAYMENT table unchanged (row counts and checksums)") {
            fun snapshot(): Map<String, String> = db.withRoot { c ->
                with(db) {
                    val tables = c.rows("SELECT table_name AS t FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name", db.databaseName)
                        .map { (it["t"] ?: it["T"] ?: it["TABLE_NAME"]) as String }
                    tables.associateWith { t ->
                        val count = c.scalar("SELECT COUNT(*) FROM ${db.databaseName}.`$t`").toString()
                        val sum = c.rows("CHECKSUM TABLE ${db.databaseName}.`$t`").single().let { it["Checksum"].toString() }
                        "$count/$sum"
                    }
                }
            }
            val calls = listOf<() -> AdminOpsTestClient.Response>(
                { api.post(search, orderSearch(a)) },
                { api.post(search, """{"paymentId":"${a.paymentId.value}"}""") },
                { api.post(search, """{"createdFrom":"2026-10-14T00:00:00.000Z","createdTo":"2026-10-15T00:00:00.000Z","merchantAccountId":"${a.mid.value}"}""") },
                { api.get(detail(a.paymentId.value)) },
                { api.get(detail(AdminOpsTestClient.newPaymentId())) },
            )
            val before = snapshot()
            before.isNotEmpty() shouldBe true
            val first = calls.map { it().let { r -> r.status to r.body } }
            val second = calls.map { it().let { r -> r.status to r.body } }
            second shouldBe first
            first.map { it.first } shouldBe listOf(200, 200, 200, 200, 404)
            snapshot() shouldBe before
        }
        Then("Non-exposure in logs: no signature header or v1 value, no order name, merchant order id or MID on accepted and rejected calls") {
            val header = AdminChannelFixture.header("GET", detail(a.paymentId.value), ByteArray(0))
            val lines = CapturedLogs().use { logs ->
                api.post(search, orderSearch(a)).status shouldBe 200
                api.get(detail(a.paymentId.value)).status shouldBe 200
                api.post(search, """{"merchantOrderId":"${a.prepared.order.merchantOrderId.value}"}""").status shouldBe 400
                unsigned("GET", detail(a.paymentId.value)) shouldBe 401
                logs.lines()
            }
            withClue("control: the rejected call was logged") { lines.any { it.contains("AdminChannelAuthenticate") }.shouldBe(true) }
            lines.shouldNotBeEmpty()
            lines.forEach { line ->
                line shouldNotContain header.substringAfter("v1=")
                line shouldNotContain "PayCore-Admin-Signature"
                line shouldNotContain "가나다라"
                line shouldNotContain a.prepared.order.merchantOrderId.value
                line shouldNotContain a.mid.value
                line shouldNotContain AdminChannelFixture.secret(AdminChannelFixture.K1)
            }
        }
    }
})
