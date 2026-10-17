package com.paycore.payment.payment.application.required

/**
 * 인증기관 고객 origin(AUTHN-001 §12.1·§12.4, m-9 — 설정에서만). 결제창 CSP `form-action`과 인증 시작 redirect 검증에 쓴다. 구현은 인증 backend 설정
 * (`paycore.authentication.simulator.customer-base-url`, 기동 시 https origin 검사).
 */
interface CardAuthenticationEndpoints {
    val customerOrigin: String
}
