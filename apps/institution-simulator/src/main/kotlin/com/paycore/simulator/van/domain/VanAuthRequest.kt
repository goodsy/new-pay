package com.paycore.simulator.van.domain

import java.security.MessageDigest
import java.util.HexFormat

/** 승인 요청의 인증 근거 참조(카드번호·CVC·인증 비밀 없음 — 증거 참조와 기관 코드만, AUTH-001 §5.2). */
data class VanAuthentication(val evidenceRef: String, val providerCode: String, val issuerCode: String, val acquirerCode: String)

/**
 * 가상 VAN 승인 요청의 업무 필드(AUTH-001 §5.2 AUTH_REQUEST — 전송 필드 messageId·requestedAt은 여기 없다). 같은 요청 ID 재전송 판정은 [payloadHash]로 한다(rev 0.3 m-7):
 * 업무 필드의 canonical 문자열 SHA-256(golden `payloadHashes`와 같은 정의). 필드 문법은 프레임 파서가 강제하며 값에 줄바꿈이 없다.
 */
data class VanAuthRequest(
    val requestId: String,
    val institutionAccount: String,
    val merchantRef: String,
    val environment: String,
    val routeRef: String,
    val amountMinor: Long,
    val currency: String,
    val plan: VanPlan,
    val authentication: VanAuthentication,
) {
    init {
        require(amountMinor >= 1) { "amount must be positive" }
        val fields = listOf(requestId, institutionAccount, merchantRef, environment, routeRef, currency) +
            listOf(authentication.evidenceRef, authentication.providerCode, authentication.issuerCode, authentication.acquirerCode)
        require(fields.all { it.isNotEmpty() && '\n' !in it && '\r' !in it }) { "request fields must be non-empty single-line values" }
    }

    fun payloadHash(): String {
        val a = authentication
        val canonical = (
            listOf(
                requestId, institutionAccount, merchantRef, environment, routeRef, amountMinor.toString(), currency, plan.kind.name, plan.months?.toString() ?: "",
                a.evidenceRef, a.providerCode, a.issuerCode, a.acquirerCode,
            )
            ).joinToString("\n", prefix = "$CONTRACT/AUTH_REQUEST\n")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "VanAuthRequest(requestId=$requestId, amountMinor=$amountMinor, currency=$currency, plan=$plan)"

    companion object {
        const val CONTRACT = "sim-van-1"
    }
}
