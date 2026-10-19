package com.paycore.simulator.van.domain

import java.time.Instant

/**
 * 가상 인증기관이 기록한 인증 증거(가상 VAN이 재검증에 쓰는 사본 — 인증 모듈 provided 조회 결과를 application이 옮긴다, AUTH-001 §5.3-3·AU-13). [authenticated]는 기관이
 * AUTHENTICATED로 확정했는지(시각과 무관), [validUntil]은 증거 유효기간. 카드 식별은 가상 카드 참조([cardRef])뿐이다(카드번호 없음).
 */
data class VanEvidence(
    val evidenceRef: String,
    val clientId: String,
    val authenticated: Boolean,
    val environment: String,
    val merchantRef: String,
    val amountMinor: Long,
    val currency: String,
    val plan: VanPlan,
    val cardRef: String?,
    val cardType: String?,
    val issuerCode: String?,
    val acquirerCode: String?,
    val providerCode: String?,
    val validUntil: Instant,
) {
    override fun toString(): String = "VanEvidence(clientId=$clientId, authenticated=$authenticated, validUntil=$validUntil)"
}
