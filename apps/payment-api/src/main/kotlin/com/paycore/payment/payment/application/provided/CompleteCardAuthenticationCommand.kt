package com.paycore.payment.payment.application.provided

/**
 * 인증 결과 반영 입력(AUTHN-001 §6.1 `GET /checkout/s/{handle}/authentication/return`). [parameters]는 query 전체(이름 → 값 목록) — 고정 문법(`resultRef`·`state` 각
 * 하나, 다른 이름·중복·형식 위반 거절)은 Use Case가 판정한다(조회 전, m-3).
 */
data class CompleteCardAuthenticationCommand(
    val handle: String,
    val cookieSecret: String?,
    val parameters: Map<String, List<String>>,
    val correlationId: String,
) {
    override fun toString(): String = "CompleteCardAuthenticationCommand(correlationId=$correlationId)"
}
