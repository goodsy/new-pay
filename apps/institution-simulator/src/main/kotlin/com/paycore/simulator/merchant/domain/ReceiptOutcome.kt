package com.paycore.simulator.merchant.domain

/**
 * 수신 원장 행의 분류. ACCEPTED = 그 이벤트의 첫 2xx(가맹점 처리 1회 — eventId 중복 제거), DUPLICATE = 이미 받은 이벤트의 2xx, SCENARIO_REFUSED = 검증은 통과했으나 시나리오가
 * 2xx가 아닌 응답(503·302). REJECTED_* = 거절([responseStatus] 4xx — 서명 문법·창·서명·본문·환경 TEST 아님(검토 n-6)).
 */
enum class ReceiptOutcome(val verified: Boolean, val responseStatus: Int?) {
    ACCEPTED(true, null),
    DUPLICATE(true, null),
    SCENARIO_REFUSED(true, null),
    REJECTED_MALFORMED(false, 400),
    REJECTED_STALE(false, 401),
    REJECTED_SIGNATURE(false, 401),
    REJECTED_BODY(true, 400),
    REJECTED_ENVIRONMENT(true, 400),
    ;

    companion object {
        fun rejectionOf(verdict: WebhookVerdict): ReceiptOutcome = when (verdict) {
            WebhookVerdict.MALFORMED -> REJECTED_MALFORMED
            WebhookVerdict.STALE -> REJECTED_STALE
            WebhookVerdict.SIGNATURE -> REJECTED_SIGNATURE
            WebhookVerdict.ACCEPTED -> error("an accepted signature is not a rejection")
        }
    }
}
