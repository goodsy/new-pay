package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
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
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import org.springframework.core.env.Environment as SpringEnvironment

/**
 * 취소 공개 API(CANCEL-001 §4.1·§4.9 매핑) — 실제 Tomcat·보안 필터·MySQL·운영 Adapter. HTTP 상태·code·재생 헤더·Retry-After를 명세 표에서 직접 적는다. 확정 응답(200 COMPLETED/FAILED)의
 * 본문 값은 전송(S6)과 함께 본다 — 여기서는 시작된 취소가 2xx이고 계약 필드를 갖는 것까지. 응답에 기관 계약계정·기관 원문·카드번호가 없음을 본다.
 */
@PaymentApiTest
class CancelPaymentApiSpec(
    env: SpringEnvironment,
    private val admission: ScriptedAdmissionPort,
    private val van: com.paycore.payment.payment.application.ProgrammableVan,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)
    val mapper = JsonMapper.builder().build()

    fun authorized(cred: TestCredentials.Issued, routeRef: String = CancelFixture.ROUTE_C) = fixture.authorized(
        routeRef = routeRef, order = OrderKey(cred.environment, MerchantAccountId(cred.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().take(12))),
    )

    fun cancel(cred: TestCredentials.Issued, paymentId: String, body: String, key: String? = CancelFixture.newKey()) =
        api.post("/v1/payments/$paymentId/cancel", body, cred.key, key)

    fun body(amount: Long = 10_000, merchantCancelId: String = CancelFixture.newMerchantCancelId(), currency: String = "KRW") =
        """{"merchantCancelId":"$merchantCancelId","amount":$amount,"currency":"$currency"}"""

    fun json(s: String) = mapper.readTree(s)

    Given("a cancel of an authorized payment") {
        Then("2xx with paymentId, cancellationId, status, amount, currency, cancellableAmount and returnStatus; no institution account, raw message or card data") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            val r = cancel(cred, a.paymentId.value, body(amount = 3_000))
            withClue(r.body) { r.status shouldBeIn listOf(200, 202) }
            val b = json(r.body)
            b.get("paymentId").stringValue() shouldBe a.paymentId.value
            b.get("cancellationId").stringValue().startsWith("pcn_") shouldBe true
            b.get("status").stringValue() shouldBeIn listOf("IN_PROGRESS", "UNKNOWN", "COMPLETED", "FAILED")
            b.get("amount").longValue() shouldBe 3_000
            b.get("currency").stringValue() shouldBe "KRW"
            b.has("cancellableAmount") shouldBe true
            b.has("returnStatus") shouldBe true
            r.header("Idempotent-Replayed") shouldBe "false"
            listOf(ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF, "cr_", "decisionId", "routeProfile", "capability").forEach { r.body shouldNotContain it }
        }
    }

    Given("requests rejected before the use case") {
        Then("no credential 401; malformed body 400; bad Idempotency-Key 400; unknown, other-MID or malformed paymentId 404 — nothing stored") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val other = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            api.post("/v1/payments/${a.paymentId.value}/cancel", body(), null, CancelFixture.newKey()).status shouldBe 401
            cancel(cred, a.paymentId.value, """{"merchantCancelId":"x","amount":1,"currency":"KRW","reason":"r"}""").let { r ->
                r.status shouldBe 400
                json(r.body).get("error").get("code").stringValue() shouldBe "INVALID_REQUEST"
            }
            cancel(cred, a.paymentId.value, body(), key = "bad key with space").let { r ->
                r.status shouldBe 400
                json(r.body).get("error").get("code").stringValue() shouldBe "INVALID_IDEMPOTENCY_KEY"
            }
            cancel(cred, a.paymentId.value, body(), key = null).status shouldBe 400
            listOf(
                cancel(cred, "pay_doesnotexist0000000000", body()),
                cancel(other, a.paymentId.value, body()),
                cancel(cred, "%20bad", body()),
            ).forEach { r ->
                r.status shouldBe 404
                json(r.body).get("error").get("code").stringValue() shouldBe "PAYMENT_NOT_FOUND"
            }
            fixture.cancellationRows(a) shouldBe 0
            fixture.count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'CancelPayment' AND merchant_account_id IN (?, ?)", cred.mid, other.mid) shouldBe 0
        }
    }

    Given("stored business rejections (§4.9)") {
        Then("409 CANCEL_REQUEST_CONFLICT, 422 CANCEL_AMOUNT_EXCEEDS_BALANCE / CURRENCY_MISMATCH, each with referencePaymentId") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            cancel(cred, a.paymentId.value, body(amount = 3_000, merchantCancelId = "mc-api-0001")).status shouldBeIn listOf(200, 202)
            // 업무 키 충돌은 진행 중 검사보다 먼저(§4.3-3). 금액·통화 검사는 진행 중 취소가 없는 다른 Payment로 본다(진행 중이면 CANCEL_IN_PROGRESS가 먼저 — CancelStart 순서).
            val b = authorized(cred)
            mapOf(
                (a to body(amount = 7_000, merchantCancelId = "mc-api-0001")) to (409 to "CANCEL_REQUEST_CONFLICT"),
                (b to body(amount = 99_999)) to (422 to "CANCEL_AMOUNT_EXCEEDS_BALANCE"),
                (b to body(amount = 1_000, currency = "USD")) to (422 to "CURRENCY_MISMATCH"),
            ).forEach { (request, expected) ->
                val (target, payload) = request
                val r = cancel(cred, target.paymentId.value, payload)
                withClue(r.body) {
                    r.status shouldBe expected.first
                    json(r.body).get("error").get("code").stringValue() shouldBe expected.second
                    json(r.body).get("error").get("referencePaymentId").stringValue() shouldBe target.paymentId.value
                }
            }
        }
        Then("202 UNKNOWN with the retry notice while the VAN answers PENDING; then 409 CANCEL_IN_PROGRESS for another cancel (stored)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            van.onCancel(cred.mid) { van.pendingCancel(it) }
            val first = cancel(cred, a.paymentId.value, body(amount = 3_000))
            first.status shouldBe 202
            json(first.body).get("status").stringValue() shouldBe "UNKNOWN"
            json(first.body).has("notice") shouldBe true
            val r = cancel(cred, a.paymentId.value, body(amount = 1_000))
            r.status shouldBe 409
            json(r.body).get("error").get("code").stringValue() shouldBe "CANCEL_IN_PROGRESS"
        }
        Then("200 COMPLETED with cancelTransactionId, cancelApprovalNumber and cancelledAt; 200 FAILED with failureCategory DECLINED") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            val ok = cancel(cred, a.paymentId.value, body())
            ok.status shouldBe 200
            json(ok.body).get("status").stringValue() shouldBe "COMPLETED"
            json(ok.body).get("cancelTransactionId").stringValue().startsWith("ptx_") shouldBe true
            json(ok.body).has("cancelApprovalNumber") shouldBe true
            json(ok.body).has("cancelledAt") shouldBe true
            json(ok.body).get("returnStatus").stringValue() shouldBe "FULLY_CANCELLED"
            val cred2 = TestCredentials.issue(db, environment = Environment.TEST)
            val b = authorized(cred2)
            van.onCancel(cred2.mid) { van.declineCancel(it) }
            val declined = cancel(cred2, b.paymentId.value, body())
            declined.status shouldBe 200
            json(declined.body).get("status").stringValue() shouldBe "FAILED"
            json(declined.body).get("failureCategory").stringValue() shouldBe "DECLINED"
        }
        Then("a hold on the payment is 409 ORDER_ON_HOLD (stored)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            fixture.holdOn(a)
            val r = cancel(cred, a.paymentId.value, body())
            r.status shouldBe 409
            json(r.body).get("error").get("code").stringValue() shouldBe "ORDER_ON_HOLD"
        }
        Then("the same key and body replays a stored rejection with Idempotent-Replayed true; another body under the key is 409 IDEMPOTENCY_KEY_REUSED") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            val k = CancelFixture.newKey()
            val b = body(amount = 99_999)
            cancel(cred, a.paymentId.value, b, key = k).status shouldBe 422
            cancel(cred, a.paymentId.value, b, key = k).let { r ->
                r.status shouldBe 422
                r.header("Idempotent-Replayed") shouldBe "true"
            }
            cancel(cred, a.paymentId.value, body(amount = 88_888), key = k).let { r ->
                r.status shouldBe 409
                json(r.body).get("error").get("code").stringValue() shouldBe "IDEMPOTENCY_KEY_REUSED"
            }
        }
    }

    Given("a route that cannot cancel (sim-van-1 — §6.1)") {
        Then("503 TEMPORARILY_UNAVAILABLE with Retry-After, nothing stored") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred, routeRef = ScriptedAdmissionPort.ROUTE_REF)
            val r = cancel(cred, a.paymentId.value, body())
            r.status shouldBe 503
            json(r.body).get("error").get("code").stringValue() shouldBe "TEMPORARILY_UNAVAILABLE"
            (r.header("Retry-After") != null) shouldBe true
            fixture.cancellationRows(a) shouldBe 0
        }
    }

    // K-3(API): 신규 결제 중지 상태(Admission 거절)여도 Credential이 유효하면 가맹점 취소는 시작된다.
    Given("K-3 — the merchant's new payments are suspended") {
        Then("a cancel with a valid credential still starts") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val a = authorized(cred)
            admission.respondFor(MerchantAccountId(cred.mid)) { AdmissionResult.Refused(AdmissionRefusalReason.entries.first()) }
            cancel(cred, a.paymentId.value, body()).status shouldBeIn listOf(200, 202)
        }
    }
})
