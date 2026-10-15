package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlStore
import com.paycore.payment.payment.adapter.persistence.JpaPaymentStore
import com.paycore.payment.payment.application.EligibilityCall
import com.paycore.payment.payment.application.RecordingEligibilityPort
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * CreatePayment 공개 API 시나리오(실서버·실제 MySQL·운영 Credential 검증 경로). 초안 CP-01·CP-02·CP-05·CP-06·CP-07(일부)·CP-12, 응답 schema·
 * `Idempotent-Replayed`·현재 상태 미포함(D-CP-003), 저장 거절 재생(D-CPC-007), eligibility 매핑(계약 §9), PaymentRequested envelope(§10).
 * 요청 media type·가맹점 X-Request-Id 정제(명세 §4.1), 오류 본문·헤더 비노출(§7.5)도 여기서 본다(review round1 m-3·m-4).
 * 인증·격리는 ApiSecuritySpec, 장애·경합·로그는 ApiFailureSpec.
 */
@PaymentApiTest
class CreatePaymentApiSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val eligibility: RecordingEligibilityPort,
    payments: JpaPaymentStore,
    controls: JpaOrderPaymentControlStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    fun post(cred: TestCredentials.Issued, body: String, key: String) = api.post("/v1/payments", body, cred.key, key)

    /** 저장된 업무 거절의 재생(D-CPC-007): 같은 409 code·referencePaymentId, `Idempotent-Replayed: true`. */
    fun replayed(r: ApiClient.Response, code: String, referencePaymentId: String) {
        r.status shouldBe 409
        r.header("Idempotent-Replayed") shouldBe "true"
        s.json(r.body).get("error").get("code").stringValue() shouldBe code
        s.json(r.body).get("error").get("referencePaymentId").stringValue() shouldBe referencePaymentId
        s.json(r.body).get("error").get("retryable").booleanValue() shouldBe false
    }

    fun orderOf(cred: TestCredentials.Issued, orderId: String) = OrderKey(cred.environment, MerchantAccountId(cred.mid), MerchantOrderId(orderId))

    Given("CP-01: a new order through the public API") {
        Then("201 with the fixed response schema; one payment, control, record and event; the event follows contract §10") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, amount = 1050, currency = "USD", orderName = "주문명 🍀"), s.newKey())

            r.status shouldBe 201
            r.header("Idempotent-Replayed") shouldBe "false"
            val requestId = r.header("PayCore-Request-Id")!!
            val body = s.json(r.body)
            body.properties().map { it.key }.toSet() shouldBe
                setOf("paymentId", "paymentKey", "outcome", "lifecycleAtCreation", "paymentCreatedAt", "merchantOrderId", "amount", "currency")
            body.get("outcome").stringValue() shouldBe "CREATED"
            body.get("lifecycleAtCreation").stringValue() shouldBe "REQUESTED"
            body.get("paymentCreatedAt").stringValue() shouldBe "2026-10-14T01:02:03.456Z"
            body.get("merchantOrderId").stringValue() shouldBe orderId
            body.get("amount").isIntegralNumber shouldBe true
            body.get("amount").longValue() shouldBe 1050L
            body.get("currency").stringValue() shouldBe "USD"
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)

            val paymentId = body.get("paymentId").stringValue()
            db.withApp { c -> with(db) { c.rows("SELECT created_by_credential_id a, status st FROM payment WHERE payment_id = ?", paymentId) } }.single() shouldBe
                mapOf("a" to cred.keyId, "st" to "REQUESTED")
            val event = db.withApp { c -> with(db) { c.rows("SELECT * FROM outbox_event WHERE aggregate_id = ?", paymentId) } }.single()
            event["event_type"] shouldBe "PaymentRequested"
            event["schema_version"].toString() shouldBe "1"
            event["event_category"] shouldBe "INTEGRATION_NON_FINANCIAL"
            event["producer"] shouldBe "payment-api/payment"
            event["aggregate_type"] shouldBe "Payment"
            event["aggregate_version"].toString() shouldBe "0"
            event["correlation_id"] shouldBe requestId
            event["effect_key"] shouldBe "PaymentRequested:$paymentId"
            val payload = s.json(event["payload"].toString())
            payload.properties().map { it.key }.toSet() shouldBe setOf(
                "environment", "merchantAccountId", "merchantSiteId", "merchantOrderId", "paymentId", "amount", "currency", "paymentMethod",
                "slotGeneration", "orderIntentFingerprint", "lifecycle", "requestedAt",
            )
            payload.get("amount").longValue() shouldBe 1050L
            event["payload"].toString() shouldNotContain body.get("paymentKey").stringValue()
            event["payload"].toString() shouldNotContain "주문명"
            event["causation_id"] shouldBe s.scalar("SELECT request_record_id FROM idempotency_request WHERE result_payment_id = ?", paymentId)
        }
    }

    Given("the same key and the same request (D-CP-003)") {
        Then("the first result is replayed byte for byte with 200 and Idempotent-Replayed: true, without the current state") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val orderId = s.newOrderId()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            val first = post(cred, body, key)
            val paymentId = s.json(first.body).get("paymentId").stringValue()
            // 그 사이 승인이 시작돼 현재 상태가 바뀌어도 생성 응답은 생성 당시 값이다(현재 상태는 조회 API — CP-QUERY-001).
            s.change(cred, orderId, paymentId) { p, c -> c.startAuthorization(p) }

            val replay = post(cred, CreatePaymentRequests.reordered().replace(CreatePaymentRequests.MID, cred.mid).replace(CreatePaymentRequests.ORDER_ID, orderId)
                .replace("}", ",\"orderName\":\"Demo order\"}"), key)
            replay.status shouldBe 200
            replay.header("Idempotent-Replayed") shouldBe "true"
            replay.body shouldBe first.body
            s.json(replay.body).get("lifecycleAtCreation").stringValue() shouldBe "REQUESTED"
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
        Then("the same key with only orderName changed is IDEMPOTENCY_KEY_REUSED (409) and nothing changes") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val orderId = s.newOrderId()
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), key).status shouldBe 201
            val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "Other"), key)
            r.status shouldBe 409
            s.json(r.body).get("error").get("code").stringValue() shouldBe "IDEMPOTENCY_KEY_REUSED"
            r.header("Idempotent-Replayed") shouldBe null
            r.header("Retry-After") shouldBe null
            s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("CP-05: a different key for the same order and the same fixed input") {
        listOf<Pair<String, (com.paycore.payment.payment.domain.Payment, com.paycore.payment.payment.domain.OrderPaymentControl) -> Unit>>(
            "REQUESTED" to { _, _ -> },
            "AUTHORIZING" to { p, c -> c.startAuthorization(p) },
            "UNKNOWN" to { p, c -> c.startAuthorization(p); p.markUnknown() },
        ).forEach { (state, prepare) ->
            Then("an active $state payment is reused: 200 EXISTING_ACTIVE_PAYMENT with the same paymentId, no new payment or event") {
                val cred = TestCredentials.issue(db)
                val orderId = s.newOrderId()
                val first = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "A"), s.newKey()).body)
                s.change(cred, orderId, first.get("paymentId").stringValue(), prepare)
                val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = "B"), s.newKey())
                r.status shouldBe 200
                r.header("Idempotent-Replayed") shouldBe "false"
                s.json(r.body).get("outcome").stringValue() shouldBe "EXISTING_ACTIVE_PAYMENT"
                s.json(r.body).get("paymentId").stringValue() shouldBe first.get("paymentId").stringValue()
                s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 2, outbox = 1)
            }
        }
        Then("a past authorization blocks a new payment: 409 ORDER_ALREADY_AUTHORIZED with the authorized payment as reference (refund is not modelled yet — see spec §19-7)") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val paymentId = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey()).body).get("paymentId").stringValue()
            s.change(cred, orderId, paymentId) { p, c -> c.startAuthorization(p); p.markAuthorized(); c.recordAuthorized(p) }
            val rejectedKey = s.newKey()
            val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), rejectedKey)
            r.status shouldBe 409
            r.header("Idempotent-Replayed") shouldBe "false"
            replayed(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), rejectedKey), "ORDER_ALREADY_AUTHORIZED", paymentId)
            val error = s.json(r.body).get("error")
            error.get("code").stringValue() shouldBe "ORDER_ALREADY_AUTHORIZED"
            error.get("referencePaymentId").stringValue() shouldBe paymentId
            error.get("retryable").booleanValue() shouldBe false
            s.rows(cred.mid).getValue("payment") shouldBe 1L
            s.scalar("SELECT authorized_payment_id FROM order_payment_control WHERE merchant_account_id = ?", cred.mid) shouldBe paymentId
        }
    }

    Given("CP-06: the current payment ended FAILED") {
        Then("with sufficient evidence a new key creates generation 2 (201); the old key still replays the original result") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val oldKey = s.newKey()
            val first = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), oldKey)
            val firstId = s.json(first.body).get("paymentId").stringValue()
            s.change(cred, orderId, firstId) { p, _ -> p.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE) }

            val second = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey())
            second.status shouldBe 201
            s.json(second.body).get("paymentId").stringValue() shouldNotBe firstId
            s.scalar("SELECT generation FROM order_payment_control WHERE merchant_account_id = ?", cred.mid).toString() shouldBe "2"
            val replay = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), oldKey)
            replay.status shouldBe 200
            replay.body shouldBe first.body
        }
        Then("without sufficient evidence the order is blocked (409 ORDER_RESULT_UNCONFIRMED) and the rejection replays for 30 days (D-CPC-007)") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val paymentId = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey()).body).get("paymentId").stringValue()
            s.change(cred, orderId, paymentId) { p, _ -> p.fail(null) }
            val key = s.newKey()
            val rejected = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), key)
            rejected.status shouldBe 409
            s.json(rejected.body).get("error").get("code").stringValue() shouldBe "ORDER_RESULT_UNCONFIRMED"
            s.json(rejected.body).get("error").get("referencePaymentId").stringValue() shouldBe paymentId
            rejected.header("Idempotent-Replayed") shouldBe "false"

            // 그 사이 충분한 근거가 확인돼도 같은 키는 저장된 거절을 재생한다. 새 고객 시도는 새 키로만.
            s.change(cred, orderId, paymentId) { p, _ -> p.addTerminationEvidence(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE) }
            val replay = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), key)
            replayed(replay, "ORDER_RESULT_UNCONFIRMED", paymentId)
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey()).status shouldBe 201
        }
        listOf<Triple<String, (com.paycore.payment.payment.domain.Payment) -> Unit, Boolean>>(
            Triple("FAILED after timeout", { p -> p.fail(TerminationEvidence.TIMEOUT) }, false),
            Triple("FAILED after inquiry NOT_FOUND", { p -> p.fail(TerminationEvidence.INQUIRY_NOT_FOUND) }, false),
            Triple("FAILED after lease expiry", { p -> p.fail(TerminationEvidence.LEASE_EXPIRED) }, false),
            Triple("EXPIRED after lease expiry", { p -> p.expire(TerminationEvidence.LEASE_EXPIRED) }, false),
            Triple("EXPIRED without evidence", { p -> p.expire(null) }, false),
            Triple("EXPIRED, not transmitted and session discarded", { p -> p.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED) }, true),
            Triple("FAILED, pre-transmission failure confirmed", { p -> p.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED) }, true),
        ).forEach { (case, prepare, safe) ->
            Then("$case: a new key ${if (safe) "creates generation 2 (201)" else "is blocked with 409 ORDER_RESULT_UNCONFIRMED (timeout, NOT_FOUND and lease expiry are no proof of no effect)"}") {
                val cred = TestCredentials.issue(db)
                val orderId = s.newOrderId()
                val paymentId = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey()).body).get("paymentId").stringValue()
                s.change(cred, orderId, paymentId) { p, _ -> prepare(p) }
                val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey())
                if (safe) {
                    r.status shouldBe 201
                    s.rows(cred.mid) shouldBe s.rows(payment = 2, control = 1, idempotency = 2, outbox = 2)
                } else {
                    r.status shouldBe 409
                    s.json(r.body).get("error").get("code").stringValue() shouldBe "ORDER_RESULT_UNCONFIRMED"
                    s.json(r.body).get("error").get("referencePaymentId").stringValue() shouldBe paymentId
                    s.rows(cred.mid) shouldBe s.rows(payment = 1, control = 1, idempotency = 2, outbox = 1)
                }
            }
        }
    }

    Given("other stored business rejections (contract §9, D-CPC-007)") {
        Then("a different fixed input is ORDER_INTENT_CONFLICT; an incident hold is ORDER_ON_HOLD with the reference payment; both replay") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val first = s.json(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), s.newKey()).body).get("paymentId").stringValue()
            val conflictKey = s.newKey()
            val conflict = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, amount = 9_999), conflictKey)
            conflict.status shouldBe 409
            s.json(conflict.body).get("error").get("code").stringValue() shouldBe "ORDER_INTENT_CONFLICT"
            conflict.body shouldNotContain "10000"
            s.json(conflict.body).get("error").get("referencePaymentId").stringValue() shouldBe first
            replayed(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, amount = 9_999), conflictKey), "ORDER_INTENT_CONFLICT", first)

            s.change(cred, orderId, first) { p, c -> p.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE); c.reportSuccessAfterTermination(p) }
            val holdKey = s.newKey()
            val hold = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), holdKey)
            hold.status shouldBe 409
            s.json(hold.body).get("error").get("code").stringValue() shouldBe "ORDER_ON_HOLD"
            s.json(hold.body).get("error").get("referencePaymentId").stringValue() shouldBe first
            replayed(post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId), holdKey), "ORDER_ON_HOLD", first)
            s.rows(cred.mid).getValue("payment") shouldBe 1L
        }
    }

    Given("eligibility refusals (contract §9 — not stored, so the same key is evaluated again)") {
        Then("Site not found 404, not eligible 422 with the fixed reason, unavailable 503 with Retry-After and no internal cause") {
            val cred = TestCredentials.issue(db)
            val order = orderOf(cred, s.newOrderId())
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = order.merchantOrderId.value)
            val answers = listOf<(EligibilityCall) -> EligibilityResult>(
                { EligibilityResult.NotFoundOrForbidden },
                { EligibilityResult.NotEligible(NotEligibleReason.CONTRACT_NOT_EFFECTIVE) },
                { EligibilityResult.Unavailable(EligibilityUnavailableReason.SNAPSHOT_MISSING) },
            )
            val responses = answers.map { answer ->
                eligibility.respondFor(order, answer)
                post(cred, body, key)
            }
            responses.map { it.status } shouldBe listOf(404, 422, 503)
            responses.map { s.json(it.body).get("error").get("code").stringValue() } shouldBe
                listOf("MERCHANT_SITE_NOT_FOUND", "PAYMENT_NOT_ELIGIBLE", "ELIGIBILITY_UNAVAILABLE")
            s.json(responses[1].body).get("error").get("reason").stringValue() shouldBe "CONTRACT_NOT_EFFECTIVE"
            responses.map { it.header("Retry-After") } shouldBe listOf(null, null, "2")
            responses[2].body shouldNotContain "SNAPSHOT_MISSING"
            s.json(responses[2].body).get("error").get("retryable").booleanValue() shouldBe true
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)

            eligibility.respondFor(order) { RecordingEligibilityPort.eligible(it) }
            post(cred, body, key).status shouldBe 201
        }
        Then("CP-02/CP-07: every not-eligible reason is 422 PAYMENT_NOT_ELIGIBLE with that fixed reason (METHOD_NOT_ENABLED included), no Retry-After, nothing stored") {
            val cred = TestCredentials.issue(db)
            val order = orderOf(cred, s.newOrderId())
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = order.merchantOrderId.value)
            NotEligibleReason.entries.forEach { reason ->
                eligibility.respondFor(order) { EligibilityResult.NotEligible(reason) }
                val r = post(cred, body, s.newKey())
                r.status shouldBe 422
                s.json(r.body).get("error").get("code").stringValue() shouldBe "PAYMENT_NOT_ELIGIBLE"
                s.json(r.body).get("error").get("reason").stringValue() shouldBe reason.name
                s.json(r.body).get("error").get("retryable").booleanValue() shouldBe false
                r.header("Retry-After") shouldBe null
                r.header("Idempotent-Replayed") shouldBe null
            }
            NotEligibleReason.entries.map { it.name }.toSet() shouldBe
                setOf("CURRENCY_NOT_ENABLED", "METHOD_NOT_ENABLED", "CONTRACT_NOT_EFFECTIVE", "MERCHANT_SUSPENDED")
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
        Then("CP-02: a currency in a valid format but not enabled is 422 CURRENCY_NOT_ENABLED; invalid amounts are 400 with nothing stored") {
            val cred = TestCredentials.issue(db)
            val r = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId(), currency = "JPY"), s.newKey())
            r.status shouldBe 422
            s.json(r.body).get("error").get("reason").stringValue() shouldBe "CURRENCY_NOT_ENABLED"
            listOf("0", "-1", "1.5", "1e3", "\"100\"", "9223372036854775808").forEach { raw ->
                val bad = post(cred, CreatePaymentRequests.withRawAmount(raw).replace(CreatePaymentRequests.MID, cred.mid), s.newKey())
                bad.status shouldBe 400
                s.json(bad.body).get("error").get("code").stringValue() shouldBe "INVALID_AMOUNT"
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }

    Given("request media types (spec §4.1: application/json in UTF-8 only; Accept absent or JSON-compatible)") {
        Then("other Content-Type or Accept values are 400 INVALID_REQUEST before anything is stored; accepted variants create") {
            val cred = TestCredentials.issue(db)
            val body = { CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()) }
            fun send(headers: Map<String, String>) = api.post("/v1/payments", body(), cred.key, s.newKey(), headers)
            listOf(
                emptyMap(),
                mapOf("Content-Type" to "text/plain"),
                mapOf("Content-Type" to "application/xml"),
                mapOf("Content-Type" to "application/*"),
                mapOf("Content-Type" to "application/json; charset=ISO-8859-1"),
                mapOf("Content-Type" to "application/json; charset=UTF-16"),
                mapOf("Content-Type" to "application/json; charset=no-such-charset"),
                mapOf("Content-Type" to "application/json; charset=\"bad name\""),
                mapOf("Content-Type" to "not a media type"),
                mapOf("Content-Type" to "application/json", "Accept" to "text/html"),
                mapOf("Content-Type" to "application/json", "Accept" to "application/xml, text/plain"),
                mapOf("Content-Type" to "application/json", "Accept" to "not/a/media type"),
            ).forEach { headers ->
                val r = send(headers)
                r.status shouldBe 400
                s.json(r.body).get("error").get("code").stringValue() shouldBe "INVALID_REQUEST"
                s.json(r.body).get("error").has("field") shouldBe false
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            listOf(
                mapOf("Content-Type" to "application/json"),
                mapOf("Content-Type" to "application/json;charset=UTF-8"),
                mapOf("Content-Type" to "Application/JSON; charset=utf-8"),
                mapOf("Content-Type" to "application/json", "Accept" to "*/*"),
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                mapOf("Content-Type" to "application/json", "Accept" to "text/html, application/json;q=0.5"),
            ).forEach { headers -> send(headers).status shouldBe 201 }
        }
    }

    Given("the merchant X-Request-Id (spec §4.1: logged as merchantRequestId only when sanitised; never a business key)") {
        Then("a clean value is logged next to the server correlationId; an unclean value is dropped from the log; neither is echoed in the response") {
            val cred = TestCredentials.issue(db)
            val order = orderOf(cred, s.newOrderId())
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = order.merchantOrderId.value)
            eligibility.respondFor(order) { EligibilityResult.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED) }
            val clean = "merchant-req-" + s.newKey()
            val unclean = "bad id " + s.newKey()
            CapturedLogs().use { logs ->
                val a = api.post("/v1/payments", body, cred.key, s.newKey(), mapOf("Content-Type" to "application/json", "X-Request-Id" to clean))
                val b = api.post("/v1/payments", body, cred.key, s.newKey(), mapOf("Content-Type" to "application/json", "X-Request-Id" to unclean))
                listOf(a.status, b.status) shouldBe listOf(503, 503)
                val lineA = logs.lines().single { "result=ELIGIBILITY_UNAVAILABLE" in it && "correlationId=${a.header("PayCore-Request-Id")}" in it }
                lineA.contains("merchantRequestId=$clean") shouldBe true
                val lineB = logs.lines().single { "result=ELIGIBILITY_UNAVAILABLE" in it && "correlationId=${b.header("PayCore-Request-Id")}" in it }
                lineB.contains("merchantRequestId=null") shouldBe true
                logs.lines().forEach { it shouldNotContain unclean }
                (a.body + a.headers.toString()) shouldNotContain clean
                (b.body + b.headers.toString()) shouldNotContain unclean
            }
            // 가맹점 요청 ID는 업무 키가 아니다: 같은 X-Request-Id로 다른 주문을 만들 수 있다.
            eligibility.respondFor(order) { RecordingEligibilityPort.eligible(it) }
            listOf(s.newOrderId(), s.newOrderId()).map { id ->
                api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = id), cred.key, s.newKey(),
                    mapOf("Content-Type" to "application/json", "X-Request-Id" to clean)).status
            } shouldBe listOf(201, 201)
        }
    }

    Given("error bodies and headers never echo request values or existence (contract §9.1, spec §7.5)") {
        Then("every error response is free of the MID, Site, order ID, idempotency key, order name, amount and unknown field name that were sent") {
            val cred = TestCredentials.issue(db)
            val other = TestCredentials.issue(db)
            val marker = s.newKey().removePrefix("idem-")
            val orderId = "leakord-$marker"
            val site = "site_LEAK$marker"
            val key = "leakkey-$marker"
            val orderName = "LeakName$marker"
            val amount = 7_340_033L
            val markers = listOf(cred.mid, other.mid, orderId, site, key, orderName, amount.toString(), "leakfield$marker", marker)
            fun valid(mid: String = cred.mid, siteId: String = site, money: Long = amount, name: String = orderName) =
                CreatePaymentRequests.valid(mid = mid, site = siteId, orderId = orderId, amount = money, orderName = name)
            val order = orderOf(cred, orderId)

            val responses = mutableListOf<Pair<String, ApiClient.Response>>()
            fun record(label: String, r: ApiClient.Response) = responses.add(label to r)

            record("401", api.post("/v1/payments", valid(), null, key))
            record("400 key", api.post("/v1/payments", valid(), cred.key, "$key with space"))
            record("400 field", api.post("/v1/payments", valid().replace("}", ",\"leakfield$marker\":\"$marker\"}"), cred.key, key))
            record("400 amount", api.post("/v1/payments", valid().replace("$amount", "$amount.5"), cred.key, key))
            record("400 currency", api.post("/v1/payments", valid().replace("\"KRW\"", "\"k$marker\""), cred.key, key))
            record("400 orderName", api.post("/v1/payments", valid(name = "$orderName\n"), cred.key, key))
            record("403", api.post("/v1/payments", valid(mid = other.mid), cred.key, key))
            eligibility.respondFor(order) { EligibilityResult.NotFoundOrForbidden }
            record("404", api.post("/v1/payments", valid(), cred.key, key))
            eligibility.respondFor(order) { EligibilityResult.NotEligible(NotEligibleReason.METHOD_NOT_ENABLED) }
            record("422", api.post("/v1/payments", valid(), cred.key, key))
            eligibility.respondFor(order) { EligibilityResult.Unavailable(EligibilityUnavailableReason.SNAPSHOT_MISSING) }
            record("503", api.post("/v1/payments", valid(), cred.key, key))
            eligibility.respondFor(order) { RecordingEligibilityPort.eligible(it) }
            post(cred, valid(), key).status shouldBe 201
            record("409 key reused", api.post("/v1/payments", valid(name = "Other$marker"), cred.key, key))
            record("409 intent", api.post("/v1/payments", valid(money = amount + 1), cred.key, "leakkey2-$marker"))
            record("404 route", api.get("/v1/leak/$marker", cred.key))

            responses.map { it.first to it.second.status } shouldBe listOf(
                "401" to 401, "400 key" to 400, "400 field" to 400, "400 amount" to 400, "400 currency" to 400, "400 orderName" to 400,
                "403" to 403, "404" to 404, "422" to 422, "503" to 503, "409 key reused" to 409, "409 intent" to 409, "404 route" to 404,
            )
            responses.forEach { (label, r) ->
                val headers = r.headers.filterKeys { !it.equals("PayCore-Request-Id", ignoreCase = true) }.toString()
                markers.forEach { m ->
                    withClue("$label must not echo a request value") {
                        r.body shouldNotContain m
                        headers shouldNotContain m
                    }
                }
                // 고정 형식: error 객체 하나, 허용 키만.
                (s.json(r.body).get("error").properties().map { it.key }.toSet() -
                    setOf("code", "message", "retryable", "requestId", "field", "reason", "referencePaymentId")) shouldBe emptySet()
            }
        }
    }

    Given("CP-12: API identity equals DB key identity (no normalisation)") {
        Then("merchantOrderId differing only in case are two orders; a Site differing only in case is a different fixed input") {
            val cred = TestCredentials.issue(db)
            val base = s.newOrderId()
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = "A$base"), s.newKey()).status shouldBe 201
            post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = "a$base"), s.newKey()).status shouldBe 201
            val other = post(cred, CreatePaymentRequests.valid(mid = cred.mid, orderId = "A$base", site = CreatePaymentRequests.SITE.lowercase()), s.newKey())
            s.json(other.body).get("error").get("code").stringValue() shouldBe "ORDER_INTENT_CONFLICT"
            s.rows(cred.mid).getValue("payment") shouldBe 2L
        }
    }

    Given("environment and MID come from the credential, never from the body") {
        Then("TEST and LIVE credentials of the same MID create two separate orders for the same merchantOrderId") {
            val live = TestCredentials.issue(db)
            val test = TestCredentials.issue(db, mid = live.mid, environment = Environment.TEST)
            val orderId = s.newOrderId()
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = live.mid, orderId = orderId)
            val a = post(live, body, key)
            val b = post(test, body, key)
            listOf(a.status, b.status) shouldBe listOf(201, 201)
            s.json(a.body).get("paymentId") shouldNotBe s.json(b.body).get("paymentId")
            db.withApp { c -> with(db) { c.rows("SELECT environment e FROM payment WHERE merchant_account_id = ? ORDER BY environment", live.mid) } }
                .map { it["e"] } shouldBe listOf("LIVE", "TEST")
            eligibility.callsFor(OrderKey(Environment.TEST, MerchantAccountId(live.mid), MerchantOrderId(orderId))).last().environment shouldBe Environment.TEST
        }
    }

    Given("40 random valid requests (Fixture Monkey behind RandomCreatePaymentRequestFixture, seed fixed)") {
        Then("each is created once (201) and its same-key replay returns the identical body (200, replayed)") {
            val cred = TestCredentials.issue(db)
            val requests = RandomCreatePaymentRequestFixture.validRequests(cred.mid, count = 40, seed = 20261014L)
            requests.forEachIndexed { i, request ->
                val key = "bulk-$i-" + s.newKey()
                val first = post(cred, request.body, key)
                first.status shouldBe 201
                val replay = post(cred, request.body, key)
                replay.status shouldBe 200
                replay.body shouldBe first.body
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 40, control = 40, idempotency = 40, outbox = 40)
        }
    }

    Given("a payment's creation snapshot stays unchanged") {
        Then("the replayed lifecycleAtCreation stays REQUESTED even after the payment became AUTHORIZED") {
            val cred = TestCredentials.issue(db)
            val orderId = s.newOrderId()
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId)
            val first = post(cred, body, key)
            s.change(cred, orderId, s.json(first.body).get("paymentId").stringValue()) { p, c -> c.startAuthorization(p); p.markAuthorized(); c.recordAuthorized(p) }
            s.json(post(cred, body, key).body).get("lifecycleAtCreation").stringValue() shouldBe PaymentStatus.REQUESTED.name
        }
    }
})
