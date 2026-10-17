package com.paycore.payment.payment.application.provided

/**
 * 결제창 토큰 발급 입력(AUTHN-001 §5.1). [paymentId]는 경로 원문 그대로다 — 형식 오류도 존재를 드러내지 않게 Use Case가 PAYMENT_NOT_FOUND로 답한다(AN-01). 반환 URL은
 * 원문 문자열이다 — 문법 판정(§8.1)은 Use Case가 domain 규칙으로 한다(web은 provided만 쓴다, D-AUTHN-006). MID·환경은 [authentication]에서만 온다.
 * [correlationId]는 requestId — PaymentExpired의 causationId(VARCHAR 64)로도 쓰이므로 64자 이하.
 */
data class IssueCheckoutTokenCommand(
    val authentication: MerchantAuthentication,
    val paymentId: String,
    val successUrl: String,
    val failUrl: String,
    val correlationId: String,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
    }

    override fun toString(): String = "IssueCheckoutTokenCommand(merchantAccountId=${authentication.merchantAccountId.value}, correlationId=$correlationId)"

    private companion object {
        const val MAX_CORRELATION_ID = 64
    }
}
