package com.paycore.simulator.authentication.domain

/** echo할 PaymentPlan(`LUMP_SUM` 또는 `INSTALLMENT` + 개월 2..99). */
data class PlanEcho(val kind: String, val months: Int?) {
    init {
        val valid = (kind == "LUMP_SUM" && months == null) || (kind == "INSTALLMENT" && months != null && months in 2..99)
        if (!valid) throw SimulatorRejection(RejectionReason.INVALID_REQUEST)
    }
}

/**
 * 인증 등록 요청(계약 v1 §13.4). 바인딩은 PG가 보낸 그대로 보존하고 결과 조회에서 echo한다(PG가 대조 — D-AUTHN-003). state는 기관이 echo해야 하므로 원문을
 * 보관한다(TEST 전용 대역 값). 형식 위반은 [RejectionReason.INVALID_REQUEST]. 환경 판정(TEST만)은 [AuthenticationTransaction.register].
 */
data class RegistrationRequest(
    val paymentId: String,
    val merchantRef: String,
    val environment: String,
    val amountMinor: Long,
    val currency: String,
    val plan: PlanEcho,
    val cardCompany: String,
    val state: String,
    val returnUrl: String,
) {
    init {
        val ok = IDENTIFIER.matches(paymentId) && IDENTIFIER.matches(merchantRef) && environment in ENVIRONMENTS && amountMinor >= 1 &&
            CURRENCY.matches(currency) && CARD_COMPANY.matches(cardCompany) && STATE.matches(state) && returnUrl.length <= 2048
        if (!ok) throw SimulatorRejection(RejectionReason.INVALID_REQUEST)
    }

    override fun toString(): String = "RegistrationRequest(paymentId=$paymentId, environment=$environment)"

    private companion object {
        val IDENTIFIER = Regex("^[!-~]{1,64}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val CARD_COMPANY = Regex("^[A-Z0-9-]{1,32}$")
        val STATE = Regex("^[A-Za-z0-9_-]{43}$")
        val ENVIRONMENTS = setOf("TEST", "LIVE")
    }
}
