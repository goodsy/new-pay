package com.paycore.payment.payment.application.provided

/**
 * 인증 결과 반영 Use Case(AUTHN-001 §7.3 — 세션 선택(state) → 1단계(조회 전, 잠금 TX) → 인증기관 조회(**TX 밖**) → TX-C(payment → authentication_session →
 * checkout_session)에서 1단계 재판정 + 2단계). 판정 규칙은 domain `AuthenticationResultCheck`가 가진다.
 */
interface CompleteCardAuthenticationUseCase {
    fun complete(command: CompleteCardAuthenticationCommand): CompleteCardAuthenticationResult
}
