package com.paycore.simulator.merchant.domain

import java.time.Instant

/**
 * 가상 가맹점 수신 원장 한 행(append-only — 요청 하나당 한 행, 수정·삭제 없음). 검증을 통과한 행만 봉투 값과 키 ID를 갖는다(거절 행은 믿지 않은 본문 값을 남기지 않는다 —
 * 단 REJECTED_ENVIRONMENT는 서명된 봉투를 남긴다). 본문은 저장하지 않고 SHA-256만 남긴다.
 */
data class MerchantWebhookReceipt(
    val receiverId: String,
    val outcome: ReceiptOutcome,
    val envelope: ReceivedEnvelope?,
    val keyId: String?,
    val bodySha256: String,
    val responseStatus: Int,
    val receivedAt: Instant,
) {
    init {
        require(BODY_SHA256.matches(bodySha256)) { "body digest must be 64 lower-case hex characters" }
        require(responseStatus in 200..599) { "response status must be an HTTP status" }
        require((keyId != null) == outcome.verified) { "only a verified receipt carries the matching key id" }
        val envelopeExpected = outcome.verified && outcome != ReceiptOutcome.REJECTED_BODY
        require((envelope != null) == envelopeExpected) { "only a verified receipt with a readable body carries the envelope" }
        outcome.responseStatus?.let { require(responseStatus == it) { "a rejection answers its fixed status" } }
        if (outcome == ReceiptOutcome.ACCEPTED || outcome == ReceiptOutcome.DUPLICATE) require(responseStatus in 200..299) { "an accepted receipt answered 2xx" }
        if (outcome == ReceiptOutcome.SCENARIO_REFUSED) require(responseStatus !in 200..299) { "a refused receipt did not answer 2xx" }
    }

    private companion object {
        val BODY_SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
