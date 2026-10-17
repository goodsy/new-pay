package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 기관 결과 조회 응답(domain 값 — 기관 DTO 아님, adapter가 엄격 파싱해 만든다). state echo는 digest로 받는다. outcome별 필수 필드(m-E):
 * AUTHENTICATED ⇔ [authenticated] 있음 — 위반 응답은 adapter가 Unverifiable(MALFORMED)로 바꾸므로 이 타입으로 만들 수 없다.
 */
data class ProviderAuthenticationResult(
    val providerTransactionRef: String,
    val outcome: ProviderOutcome,
    val stateDigest: SecretDigest,
    val binding: ProviderBindingEcho,
    val authenticated: AuthenticatedDetails?,
    val validUntil: Instant,
) {
    init {
        require(providerTransactionRef.isNotEmpty()) { "provider transaction reference must not be empty" }
        require(stateDigest.kind == SecretKind.AUTHENTICATION_STATE) { "state digest expected" }
        require((outcome == ProviderOutcome.AUTHENTICATED) == (authenticated != null)) { "evidence details exist exactly for AUTHENTICATED results" }
    }
}
