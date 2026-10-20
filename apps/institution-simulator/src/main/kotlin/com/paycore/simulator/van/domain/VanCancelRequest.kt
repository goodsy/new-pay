package com.paycore.simulator.van.domain

import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat

/** 취소 범위 echo(CANCEL-001 §6.2 — PG가 판단한 전액/부분. VAN은 자기 판정과 다르면 ORIGINAL_MISMATCH로 거절한다). */
enum class VanCancelScope { FULL, PARTIAL }

/**
 * 가상 VAN 취소 요청의 업무 필드(CANCEL-001 §6.2 CANCEL_REQUEST — 전송 필드 messageId·requestedAt은 여기 없다). 같은 요청 ID 재전송 판정은 [payloadHash]로 한다:
 * 업무 필드의 canonical 문자열 SHA-256(golden `payloadHashes`와 같은 정의, 접두 `sim-van-2/CANCEL_REQUEST`). 필드 문법은 프레임 파서가 강제한다. 카드번호·인증 비밀 없음.
 */
data class VanCancelRequest(
    val requestId: String,
    val institutionAccount: String,
    val merchantRef: String,
    val environment: String,
    val routeRef: String,
    val originalRequestId: String,
    val originalProviderTransactionId: String,
    val originalApprovalNumber: String,
    val originalTransactionDate: LocalDate,
    val cancelAmountMinor: Long,
    val currency: String,
    val scope: VanCancelScope,
) {
    init {
        require(cancelAmountMinor >= 1) { "cancel amount must be positive" }
        val fields = listOf(requestId, institutionAccount, merchantRef, environment, routeRef, originalRequestId, originalProviderTransactionId, originalApprovalNumber, currency)
        require(fields.all { it.isNotEmpty() && '\n' !in it && '\r' !in it }) { "cancel request fields must be non-empty single-line values" }
    }

    fun payloadHash(): String {
        val canonical = listOf(
            requestId, institutionAccount, merchantRef, environment, routeRef, originalRequestId, originalProviderTransactionId, originalApprovalNumber,
            originalTransactionDate.toString(), cancelAmountMinor.toString(), currency, scope.name,
        ).joinToString("\n", prefix = "$CONTRACT/CANCEL_REQUEST\n")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "VanCancelRequest(requestId=$requestId, originalRequestId=$originalRequestId, cancelAmountMinor=$cancelAmountMinor, scope=$scope)"

    companion object {
        const val CONTRACT = "sim-van-2"
    }
}
