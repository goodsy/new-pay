package com.paycore.payment.payment.application.provided

/**
 * 결제창 만료 주기 작업 한 회차 입력(AUTHN-001 §7.5.4). [runId]는 회차 ID(로그 correlationId, PaymentExpired causationId `checkout-expiry-job:<runId>`),
 * [batchSize]는 후보 한 쪽(page) 크기(키셋 — 한 회차는 후보가 없을 때까지 쪽을 넘긴다).
 */
data class ExpireElapsedRequestedPaymentsCommand(val runId: String, val batchSize: Int) {
    init {
        require(runId.length in 1..MAX_RUN_ID && runId.all { it in '!'..'~' }) { "runId must be 1..$MAX_RUN_ID printable ASCII characters" }
        require(batchSize in 1..MAX_BATCH) { "batchSize must be within 1..$MAX_BATCH" }
    }

    private companion object {
        /** causationId `checkout-expiry-job:` + runId가 envelope 64자 안에 들어가게. */
        const val MAX_RUN_ID = 44
        const val MAX_BATCH = 1000
    }
}
