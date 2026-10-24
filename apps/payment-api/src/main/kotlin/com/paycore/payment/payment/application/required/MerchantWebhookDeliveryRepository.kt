package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryState
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import java.time.Instant

/** claim TX가 잡은 전달 하나와 이번에 예약한 시도 번호(m-2)·claim lease 끝(검토 #2 H-1 — 보내기 직전 남은 lease 확인). */
data class ClaimedDelivery(val delivery: MerchantWebhookDeliveryState, val attemptNo: Int, val claimUntil: Instant)

/** 한 시도의 결과(TX 밖 전송 뒤). [Failure.code]는 고정 분류 코드, [Failure.httpStatus]는 응답이 있었을 때만. */
sealed interface DeliveryAttemptResult {
    data class Success(val httpStatus: Int) : DeliveryAttemptResult

    data class Failure(val code: String, val httpStatus: Int?) : DeliveryAttemptResult
}

/** 결과 반영 결과. [CLAIM_LOST]: 소유를 잃은(또는 이미 끝난) 전달의 실패 — 상태는 바꾸지 않고 시도 행만 남긴다. */
enum class DeliveryRecordOutcome { DELIVERED, ALREADY_DELIVERED, RETRY_SCHEDULED, GAVE_UP, CLAIM_LOST }

/**
 * 전달 Aggregate 저장 Port(CONVENTIONS §14, MANDATORY TX — WEBHOOK-001 §5.0 delivery).
 * - [claimDue]: PENDING ∧ 시각 도래 ∧ (claim 없음 ∨ lease 만료)인 행을 `FOR UPDATE SKIP LOCKED`로 잡아 시도 번호를 예약하고 claim 소유자·lease를 적는다.
 * - [recordResult]: 행을 다시 잠가 결과를 반영한다. 성공은 소유와 무관하게 DELIVERED(성공 단조 — n-1), 실패는 그 소유자의 PENDING 행이고 시도 번호가 최신 예약일 때만 반영
 *   (아니면 CLAIM_LOST — 검토 #2 n-1). 끝났거나 소유자가 같으면 claim을 지운다.
 */
interface MerchantWebhookDeliveryRepository {
    fun insertNew(delivery: MerchantWebhookDelivery)

    fun claimDue(now: Instant, limit: Int, owner: String, claimUntil: Instant): List<ClaimedDelivery>

    /**
     * 보내지 않은 예약을 claim과 함께 되돌린다(검토 #2 H-1). 그 소유자의 PENDING 행이고 [attemptNo]가 최신 예약일 때만(아니면 아무것도 바꾸지 않고 false — 그 사이 lease 인수).
     */
    fun releaseClaim(id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int): Boolean

    @Suppress("LongParameterList")
    fun recordResult(
        id: MerchantWebhookDeliveryId, owner: String, attemptNo: Int, result: DeliveryAttemptResult, at: Instant, schedule: WebhookRetrySchedule, jitterFraction: Double,
    ): DeliveryRecordOutcome
}
