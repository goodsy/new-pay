package com.paycore.payment.payment.application.provided

/**
 * 인증 시작 Use Case(AUTHN-001 §4.1 — TX-A(payment → authentication_session → checkout_session) → 인증기관 등록(**TX 밖**) → TX-B). TX를 열지 않는 조정자이고 각 TX는
 * 로컬 TX다. 새 시작은 같은 Payment의 미폐기·미귀속 세션을 대체한다(AN-07).
 */
interface StartCardAuthenticationUseCase {
    fun start(command: StartCardAuthenticationCommand): StartCardAuthenticationResult
}
