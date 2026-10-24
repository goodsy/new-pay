package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksUseCase
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsUseCase
import com.paycore.payment.payment.application.provided.MerchantNotificationFanoutReport
import com.paycore.payment.payment.application.provided.MerchantWebhookDeliveryReport
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * WEBHOOK-001 §11 지표(최종 검토 m-1 — AUTH-001 adapter 지표 선례): trigger가 회차 결과를 counter `paycore.webhook.fanout{result}`·`paycore.webhook.delivery{result}`와
 * timer `paycore.webhook.delivery.duration`(보낸 시도마다 한 번)으로 남긴다. label은 `result` 하나뿐(ID·URL·MID 없음). domain·application은 Micrometer를 모른다.
 */
class MerchantWebhookSchedulersSpec : FunSpec({

    test("delivery: every result of a round is counted under result only, and each sent attempt is timed") {
        val report = MerchantWebhookDeliveryReport(
            claimed = 12, delivered = 3, alreadyDelivered = 1, retried = 2, gaveUp = 1, claimLost = 1, recordFailed = 1, released = 2, blocked = 1,
            attemptDurations = listOf(Duration.ofMillis(120), Duration.ofMillis(80), Duration.ofSeconds(2)),
        )
        val calls = mutableListOf<DeliverMerchantWebhooksCommand>()
        val useCase = object : DeliverMerchantWebhooksUseCase {
            override fun deliver(command: DeliverMerchantWebhooksCommand): MerchantWebhookDeliveryReport = report.also { calls += command }
        }
        val registry = SimpleMeterRegistry()
        MerchantWebhookDeliveryScheduler(useCase, MerchantWebhookDeliveryJobSettings(enabled = true, fixedDelay = Duration.ofSeconds(1), batchSize = 40), registry).runOnce()
        calls.map { it.batchSize } shouldBe listOf(40)
        mapOf(
            "delivered" to 3.0, "already_delivered" to 1.0, "retry" to 2.0, "gave_up" to 1.0, "claim_lost" to 1.0, "record_failed" to 1.0,
            "released_lease_short" to 2.0, "blocked" to 1.0,
        ).forEach { (result, n) -> withClue(result) { registry.counter("paycore.webhook.delivery", "result", result).count() shouldBe n } }
        registry.find("paycore.webhook.delivery").counters().forEach { c -> c.id.tags.map { it.key } shouldBe listOf("result") }
        val timer = registry.find("paycore.webhook.delivery.duration").timers().single()
        timer.id.tags shouldBe emptyList()
        timer.count() shouldBe 3
        timer.totalTime(TimeUnit.MILLISECONDS) shouldBe 2_200.0
    }

    test("fan-out: every result of a round is counted under result only") {
        val useCase = object : FanOutMerchantNotificationsUseCase {
            override fun fanOut(command: FanOutMerchantNotificationsCommand) =
                MerchantNotificationFanoutReport(claimed = 10, notified = 5, noEndpoint = 2, suppressed = 1, notApplicable = 1, failed = 1, deferred = 1)
        }
        val registry = SimpleMeterRegistry()
        MerchantNotificationFanoutScheduler(
            useCase, MerchantNotificationFanoutJobSettings(enabled = true, fixedDelay = Duration.ofSeconds(1), batchSize = 40, failedRetryInterval = Duration.ofMinutes(1)), registry,
        ).runOnce()
        mapOf("notified" to 3.0, "no_endpoint" to 2.0, "suppressed" to 1.0, "not_applicable" to 1.0, "failed" to 1.0, "deferred" to 1.0)
            .forEach { (result, n) -> withClue(result) { registry.counter("paycore.webhook.fanout", "result", result).count() shouldBe n } }
        registry.find("paycore.webhook.fanout").counters().forEach { c -> c.id.tags.map { it.key } shouldBe listOf("result") }
    }
})
