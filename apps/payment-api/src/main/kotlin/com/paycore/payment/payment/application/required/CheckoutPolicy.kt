package com.paycore.payment.payment.application.required

import java.time.Duration

/**
 * 결제창 정책 설정(AUTHN-001 §5·§6·§12.1 — 운영 기본값 없음, 시험·로컬은 Demo). 버전([policyVersion])은 토큰·결제창 세션·인증 세션 행에 저장한다(Auditability).
 * - [publicBaseUrl]: checkoutUrl·PG returnUrl의 origin(`https://host[:port]`, path 없음). 요청의 `Host`·`X-Forwarded-*`로 만들지 않는다(m-9).
 * - [tokenTtl]·[sessionTtl]·[authenticationTtl]: 토큰·결제창 세션·인증 세션 수명. 실제 만료 시각은 Payment 결제창 시각(·결제창 세션 시각)으로 자른다(§7.5.1).
 */
interface CheckoutPolicy {
    val policyVersion: String
    val publicBaseUrl: String
    val tokenTtl: Duration
    val sessionTtl: Duration
    val authenticationTtl: Duration
}
