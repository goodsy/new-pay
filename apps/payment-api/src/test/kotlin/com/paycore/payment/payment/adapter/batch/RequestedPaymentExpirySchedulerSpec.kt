package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.ExpiryRunResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration

/**
 * 결제창 만료 주기 작업 trigger(AUTHN-001 §7.5.4, AN-28 — `adapter.batch`의 `@Scheduled`, provided Use Case만). 회차마다 새 회차 ID(UUID)와 설정 batch 크기로 Use Case를
 * 부르고 결과를 counter `paycore.payment.checkout_expiry{result}`로 센다(label에 ID 없음). 설정(`paycore.payment.checkout-expiry.job`)은 운영 기본값이 없고 형식 위반은 기동 실패.
 */
class RequestedPaymentExpirySchedulerSpec : FunSpec({

    test("one run calls the use case with a fresh run id and the configured batch size, and counts the results") {
        val calls = mutableListOf<ExpireElapsedRequestedPaymentsCommand>()
        val useCase = object : ExpireElapsedRequestedPaymentsUseCase {
            override fun expireElapsed(command: ExpireElapsedRequestedPaymentsCommand): ExpiryRunResult {
                calls += command
                return ExpiryRunResult(expired = 2, noChange = 1, failed = 3, defects = 1)
            }
        }
        val registry = SimpleMeterRegistry()
        val scheduler = RequestedPaymentExpiryScheduler(useCase, CheckoutExpiryJobSettings(enabled = true, fixedDelay = Duration.ofMinutes(1), batchSize = 100), registry)
        scheduler.runOnce()
        scheduler.runOnce()
        calls.map { it.batchSize } shouldBe listOf(100, 100)
        calls.forEach { it.runId shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") }
        (calls[0].runId != calls[1].runId) shouldBe true
        mapOf("expired" to 4.0, "no_change" to 2.0, "failed" to 6.0, "defect" to 2.0).forEach { (result, count) ->
            withClue(result) { registry.counter("paycore.payment.checkout_expiry", "result", result).count() shouldBe count }
        }
    }

    test("a disabled job does nothing when the schedule fires") {
        var called = false
        val useCase = object : ExpireElapsedRequestedPaymentsUseCase {
            override fun expireElapsed(command: ExpireElapsedRequestedPaymentsCommand): ExpiryRunResult {
                called = true
                return ExpiryRunResult(0, 0, 0, 0)
            }
        }
        RequestedPaymentExpiryScheduler(useCase, CheckoutExpiryJobSettings(enabled = false, fixedDelay = Duration.ofMinutes(1), batchSize = 100), SimpleMeterRegistry()).onSchedule()
        called shouldBe false
    }

    test("settings: batch size 1..1000 and a fixed delay of at least one second (start-up fails otherwise)") {
        listOf(0, 1001).forEach { size -> withClue(size) { shouldThrow<IllegalArgumentException> { CheckoutExpiryJobSettings(true, Duration.ofMinutes(1), size) } } }
        listOf(Duration.ZERO, Duration.ofMillis(999)).forEach { d -> withClue(d) { shouldThrow<IllegalArgumentException> { CheckoutExpiryJobSettings(true, d, 100) } } }
    }
})
