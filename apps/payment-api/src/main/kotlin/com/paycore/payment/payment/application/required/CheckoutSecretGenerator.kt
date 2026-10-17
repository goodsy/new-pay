package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId

/**
 * 결제창 비밀·식별자 생성(AUTHN-001 §11.2, AN-22·AN-27 — 기존 `PaymentIdentifierGenerator` 관례). 비밀 원문은 응답·쿠키·기관 요청에만 쓰고 DB에는 digest만 둔다
 * (D-AUTHN-001). 식별자(ULID)는 비밀이 아니다.
 */
interface CheckoutSecretGenerator {
    /** 결제창 1회용 토큰 원문: `ckt_` + base64url(256bit) 43자. */
    fun newTokenSecret(): String

    /** 토큰 행 식별자 `ctk_` + ULID. */
    fun newTokenId(): CheckoutTokenId

    /** 결제창 세션 행 식별자 `cks_` + ULID. */
    fun newCheckoutSessionId(): CheckoutSessionId

    /** 결제창 경로 handle: base64url(128bit) 22자(판정 m-1). */
    fun newHandle(): CheckoutHandle

    /** 결제창 쿠키 비밀: base64url(256bit) 43자. */
    fun newSessionSecret(): String

    /** 인증 세션 식별자 `aus_` + ULID. */
    fun newAuthenticationSessionId(): AuthenticationSessionId

    /** 인증 state 원문: base64url(256bit) 43자(기관이 echo — §7.3 세션 선택). */
    fun newState(): String

    /** 인증 참조 `aref_` + base64url(128bit) 22자(§7.3). */
    fun newAuthenticationRef(): AuthenticationRef
}
