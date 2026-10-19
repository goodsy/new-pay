package com.paycore.simulator.authentication.application.provided

import java.time.Instant

/**
 * 가상 인증기관이 확정한 인증 증거 조회(AUTH-001 §5.3-3·AU-13 — 가상 VAN 모듈의 기관 재검증용 provided 연산, 같은 JVM·같은 Simulator DB). 읽기 전용이다.
 * 증거 참조가 없거나 둘 이상의 거래에 걸리면 null(판정 불가 — VAN은 무효 증거로 거절한다).
 */
interface AuthenticationEvidenceLookup {
    fun find(evidenceRef: String): AuthenticationEvidenceView?
}

/**
 * 인증 증거 사본. [authenticated]는 기관이 AUTHENTICATED로 확정했는지(시각 무관 — 유효기간은 [validUntil]로 호출자가 본다). 바인딩은 등록 때 PG가 보낸 그대로다. 카드는
 * 가상 카드 참조와 그 속성뿐이다(카드번호 없음).
 */
data class AuthenticationEvidenceView(
    val evidenceRef: String,
    val clientId: String,
    val authenticated: Boolean,
    val environment: String,
    val merchantRef: String,
    val amountMinor: Long,
    val currency: String,
    val planKind: String,
    val planMonths: Int?,
    val cardRef: String?,
    val cardType: String?,
    val issuerCode: String?,
    val acquirerCode: String?,
    val providerCode: String?,
    val validUntil: Instant,
) {
    override fun toString(): String = "AuthenticationEvidenceView(clientId=$clientId, authenticated=$authenticated, validUntil=$validUntil)"
}
