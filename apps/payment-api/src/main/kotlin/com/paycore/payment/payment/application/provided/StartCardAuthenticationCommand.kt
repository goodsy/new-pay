package com.paycore.payment.payment.application.provided

/**
 * 인증 시작 입력(AUTHN-001 §6.1 `POST /checkout/s/{handle}/authentication`). [cardCompany]·[plan]은 폼 원문(없으면 null) — 허용 판정은 Use Case가 서버 값으로 한다
 * (카드사 allowlist·Plan 사전 판정, 클라이언트 선택만으로 허용하지 않는다 — A05). [plan]은 `LUMP_SUM` 또는 `INSTALLMENT_<개월>`.
 */
data class StartCardAuthenticationCommand(
    val handle: String,
    val cookieSecret: String?,
    val cardCompany: String?,
    val plan: String?,
    val correlationId: String,
) {
    override fun toString(): String = "StartCardAuthenticationCommand(correlationId=$correlationId)"
}
