package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import java.time.Instant

/**
 * 전송 시도 한 건(append-only 증거 — WEBHOOK-001 §5.0·§13 Auditability). URL·본문·서명 값·응답 본문은 담지 않는다: endpointRef·키 ID·고정 결과 코드·HTTP 상태·일정 버전·시각만.
 * [attemptNo]는 claim TX가 예약한 번호(m-2 — lease 인수 때 두 worker의 시도 행이 서로 다른 번호로 남는다). [outcome]은 성공이면 `SUCCESS`, 아니면 실패 코드.
 */
data class MerchantWebhookAttempt(
    val deliveryId: MerchantWebhookDeliveryId,
    val attemptNo: Int,
    val worker: String,
    val endpointRef: String?,
    val signingKeyIds: String?,
    val outcome: String,
    val httpStatus: Int?,
    val scheduleVersion: String,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    init {
        require(attemptNo >= 1) { "attempt numbers start at 1" }
        require(OUTCOME.matches(outcome)) { "attempt outcome must be a fixed code" }
        require(httpStatus == null || httpStatus in 100..599) { "http status out of range" }
        require(!finishedAt.isBefore(startedAt)) { "an attempt finishes after it starts" }
    }

    private companion object {
        val OUTCOME = Regex("^[A-Z0-9_]{1,48}$")
    }
}

/** 시도 기록 저장 Port(insert-only, MANDATORY TX — 결과 반영과 같은 TX). */
interface MerchantWebhookAttemptStore {
    fun append(attempt: MerchantWebhookAttempt)
}
