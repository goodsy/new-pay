package com.paycore.payment.payment.domain

import java.time.Instant

/** 전달 상태(WEBHOOK-001 §5.5·§5.8). GAVE_UP은 완료가 아니다(경보 대상 — D-WH-006). */
enum class MerchantWebhookDeliveryStatus { PENDING, DELIVERED, GAVE_UP }

/** 영속 행 모양(reconstitute 입력). claim 소유자·lease는 persistence bookkeeping이라 여기 없다. */
data class MerchantWebhookDeliveryState(
    val id: MerchantWebhookDeliveryId,
    val webhookEventId: MerchantWebhookEventId,
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val status: MerchantWebhookDeliveryStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val firstAttemptAt: Instant?,
    val lastFailureCode: String?,
    val deliveredAt: Instant?,
    val gaveUpAt: Instant?,
)

/**
 * 가맹점 이벤트 하나의 전달(Aggregate, WEBHOOK-001 §5.0 rev 0.2·§5.6, D-WH-006).
 *
 * - [startAttempt]: claim TX가 시도 번호를 **예약**한다(m-2 — `attempt_count` +1, 결과 TX는 그 번호로 시도 행을 남긴다). 예약 뒤 전송 전 crash면 그 번호의 시도 행이 없는 틈이 생긴다(n-2 — 허용).
 * - [recordFailure]: 예약한 시도가 실패 → [WebhookRetrySchedule]로 다음 시각, 상한이면 GAVE_UP.
 * - [recordSuccess]: 2xx → DELIVERED. **성공은 단조**(n-1): 소유를 잃은 worker의 2xx나 GAVE_UP 뒤 늦게 확인된 2xx도 DELIVERED로 만든다(가맹점이 받았다는 사실이 우선, 이력은 시도 행에 남는다).
 *
 * 수동 재전송은 WEBHOOK-ENDPOINT-001의 명령이다(상태 모델만 — D-WH-008). 금융 상태와 무관하다(D-WH-006). 실패 코드는 고정 분류 코드만(URL·본문·예외 문구 금지).
 */
class MerchantWebhookDelivery private constructor(state: MerchantWebhookDeliveryState) {
    val id: MerchantWebhookDeliveryId = state.id
    val webhookEventId: MerchantWebhookEventId = state.webhookEventId
    val environment: Environment = state.environment
    val merchantAccountId: MerchantAccountId = state.merchantAccountId

    var status: MerchantWebhookDeliveryStatus = state.status
        private set
    var attemptCount: Int = state.attemptCount
        private set
    var nextAttemptAt: Instant? = state.nextAttemptAt
        private set
    var firstAttemptAt: Instant? = state.firstAttemptAt
        private set
    var lastFailureCode: String? = state.lastFailureCode
        private set
    var deliveredAt: Instant? = state.deliveredAt
        private set
    var gaveUpAt: Instant? = state.gaveUpAt
        private set

    fun state(): MerchantWebhookDeliveryState = MerchantWebhookDeliveryState(
        id, webhookEventId, environment, merchantAccountId, status, attemptCount, nextAttemptAt, firstAttemptAt, lastFailureCode, deliveredAt, gaveUpAt,
    )

    /** 다음 시도 번호를 예약한다(1부터). PENDING만. */
    fun startAttempt(at: Instant): Int {
        check(status == MerchantWebhookDeliveryStatus.PENDING) { "only a pending delivery starts an attempt" }
        attemptCount++
        if (firstAttemptAt == null) firstAttemptAt = at
        return attemptCount
    }

    /**
     * 예약한 시도를 보내지 않고 되돌린다(검토 #2 H-1 — claim lease가 한 시도 예산을 덮지 못할 때). 가장 최근 예약([attemptNo] = 현재 시도 수)만, PENDING에서만.
     * 시도 수 = 실제 보낸 시도 수가 유지된다(되돌린 번호는 다음 claim이 다시 쓴다 — 시도 행이 없으므로 UNIQUE 충돌 없음).
     */
    fun releaseReservedAttempt(attemptNo: Int) {
        check(status == MerchantWebhookDeliveryStatus.PENDING) { "only a pending delivery releases a reservation" }
        check(attemptNo == attemptCount && attemptNo >= 1) { "only the latest reservation can be released" }
        attemptCount--
        if (attemptCount == 0) firstAttemptAt = null
    }

    /** 2xx. 이미 DELIVERED면 그대로(멱등), GAVE_UP이어도 DELIVERED로 바꾼다(성공 단조 — n-1). 시도 예약 없이 성공할 수 없다. */
    fun recordSuccess(at: Instant) {
        check(attemptCount >= 1) { "a success needs a reserved attempt" }
        if (status == MerchantWebhookDeliveryStatus.DELIVERED) return
        status = MerchantWebhookDeliveryStatus.DELIVERED
        deliveredAt = at
        nextAttemptAt = null
        gaveUpAt = null
    }

    /** 예약한 시도의 실패. PENDING만(끝난 전달은 실패로 되돌리지 않는다). */
    fun recordFailure(at: Instant, failureCode: String, schedule: WebhookRetrySchedule, jitterFraction: Double) {
        check(status == MerchantWebhookDeliveryStatus.PENDING) { "a finished delivery does not record failures" }
        check(attemptCount >= 1) { "a failure needs a reserved attempt" }
        requireFailureCode(failureCode)
        lastFailureCode = failureCode
        if (schedule.exhausted(attemptCount, checkNotNull(firstAttemptAt), at)) {
            status = MerchantWebhookDeliveryStatus.GAVE_UP
            gaveUpAt = at
            nextAttemptAt = null
        } else {
            nextAttemptAt = at.plus(schedule.delayAfter(attemptCount, jitterFraction))
        }
    }

    companion object {
        private val FAILURE_CODE = Regex("^[A-Z0-9_]{1,48}$")

        fun requireFailureCode(code: String) {
            require(FAILURE_CODE.matches(code)) { "failure code must be a fixed code of 1..48 [A-Z0-9_]" }
        }

        fun pending(
            id: MerchantWebhookDeliveryId, webhookEventId: MerchantWebhookEventId, environment: Environment, merchantAccountId: MerchantAccountId, at: Instant,
        ): MerchantWebhookDelivery = MerchantWebhookDelivery(
            MerchantWebhookDeliveryState(id, webhookEventId, environment, merchantAccountId, MerchantWebhookDeliveryStatus.PENDING, 0, at, null, null, null, null),
        )

        /** 영속 행 복원 — 존재할 수 없는 조합은 거부한다. */
        fun reconstitute(s: MerchantWebhookDeliveryState): MerchantWebhookDelivery {
            require(s.attemptCount >= 0) { "attempt count must not be negative" }
            require((s.attemptCount == 0) == (s.firstAttemptAt == null)) { "the first attempt time exists exactly when an attempt was reserved" }
            s.lastFailureCode?.let(::requireFailureCode)
            when (s.status) {
                MerchantWebhookDeliveryStatus.PENDING ->
                    require(s.nextAttemptAt != null && s.deliveredAt == null && s.gaveUpAt == null) { "a pending delivery has a next attempt and no end" }
                MerchantWebhookDeliveryStatus.DELIVERED ->
                    require(s.attemptCount >= 1 && s.deliveredAt != null && s.nextAttemptAt == null && s.gaveUpAt == null) { "a delivered delivery has an attempt and a delivery time" }
                MerchantWebhookDeliveryStatus.GAVE_UP ->
                    require(s.attemptCount >= 1 && s.gaveUpAt != null && s.lastFailureCode != null && s.nextAttemptAt == null && s.deliveredAt == null) {
                        "a given-up delivery has failed attempts and a give-up time"
                    }
            }
            return MerchantWebhookDelivery(s)
        }
    }
}
