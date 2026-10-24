package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksUseCase
import com.paycore.payment.payment.application.provided.MerchantWebhookDeliveryReport
import com.paycore.payment.payment.application.required.ClaimedDelivery
import com.paycore.payment.payment.application.required.DeliveryAttemptResult
import com.paycore.payment.payment.application.required.DeliveryRecordOutcome
import com.paycore.payment.payment.application.required.MerchantWebhookAttempt
import com.paycore.payment.payment.application.required.MerchantWebhookAttemptStore
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryRepository
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryTimings
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookEventReader
import com.paycore.payment.payment.application.required.MerchantWebhookSendRequest
import com.paycore.payment.payment.application.required.MerchantWebhookSendResult
import com.paycore.payment.payment.application.required.MerchantWebhookSender
import com.paycore.payment.payment.application.required.MerchantWebhookSigner
import com.paycore.payment.payment.application.required.MerchantWebhookSigningKeys
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * 가맹점 Webhook 전송(WEBHOOK-001 §5.0 rev 0.2 delivery·§5.5~§5.8, m-2·n-1·n-3·n-4, D-WH-003·006·007).
 *
 * ```
 * TX1(claim): FOR UPDATE SKIP LOCKED → 시도 번호 예약(attempt_count +1)·claim 소유자·lease(주입 시계 기준 — n-3) → COMMIT
 * (TX 밖) 행마다: 저장 본문 SHA-256 재확인(다르면 보내지 않음 — n-4) → endpoint·서명 키 조회 → 서명(t = 주입 시계 초) → 전송(목적지 검사·기한·redirect 금지)
 * TX2(행마다): 결과 반영(성공 단조·소유자 실패만 반영) + 시도 행 INSERT(예약 번호)
 * ```
 * 금융 상태를 바꾸지 않는다(D-WH-006). 로그에는 deliveryId·시도 번호·결과 코드·endpointRef만(URL·본문·서명·비밀 없음).
 */
@Service
class MerchantWebhookDeliveryService(
    transactionManager: PlatformTransactionManager,
    private val deliveries: MerchantWebhookDeliveryRepository,
    private val attempts: MerchantWebhookAttemptStore,
    private val events: MerchantWebhookEventReader,
    private val endpoints: MerchantWebhookEndpointDirectory,
    private val signingKeys: MerchantWebhookSigningKeys,
    private val signer: MerchantWebhookSigner,
    private val sender: MerchantWebhookSender,
    private val timings: MerchantWebhookDeliveryTimings,
    private val clock: PaymentClock,
) : DeliverMerchantWebhooksUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(MerchantWebhookDeliveryService::class.java)

    /** 이 프로세스의 claim 소유자 이름(인스턴스마다 다르다). */
    val owner: String = "whw-" + UUID.randomUUID()

    /** [sent] = 전송 adapter까지 갔다(지표 timer 대상). */
    private class Attempted(val result: DeliveryAttemptResult, val endpointRef: String?, val keyIds: String?, val sent: Boolean = false)

    override fun deliver(command: DeliverMerchantWebhooksCommand): MerchantWebhookDeliveryReport {
        val now = now()
        // 최종 검토 n-2: 한 claim이 lease 안에 차례로 보낼 수 있는 수보다 많이 잡지 않는다 — min(batch, floor(lease / 한 시도 예산)), 최소 1(기동 검사 lease > 예산).
        val limit = minOf(command.batchSize, maxOf(1L, timings.claimLease.toMillis() / timings.attemptBudget.toMillis()).toInt())
        val claimed = transactions.execute(::isRetryable) { deliveries.claimDue(now, limit, owner, now.plus(timings.claimLease)) }
        val counts = mutableMapOf<DeliveryRecordOutcome, Int>()
        var recordFailed = 0
        var released = 0
        var releaseClaimLost = 0
        var blocked = 0
        val durations = mutableListOf<Duration>()
        for (c in claimed) {
            val started = now()
            // 검토 #2 H-1: 앞 전달이 느려 남은 lease가 한 시도 예산을 덮지 못하면 보내지 않고 claim·예약 번호를 되돌린다(다른 worker의 재claim·중복 전송·번호 소모 방지).
            if (started.plus(timings.attemptBudget).isAfter(c.claimUntil)) {
                // 최종 검토 #3 n-1: 저장소 답대로 센다 — true만 released, false(그 사이 claim을 잃음)는 claimLost, 저장소 실패는 recordFailed.
                when (release(c)) {
                    ReleaseOutcome.RELEASED -> released++
                    ReleaseOutcome.CLAIM_LOST -> releaseClaimLost++
                    ReleaseOutcome.FAILED -> recordFailed++
                }
                continue
            }
            val ticks = System.nanoTime()
            val attempted = attempt(c, started)
            if (attempted.sent) durations += Duration.ofNanos(System.nanoTime() - ticks)
            if ((attempted.result as? DeliveryAttemptResult.Failure)?.code in BLOCKED_CODES) blocked++
            val finished = now()
            try {
                val outcome = transactions.execute(::isRetryable) {
                    val o = deliveries.recordResult(
                        c.delivery.id, owner, c.attemptNo, attempted.result, finished, timings.schedule, ThreadLocalRandom.current().nextDouble(),
                    )
                    attempts.append(
                        MerchantWebhookAttempt(
                            c.delivery.id, c.attemptNo, owner, attempted.endpointRef, attempted.keyIds, outcomeCode(attempted.result), httpStatus(attempted.result),
                            timings.schedule.version, started, finished,
                        ),
                    )
                    o
                }
                counts.merge(outcome, 1, Int::plus)
                log.info(
                    "operation=MerchantWebhookDelivery deliveryId={} attemptNo={} result={} outcome={} endpointRef={}",
                    c.delivery.id.value, c.attemptNo, outcomeCode(attempted.result), outcome, attempted.endpointRef,
                )
            } catch (e: RuntimeException) {
                recordFailed++
                log.error("operation=MerchantWebhookDelivery deliveryId={} attemptNo={} result=RECORD_FAILED failure={}", c.delivery.id.value, c.attemptNo, e::class.simpleName)
            }
        }
        return MerchantWebhookDeliveryReport(
            claimed = claimed.size,
            delivered = counts[DeliveryRecordOutcome.DELIVERED] ?: 0,
            alreadyDelivered = counts[DeliveryRecordOutcome.ALREADY_DELIVERED] ?: 0,
            retried = counts[DeliveryRecordOutcome.RETRY_SCHEDULED] ?: 0,
            gaveUp = counts[DeliveryRecordOutcome.GAVE_UP] ?: 0,
            claimLost = (counts[DeliveryRecordOutcome.CLAIM_LOST] ?: 0) + releaseClaimLost,
            recordFailed = recordFailed,
            released = released,
            blocked = blocked,
            attemptDurations = durations,
        )
    }

    private enum class ReleaseOutcome { RELEASED, CLAIM_LOST, FAILED }

    /**
     * 보내지 않은 예약을 되돌린다. false = 그 사이 claim을 잃었거나 행 상태가 바뀌었다(되돌린 것이 없다 — CLAIM_LOST).
     * 실패하면(저장소 장애·재시도 소진) lease 만료 뒤 다시 잡힌다 — 그때 번호 하나가 시도 행 없이 남는다(n-2와 같은 틈, FAILED).
     */
    private fun release(c: ClaimedDelivery): ReleaseOutcome = try {
        val done = transactions.execute(::isRetryable) { deliveries.releaseClaim(c.delivery.id, owner, c.attemptNo) }
        log.info("operation=MerchantWebhookDelivery deliveryId={} attemptNo={} result=RELEASED_LEASE_SHORT released={}", c.delivery.id.value, c.attemptNo, done)
        if (done) ReleaseOutcome.RELEASED else ReleaseOutcome.CLAIM_LOST
    } catch (e: RuntimeException) {
        log.error("operation=MerchantWebhookDelivery deliveryId={} attemptNo={} result=RELEASE_FAILED failure={}", c.delivery.id.value, c.attemptNo, e::class.simpleName)
        ReleaseOutcome.FAILED
    }

    /** TX 밖 한 시도. 예외를 던지지 않는다(예상 밖 예외는 DEFECT 결과). */
    private fun attempt(c: ClaimedDelivery, at: Instant): Attempted {
        val d = c.delivery
        return try {
            val event = events.find(d.webhookEventId) ?: return Attempted(DeliveryAttemptResult.Failure("EVENT_MISSING", null), null, null)
            if (sha256(event.body) != event.bodySha256) return Attempted(DeliveryAttemptResult.Failure("BODY_INTEGRITY", null), null, null)
            val endpoint = endpoints.find(d.environment, d.merchantAccountId) ?: return Attempted(DeliveryAttemptResult.Failure("NO_ENDPOINT", null), null, null)
            val keys = signingKeys.active(d.environment, d.merchantAccountId)
            if (keys.isEmpty()) return Attempted(DeliveryAttemptResult.Failure("NO_SIGNING_KEY", null), endpoint.endpointRef, null)
            val keyIds = keys.joinToString(",") { it.keyId }
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
                "PayCore-Event-Id" to d.webhookEventId.value,
                "PayCore-Event-Type" to event.eventType,
                "PayCore-Delivery-Attempt" to c.attemptNo.toString(),
                "PayCore-Signature" to signer.signatureHeader(at.epochSecond, event.body, keys),
            )
            val result = when (val r = sender.send(MerchantWebhookSendRequest(d.environment, endpoint.url, headers, event.body))) {
                is MerchantWebhookSendResult.Succeeded -> DeliveryAttemptResult.Success(r.httpStatus)
                is MerchantWebhookSendResult.Failed -> DeliveryAttemptResult.Failure(r.code, r.httpStatus)
            }
            Attempted(result, endpoint.endpointRef, keyIds, sent = true)
        } catch (e: RuntimeException) {
            log.error("operation=MerchantWebhookDelivery deliveryId={} attemptNo={} result=DEFECT failure={}", d.id.value, c.attemptNo, e::class.simpleName)
            Attempted(DeliveryAttemptResult.Failure("DEFECT", null), null, null)
        }
    }

    private fun outcomeCode(r: DeliveryAttemptResult): String = when (r) {
        is DeliveryAttemptResult.Success -> "SUCCESS"
        is DeliveryAttemptResult.Failure -> r.code
    }

    private fun httpStatus(r: DeliveryAttemptResult): Int? = when (r) {
        is DeliveryAttemptResult.Success -> r.httpStatus
        is DeliveryAttemptResult.Failure -> r.httpStatus
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.LockTimeout

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val USER_AGENT = "PayCore-Webhook/1"
        val BLOCKED_CODES = setOf("DESTINATION_BLOCKED", "DESTINATION_REJECTED")
    }
}
