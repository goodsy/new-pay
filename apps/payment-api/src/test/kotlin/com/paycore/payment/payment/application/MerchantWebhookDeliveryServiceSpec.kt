package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.required.ClaimedDelivery
import com.paycore.payment.payment.application.required.DeliveryAttemptResult
import com.paycore.payment.payment.application.required.DeliveryRecordOutcome
import com.paycore.payment.payment.application.required.MerchantWebhookAttempt
import com.paycore.payment.payment.application.required.MerchantWebhookAttemptStore
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryRepository
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryTimings
import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookEventReader
import com.paycore.payment.payment.application.required.MerchantWebhookSendRequest
import com.paycore.payment.payment.application.required.MerchantWebhookSendResult
import com.paycore.payment.payment.application.required.MerchantWebhookSender
import com.paycore.payment.payment.application.required.MerchantWebhookSigner
import com.paycore.payment.payment.application.required.MerchantWebhookSigningKeys
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoredMerchantWebhookEvent
import com.paycore.payment.payment.application.required.WebhookSigningKey
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * delivery Use Case 단위 시험(WEBHOOK-001 §5.0 rev 0.2 delivery·§5.5~§5.8, m-2·n-1·n-3·n-4, D-WH-003·006). 저장·전송 Port는 기록형 대역(실 DB·실 HTTP는 `MerchantWebhookDeliveryDbSpec`).
 * 기대값은 명세에서 직접 적는다: claim TX(시도 번호 예약) → TX 밖 전송 → 결과 TX(결과 반영 + 시도 행). 전송 전 본문 sha256 재계산, endpoint·키 없으면 보내지 않는다.
 */
class MerchantWebhookDeliveryServiceSpec : BehaviorSpec({

    val t0 = Instant.parse("2026-10-22T01:00:00Z")
    val schedule = WebhookRetrySchedule("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.0, 10, Duration.ofHours(24))
    val mid = MerchantAccountId("mid_delivery_a")
    val body = """{"eventId":"whe_01JB0000000000000000000001","type":"payment.expired"}""".toByteArray()
    fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    class Fakes(val t0: Instant, schedule: WebhookRetrySchedule, val body: ByteArray, val bodySha: String, mid: MerchantAccountId, budget: Duration) {
        val calls = mutableListOf<String>()
        val claimed = mutableListOf<ClaimedDelivery>()
        val results = mutableListOf<Triple<String, Int, DeliveryAttemptResult>>()
        val attempts = mutableListOf<MerchantWebhookAttempt>()
        val sent = mutableListOf<MerchantWebhookSendRequest>()
        var sendResult: (MerchantWebhookSendRequest) -> MerchantWebhookSendResult = { MerchantWebhookSendResult.Succeeded(204) }
        var storedSha = bodySha
        var endpoint: MerchantWebhookEndpoint? = MerchantWebhookEndpoint("ep-a", "https://merchant-a.example.test/webhooks")
        var keys = listOf(WebhookSigningKey("kid-1", "whsec_test_a".toByteArray()))
        var recordOutcome = DeliveryRecordOutcome.DELIVERED

        /** releaseClaim 응답(true = 되돌림, false = 그 사이 claim을 잃음). 예외를 던지면 저장소 실패. */
        var releaseAnswer: () -> Boolean = { true }
        var now = t0
        var commits = 0

        val repository = object : MerchantWebhookDeliveryRepository {
            override fun insertNew(delivery: MerchantWebhookDelivery) = error("not used")

            override fun claimDue(now: Instant, limit: Int, owner: String, claimUntil: Instant): List<ClaimedDelivery> {
                calls += "claim:$limit:${Duration.between(now, claimUntil).seconds}"
                return claimed.take(limit)
            }

            override fun releaseClaim(id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int): Boolean {
                calls += "release:${id.value}:$attemptNo"
                released += id.value to attemptNo
                return releaseAnswer()
            }

            override fun recordResult(
                id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int, result: DeliveryAttemptResult, at: Instant, schedule: WebhookRetrySchedule, jitterFraction: Double,
            ): DeliveryRecordOutcome {
                calls += "record:${id.value}:$attemptNo"
                results += Triple(id.value, attemptNo, result)
                return recordOutcome
            }
        }
        val attemptStore = object : MerchantWebhookAttemptStore {
            override fun append(attempt: MerchantWebhookAttempt) {
                calls += "attempt:${attempt.attemptNo}:${attempt.outcome}"
                attempts += attempt
            }
        }
        val reader = object : MerchantWebhookEventReader {
            override fun find(id: MerchantWebhookEventId): StoredMerchantWebhookEvent =
                StoredMerchantWebhookEvent(id, "payment.expired", body, storedSha)
        }
        val directory = object : MerchantWebhookEndpointDirectory {
            override fun find(environment: Environment, merchantAccountId: MerchantAccountId) = endpoint
        }
        val signingKeys = object : MerchantWebhookSigningKeys {
            override fun active(environment: Environment, merchantAccountId: MerchantAccountId) = keys
        }
        val signer = object : MerchantWebhookSigner {
            override fun signatureHeader(timestampSeconds: Long, body: ByteArray, keys: List<WebhookSigningKey>): String =
                "t=$timestampSeconds," + keys.joinToString(",") { "kid=${it.keyId},v1=sig-${body.size}" }
        }
        val sender = object : MerchantWebhookSender {
            override fun send(request: MerchantWebhookSendRequest): MerchantWebhookSendResult {
                calls += "send"
                sent += request
                return sendResult(request)
            }
        }
        val tm = object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) {
                commits++
            }
            override fun rollback(status: TransactionStatus) = Unit
        }
        val timings = object : MerchantWebhookDeliveryTimings {
            override val schedule = schedule
            override val claimLease: Duration = Duration.ofSeconds(60)
            override val attemptBudget: Duration = budget
        }
        val service = MerchantWebhookDeliveryService(
            tm, repository, attemptStore, reader, directory, signingKeys, signer, sender, timings,
            object : PaymentClock {
                override fun now(): Instant = now
            },
        )

        var claimUntil: Instant = t0.plusSeconds(60)
        val released = mutableListOf<Pair<String, Int>>()

        fun claim(n: Int, attemptNo: Int = 1, env: Environment = Environment.TEST) {
            val d = MerchantWebhookDelivery.pending(
                MerchantWebhookDeliveryId("whd_01JB%022d".format(n)), MerchantWebhookEventId("whe_01JB%022d".format(n)), env, mid, t0,
            )
            repeat(attemptNo) { d.startAttempt(t0) }
            claimed += ClaimedDelivery(d.state(), attemptNo, claimUntil)
        }
    }

    fun fakes(budget: Duration = Duration.ofSeconds(3)) = Fakes(t0, schedule, body, sha(body), mid, budget)

    Given("a claimed delivery whose merchant answers 204") {
        val f = fakes()
        f.claim(1)
        val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))

        Then("claim (lease = configured) → send outside the TX → result and attempt row with the reserved number") {
            f.calls shouldBe listOf("claim:20:60", "send", "record:whd_01JB0000000000000000000001:1", "attempt:1:SUCCESS")
            f.commits shouldBe 2
            f.results.single().third shouldBe DeliveryAttemptResult.Success(204)
            report.claimed shouldBe 1
            report.delivered shouldBe 1
        }
        Then("the request carries the stored body bytes, the signature over the injected clock seconds, and the public headers (n-7: values are in the body)") {
            val r = f.sent.single()
            r.body.contentEquals(body) shouldBe true
            r.url shouldBe "https://merchant-a.example.test/webhooks"
            r.environment shouldBe Environment.TEST
            r.headers shouldBe linkedMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "PayCore-Webhook/1",
                "PayCore-Event-Id" to "whe_01JB0000000000000000000001",
                "PayCore-Event-Type" to "payment.expired",
                "PayCore-Delivery-Attempt" to "1",
                "PayCore-Signature" to "t=${t0.epochSecond},kid=kid-1,v1=sig-${body.size}",
            )
        }
        Then("the attempt row records the endpoint ref, the key ids and the schedule version — never the URL or the body") {
            val a = f.attempts.single()
            a.endpointRef shouldBe "ep-a"
            a.signingKeyIds shouldBe "kid-1"
            a.httpStatus shouldBe 204
            a.scheduleVersion shouldBe "webhook-retry-demo-1"
        }
    }

    Given("failures before anything is sent") {
        Then("a stored body whose SHA-256 no longer matches is not sent (n-4) and is recorded as BODY_INTEGRITY") {
            val f = fakes()
            f.claim(2)
            f.storedSha = sha("tampered".toByteArray())
            f.recordOutcome = DeliveryRecordOutcome.RETRY_SCHEDULED
            f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent shouldBe emptyList()
            f.results.single().third shouldBe DeliveryAttemptResult.Failure("BODY_INTEGRITY", null)
        }
        Then("no endpoint (e.g. LIVE — D-WH-004) or no signing key is not sent and is recorded with its code") {
            val f = fakes()
            f.claim(3)
            f.endpoint = null
            f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.claim(4)
            f.endpoint = MerchantWebhookEndpoint("ep-a", "https://merchant-a.example.test/webhooks")
            f.keys = emptyList()
            f.claimed.removeAt(0)
            f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent shouldBe emptyList()
            f.results.map { it.third } shouldBe listOf(DeliveryAttemptResult.Failure("NO_ENDPOINT", null), DeliveryAttemptResult.Failure("NO_SIGNING_KEY", null))
        }
    }

    Given("transport failures and non-2xx answers") {
        Then("each becomes a failure with its fixed code; an unexpected sender exception is DEFECT (the row still gets its result)") {
            val f = fakes()
            (5..8).forEach { f.claim(it) }
            val answers = ArrayDeque(
                listOf<() -> MerchantWebhookSendResult>(
                    { MerchantWebhookSendResult.Failed("HTTP_500", 500) },
                    { MerchantWebhookSendResult.Failed("HTTP_410", 410) },
                    { MerchantWebhookSendResult.Failed("TIMEOUT", null) },
                    { error("boom") },
                ),
            )
            f.sendResult = { answers.removeFirst()() }
            f.recordOutcome = DeliveryRecordOutcome.RETRY_SCHEDULED
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.results.map { it.third } shouldBe listOf(
                DeliveryAttemptResult.Failure("HTTP_500", 500), DeliveryAttemptResult.Failure("HTTP_410", 410),
                DeliveryAttemptResult.Failure("TIMEOUT", null), DeliveryAttemptResult.Failure("DEFECT", null),
            )
            report.retried shouldBe 4
        }
    }

    Given("a second claim of the same delivery after a lease takeover (m-2)") {
        Then("the reserved attempt number is sent and recorded (two workers leave two attempt rows)") {
            val f = fakes()
            f.claim(9, attemptNo = 2)
            f.recordOutcome = DeliveryRecordOutcome.CLAIM_LOST
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent.single().headers["PayCore-Delivery-Attempt"] shouldBe "2"
            f.attempts.single().attemptNo shouldBe 2
            report.claimLost shouldBe 1
        }
    }

    Given("H-1: the claim lease can no longer cover one attempt (now + budget > claimUntil)") {
        Then("the delivery is not sent; the claim and its reserved number are released (no attempt row, no result)") {
            val f = fakes(budget = Duration.ofSeconds(22))
            f.claim(10)
            f.claimUntil = t0.plusSeconds(30)
            f.claimed[0] = f.claimed[0].copy(claimUntil = t0.plusSeconds(21))
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent shouldBe emptyList()
            f.released shouldBe listOf("whd_01JB0000000000000000000010" to 1)
            f.results shouldBe emptyList()
            f.attempts shouldBe emptyList()
            report.released shouldBe 1
        }
        Then("the boundary is inclusive (now + budget == claimUntil still sends)") {
            val f = fakes(budget = Duration.ofSeconds(22))
            f.claim(11)
            f.claimed[0] = f.claimed[0].copy(claimUntil = t0.plusSeconds(22))
            f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent.size shouldBe 1
            f.released shouldBe emptyList()
        }
    }

    Given("n-2 (final review): one claim never takes more deliveries than the lease can cover one after another") {
        Then("the claim limit is min(batch, floor(lease / attempt budget)) — lease 60s, budget 22s, batch 20 → 2") {
            val f = fakes(budget = Duration.ofSeconds(22))
            f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.calls.first() shouldBe "claim:2:60"
        }
        Then("a batch smaller than the cap is used as is (budget 3s → cap 20, batch 5)") {
            val f = fakes()
            f.service.deliver(DeliverMerchantWebhooksCommand(5))
            f.calls.first() shouldBe "claim:5:60"
        }
    }

    Given("m-1 (final review): metric inputs of a round") {
        Then("a blocked or rejected destination is counted as blocked, and every sent attempt (only those) has a duration") {
            val f = fakes()
            (11..13).forEach { f.claim(it) }
            val answers = ArrayDeque(
                listOf<() -> MerchantWebhookSendResult>(
                    { MerchantWebhookSendResult.Failed("DESTINATION_BLOCKED", null) },
                    { MerchantWebhookSendResult.Failed("DESTINATION_REJECTED", null) },
                    { MerchantWebhookSendResult.Succeeded(200) },
                ),
            )
            f.sendResult = { answers.removeFirst()() }
            f.recordOutcome = DeliveryRecordOutcome.RETRY_SCHEDULED
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            report.blocked shouldBe 2
            report.attemptDurations.size shouldBe 3
            report.attemptDurations.all { !it.isNegative } shouldBe true
        }
        Then("released and not-sent deliveries have no duration") {
            val f = fakes(budget = Duration.ofSeconds(22))
            f.claim(14)
            f.claimed[0] = f.claimed[0].copy(claimUntil = t0.plusSeconds(21))
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            report.released shouldBe 1
            report.attemptDurations shouldBe emptyList()
            report.blocked shouldBe 0
        }
    }

    Given("n-1 (final review #3): the outcome of a lease-short release is counted by what the store answered") {
        fun leaseShort(f: Fakes, n: Int) {
            f.claim(n)
            f.claimed[0] = f.claimed[0].copy(claimUntil = t0.plusSeconds(21))
        }
        Then("releaseClaim true → released 1 (claimLost 0, recordFailed 0)") {
            val f = fakes(budget = Duration.ofSeconds(22))
            leaseShort(f, 20)
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            report.released shouldBe 1
            report.claimLost shouldBe 0
            report.recordFailed shouldBe 0
        }
        Then("releaseClaim false (the claim was lost or the row changed meanwhile) → claimLost 1, not released") {
            val f = fakes(budget = Duration.ofSeconds(22))
            leaseShort(f, 21)
            f.releaseAnswer = { false }
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.released shouldBe listOf("whd_01JB0000000000000000000021" to 1)
            f.sent shouldBe emptyList()
            f.results shouldBe emptyList()
            report.released shouldBe 0
            report.claimLost shouldBe 1
            report.recordFailed shouldBe 0
        }
        Then("a store failure of the release (non-retryable or retries exhausted) → recordFailed 1, not released") {
            val f = fakes(budget = Duration.ofSeconds(22))
            leaseShort(f, 22)
            f.releaseAnswer = { throw IllegalStateException("store down") }
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(20))
            f.sent shouldBe emptyList()
            report.released shouldBe 0
            report.claimLost shouldBe 0
            report.recordFailed shouldBe 1

            val g = fakes(budget = Duration.ofSeconds(22))
            leaseShort(g, 23)
            g.releaseAnswer = { throw PersistenceFailure.Deadlock(RuntimeException("1213")) }
            val exhausted = g.service.deliver(DeliverMerchantWebhooksCommand(20))
            g.released.size shouldBe 3
            exhausted.released shouldBe 0
            exhausted.recordFailed shouldBe 1
        }
    }

    Given("nothing due") {
        Then("only the claim TX runs") {
            val f = fakes()
            val report = f.service.deliver(DeliverMerchantWebhooksCommand(5))
            f.calls shouldBe listOf("claim:5:60")
            report.claimed shouldBe 0
        }
    }
})
