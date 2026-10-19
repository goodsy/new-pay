package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.provided.RecoveryRunResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration

/**
 * 승인 회복 주기 작업 trigger(AUTH-001 §6.1 — `adapter.batch`의 `@Scheduled`, Spring Batch 아님, provided Use Case만). 회차마다 새 회차 ID(UUID)와 설정 batch 크기로 Use Case를 부르고
 * 결과를 counter `paycore.payment.authorization_recovery{result}`로 센다(label에 ID 없음). 설정(`paycore.authorization.timing.recovery`)은 운영 기본값이 없다.
 */
class AuthorizationRecoverySchedulerSpec : FunSpec({

    test("one run calls the use case with a fresh run id and the configured batch size, and counts the results") {
        val calls = mutableListOf<RecoverAuthorizationsCommand>()
        val useCase = object : RecoverAuthorizationsUseCase {
            override fun recover(command: RecoverAuthorizationsCommand): RecoveryRunResult {
                calls += command
                return RecoveryRunResult(claimed = 7, fenced = 1, leaseExpired = 2, inquiries = 3, concluded = 2, escalated = 1, failed = 1)
            }
        }
        val registry = SimpleMeterRegistry()
        val scheduler = AuthorizationRecoveryScheduler(useCase, AuthorizationRecoveryJobSettings(enabled = true, fixedDelay = Duration.ofSeconds(5), batchSize = 50), registry)
        scheduler.runOnce()
        scheduler.runOnce()
        calls.map { it.batchSize } shouldBe listOf(50, 50)
        calls.forEach { it.runId shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") }
        (calls[0].runId != calls[1].runId) shouldBe true
        mapOf("fenced" to 2.0, "lease_expired" to 4.0, "inquiry" to 6.0, "concluded" to 4.0, "escalated" to 2.0, "failed" to 2.0).forEach { (result, count) ->
            withClue(result) { registry.counter("paycore.payment.authorization_recovery", "result", result).count() shouldBe count }
        }
    }

    test("a disabled job does nothing when the schedule fires") {
        var called = false
        val useCase = object : RecoverAuthorizationsUseCase {
            override fun recover(command: RecoverAuthorizationsCommand): RecoveryRunResult {
                called = true
                return RecoveryRunResult(0, 0, 0, 0, 0, 0, 0)
            }
        }
        AuthorizationRecoveryScheduler(useCase, AuthorizationRecoveryJobSettings(enabled = false, fixedDelay = Duration.ofSeconds(5), batchSize = 50), SimpleMeterRegistry())
            .onSchedule()
        called shouldBe false
    }

    test("settings: batch size 1..1000 and a fixed delay of at least one second (start-up fails otherwise)") {
        listOf(0, 1001).forEach { size -> withClue(size) { shouldThrow<IllegalArgumentException> { AuthorizationRecoveryJobSettings(true, Duration.ofSeconds(5), size) } } }
        listOf(Duration.ZERO, Duration.ofMillis(999)).forEach { d -> withClue(d) { shouldThrow<IllegalArgumentException> { AuthorizationRecoveryJobSettings(true, d, 50) } } }
    }

    test("run command: a run id and a positive batch size") {
        shouldThrow<IllegalArgumentException> { RecoverAuthorizationsCommand("", 10) }
        shouldThrow<IllegalArgumentException> { RecoverAuthorizationsCommand("run", 0) }
    }
})
