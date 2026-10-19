package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentPlan
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * confirm 공개 API(AUTH-001 §4.1·§4.9 매핑) — 실제 Tomcat·보안 필터·MySQL·운영 Adapter·stub VAN. HTTP 상태·code·본문 필드·재생 헤더·Retry-After를 명세 표에서 직접 적는다.
 * 응답에 카드번호·인증 증거·기관 원문·기관 계약계정이 없음을 본다.
 */
@PaymentApiTest
class ConfirmPaymentApiSpec(
    env: SpringEnvironment,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val mapper = JsonMapper.builder().build()

    fun prepared(cred: TestCredentials.Issued, plan: PaymentPlan = PaymentPlan.LumpSum, amount: Long = 10_000) =
        fixture.prepared(order = OrderKey(cred.environment, MerchantAccountId(cred.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().take(12))), plan = plan, amount = amount)

    fun body(p: ConfirmFixture.Prepared, amount: Long = p.money.amountMinor, ref: String = p.authenticationRef.value) =
        """{"authenticationRef":"$ref","merchantOrderId":"${p.order.merchantOrderId.value}","amount":$amount,"currency":"${p.money.currency.value}"}"""

    fun confirm(cred: TestCredentials.Issued, p: ConfirmFixture.Prepared, key: String = ConfirmFixture.newKey(), body: String = body(p), id: String = p.paymentId.value) =
        api.post("/v1/payments/$id/confirm", body, cred.key, key)

    fun json(s: String) = mapper.readTree(s)

    Given("an approved confirm") {
        Then("200 with paymentId, authorizationId, status AUTHORIZED and the approved terms; no card, evidence, institution account or raw message") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val p = prepared(cred)
            val r = confirm(cred, p)
            withClue(r.body) { r.status shouldBe 200 }
            val b = json(r.body)
            b.get("paymentId").stringValue() shouldBe p.paymentId.value
            b.get("authorizationId").stringValue() shouldNotBe null
            b.get("status").stringValue() shouldBe "AUTHORIZED"
            b.get("approvedAmount").longValue() shouldBe 10_000
            b.get("currency").stringValue() shouldBe "KRW"
            b.get("plan").get("kind").stringValue() shouldBe "LUMP_SUM"
            b.get("cardType").stringValue() shouldBe "CREDIT"
            b.has("approvalNumber") shouldBe true
            b.has("approvedAt") shouldBe true
            r.header("Idempotent-Replayed") shouldBe "false"
            listOf(ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF, "sim-ev-", "evidenceRef", "decisionId", "providerTransactionId").forEach { r.body shouldNotContain it }
        }
        Then("the same key replays 200 with Idempotent-Replayed true and no new institution call") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val p = prepared(cred)
            val key = ConfirmFixture.newKey()
            confirm(cred, p, key).status shouldBe 200
            val again = confirm(cred, p, key)
            again.status shouldBe 200
            again.header("Idempotent-Replayed") shouldBe "true"
            van.authRequestsFor(cred.mid).size shouldBe 1
        }
    }

    Given("results that are not final within the confirm") {
        Then("PENDING is 202 UNKNOWN with the retry guidance; DECLINED is 200 FAILED with failureCategory DECLINED") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val pending = prepared(cred)
            van.onAuth(cred.mid) { van.pending(it) }
            val r = confirm(cred, pending)
            r.status shouldBe 202
            json(r.body).get("status").stringValue() shouldBe "UNKNOWN"
            val cred2 = TestCredentials.issue(db, environment = Environment.TEST)
            van.onAuth(cred2.mid) { van.decline(it) }
            val declined = confirm(cred2, prepared(cred2))
            declined.status shouldBe 200
            json(declined.body).get("status").stringValue() shouldBe "FAILED"
            json(declined.body).get("failureCategory").stringValue() shouldBe "DECLINED"
        }
    }

    Given("error mapping (§4.9)") {
        Then("401 without a key, 400 for a malformed body or Idempotency-Key, 404 for another MID's or a malformed payment ID (not stored)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val p = prepared(cred)
            api.post("/v1/payments/${p.paymentId.value}/confirm", body(p), null, ConfirmFixture.newKey()).status shouldBe 401
            confirm(cred, p, body = "{}").status shouldBe 400
            api.post("/v1/payments/${p.paymentId.value}/confirm", body(p), cred.key, null).status shouldBe 400
            val other = TestCredentials.issue(db, environment = Environment.TEST)
            val notFound = confirm(other, p)
            notFound.status shouldBe 404
            json(notFound.body).get("error").get("code").stringValue() shouldBe "PAYMENT_NOT_FOUND"
            confirm(cred, p, id = "bad%20id").status shouldBe 404
        }
        Then("409 CONFIRM_CONTENT_CONFLICT and AUTHENTICATION_NOT_VERIFIED, 422 PAYMENT_PLAN_NOT_ALLOWED and PAYMENT_NOT_ELIGIBLE(reason), 409 PAYMENT_TERMINATED") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val p = prepared(cred)
            json(confirm(cred, p, body = body(p, amount = 9_999)).body).get("error").get("code").stringValue() shouldBe "CONFIRM_CONTENT_CONFLICT"
            val notVerified = confirm(cred, p, body = body(p, ref = "aref_" + "N".repeat(22)))
            notVerified.status shouldBe 409
            json(notVerified.body).get("error").get("code").stringValue() shouldBe "AUTHENTICATION_NOT_VERIFIED"
            val plan = confirm(cred, prepared(cred, plan = PaymentPlan.Installment(12), amount = 60_000))
            plan.status shouldBe 422
            json(plan.body).get("error").get("code").stringValue() shouldBe "PAYMENT_PLAN_NOT_ALLOWED"
            val terminatedCred = TestCredentials.issue(db, environment = Environment.TEST)
            val terminated = prepared(terminatedCred)
            van.onAuth(terminatedCred.mid) { van.decline(it) }
            confirm(terminatedCred, terminated).status shouldBe 200
            val again = confirm(terminatedCred, terminated, body = body(terminated))
            again.status shouldBe 409
            json(again.body).get("error").get("code").stringValue() shouldBe "PAYMENT_TERMINATED"
        }
        Then("503 TEMPORARILY_UNAVAILABLE with Retry-After for an unavailable admission, nothing stored, and the same key later succeeds") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val p = prepared(cred)
            admission.respondFor(MerchantAccountId(cred.mid)) { AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_MISSING) }
            val key = ConfirmFixture.newKey()
            val r = confirm(cred, p, key)
            r.status shouldBe 503
            json(r.body).get("error").get("code").stringValue() shouldBe "TEMPORARILY_UNAVAILABLE"
            (r.header("Retry-After") != null) shouldBe true
            fixture.idempotencyRows(p) shouldBe emptyList()
            admission.respondFor(MerchantAccountId(cred.mid)) { ScriptedAdmissionPort.admitted(it) }
            confirm(cred, p, key).status shouldBe 200
        }
        Then("422 PAYMENT_NOT_ELIGIBLE carries the fixed reason") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            admission.respondFor(MerchantAccountId(cred.mid)) { AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED) }
            val r = confirm(cred, prepared(cred))
            r.status shouldBe 422
            json(r.body).get("error").get("code").stringValue() shouldBe "PAYMENT_NOT_ELIGIBLE"
            json(r.body).get("error").get("reason").stringValue() shouldBe "CARD_COMPANY_NOT_ENABLED"
        }
    }
})
