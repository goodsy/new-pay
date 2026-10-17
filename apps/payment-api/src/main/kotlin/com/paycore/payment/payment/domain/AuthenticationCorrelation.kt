package com.paycore.payment.payment.domain

/**
 * 인증 세션 생성 때 고정하는 상관 값(D-AUTHN-003, AUTHN-001 §7.1): 세션별 256bit state 원문의 digest. 복귀 URL의 state와 기관 결과의 echo state를
 * 이 값과 대조한다. 원문은 저장하지 않는다.
 */
data class AuthenticationCorrelation(val stateDigest: SecretDigest) {
    init {
        require(stateDigest.kind == SecretKind.AUTHENTICATION_STATE) { "an authentication state digest is required" }
    }
}
