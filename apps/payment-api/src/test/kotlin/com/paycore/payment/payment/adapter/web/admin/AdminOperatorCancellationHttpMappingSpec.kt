package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.OperatorCancelMalformedRecorder
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.domain.ReturnStatus
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

/**
 * HTTP 매핑 전수(ADMIN-PAYMENT-OPS-001 §4.5 `AdminOperatorCancellationHttpMappingSpec`, 서블릿 mock): 결과·예외 → HTTP를 표 그대로 고정하고 **불변식**을 같이 확인한다 — 409·404는 Use Case가
 * 커밋된 결정으로 돌려준 Rejected·NotFound일 때만, 400은 형식, 그 밖(일시 실패·commit 불명확·예상 못한 예외·감사 저장 실패·busy)은 503(500 없음). `OperatorCancelRejectionCode` 전 값을 반복하므로
 * enum이 늘면 이 시험이 매핑 누락을 잡는다.
 */
class AdminOperatorCancellationHttpMappingSpec : BehaviorSpec({

    val settings = AdminOpsSettings(readPermits = 4, commandPermits = 1, publicPoolReserve = 4)
    val body = AdminOpsTestClient.body(null, paymentId = AdminOpsTestClient.newPaymentId(), amountMinor = 7000, currency = "KRW")

    fun call(useCase: OperatorCorrectionCancelUseCase, recorder: OperatorCancelMalformedRecorder = mockk(relaxed = true), b: String = body, permits: AdminChannelPermits? = null) =
        AdminOperatorCancellationController(useCase, recorder, permits ?: AdminChannelPermits(settings, SimpleMeterRegistry()), SimpleMeterRegistry())
            .execute(MockHttpServletRequest("POST", AdminOpsTestClient.EXECUTE).apply { setContent(b.toByteArray()) })

    fun respond(result: OperatorCorrectionCancelResult) = call(mockk { every { cancel(any()) } returns result })
    fun fail(e: RuntimeException) = call(mockk { every { cancel(any()) } throws e })
    fun json(r: org.springframework.http.ResponseEntity<ByteArray>) = String(r.body!!)

    Given("every Use Case result") {
        Then("Accepted is 200 with cancellationId, status and existing") {
            val view = CancellationView(
                PaymentId("pay_x"), CancellationId("pcn_1"), CancellationViewStatus.IN_PROGRESS, Money(7000, CurrencyCode("KRW")), null, null, null, null, 3000, ReturnStatus.NONE,
            )
            respond(OperatorCorrectionCancelResult.Accepted(view, existing = true)).let {
                it.statusCode.value() shouldBe 200
                json(it) shouldBe """{"cancellationId":"pcn_1","status":"IN_PROGRESS","existing":true}"""
            }
        }
        Then("every rejection code is 409 with that code, NotFound is 404 PAYMENT_NOT_FOUND (decision-backed results only)") {
            OperatorCancelRejectionCode.entries.forEach { code ->
                withClue(code) {
                    val r = respond(OperatorCorrectionCancelResult.Rejected(code))
                    r.statusCode.value() shouldBe 409
                    json(r) shouldBe """{"error":"${code.name}"}"""
                }
            }
            respond(OperatorCorrectionCancelResult.NotFound).let {
                it.statusCode.value() shouldBe 404
                json(it) shouldBe """{"error":"PAYMENT_NOT_FOUND"}"""
            }
        }
    }

    Given("results that are not decisions are 503 (never 409, 404 or 500)") {
        Then("TemporarilyUnavailable (any reason), OutcomeUnknown, an unexpected exception and a failing malformed audit") {
            fail(CancelPaymentFailure.TemporarilyUnavailable("ANY")).let { it.statusCode.value() shouldBe 503; json(it) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}""" }
            fail(CancelPaymentFailure.OutcomeUnknown(RuntimeException())).let { it.statusCode.value() shouldBe 503; json(it) shouldBe """{"error":"OUTCOME_UNKNOWN"}""" }
            fail(IllegalStateException("boom")).let { it.statusCode.value() shouldBe 503; json(it) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}""" }
            val recorder = mockk<OperatorCancelMalformedRecorder> { every { record(any()) } throws CancelPaymentFailure.TemporarilyUnavailable("MALFORMED_AUDIT_UNAVAILABLE") }
            call(mockk(), recorder, b = "{}").let { it.statusCode.value() shouldBe 503; json(it) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}""" }
        }
        Then("no command permit is 503 ADMIN_COMMAND_BUSY without calling the Use Case (wait 0)") {
            val permits = AdminChannelPermits(settings, SimpleMeterRegistry())
            val blocker = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            val slow = mockk<OperatorCorrectionCancelUseCase> {
                every { cancel(any()) } answers { entered.countDown(); blocker.await(); OperatorCorrectionCancelResult.NotFound }
            }
            val t = Thread { call(slow, permits = permits) }.also { it.start() }
            entered.await()
            val untouched = mockk<OperatorCorrectionCancelUseCase>()
            val started = System.nanoTime()
            call(untouched, permits = permits).let { it.statusCode.value() shouldBe 503; json(it) shouldBe """{"error":"ADMIN_COMMAND_BUSY"}""" }
            (Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(200)) shouldBe true
            blocker.countDown()
            t.join()
        }
    }

    Given("format errors") {
        Then("are 400 with the malformed code after one audit call, and the Use Case is not called") {
            val recorder = mockk<OperatorCancelMalformedRecorder> { every { record(any()) } just runs }
            call(mockk(), recorder, b = body.replace("\"approvalId\":\"apr_", "\"approvalId\":\"bad_")).let {
                it.statusCode.value() shouldBe 400
                json(it) shouldBe """{"error":"APPROVAL_ID_MALFORMED"}"""
            }
            io.mockk.verify(exactly = 1) { recorder.record(any()) }
        }
    }
})
