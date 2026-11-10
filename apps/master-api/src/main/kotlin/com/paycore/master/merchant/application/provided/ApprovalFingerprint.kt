package com.paycore.master.merchant.application.provided

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * `mop1` 승인 지문(MERCHANT-OPS-002 §9.3 C-23 — rev 0.6 확정 정규 문자열): UTF-8 `"\n"` 연결 줄(끝 줄바꿈 없음)의 SHA-256 소문자 hex에 접두 `mop1:`.
 * 줄 순서: `mop1`, operation code, approvalId, requestedBy, approvedBy, approvedAt epoch ms, executeBefore epoch ms, evidenceRef, 그다음 명령 필드(§9.4 표 순서).
 * correlationId는 넣지 않는다(내용 아님). 모든 줄은 줄바꿈이 없는 형식 검사된 값이라 연결이 모호하지 않다.
 *
 * ADMIN 실행 행 지문 = MASTER 근거 행 지문(대조용). 두 앱은 코드를 공유하지 않고 같은 **독립 벡터**로 시험한다(MerchantApprovalCommandParserSpec — acd1 선례).
 * MASTER web adapter는 본문의 `approvalFingerprint`가 이 재계산과 다르면 400이다(Use Case 호출 전).
 */
object ApprovalFingerprint {
    const val PREFIX = "mop1:"

    @Suppress("LongParameterList")
    fun mop1(
        operation: AdminOperation,
        approvalId: String,
        requestedBy: String,
        approvedBy: String,
        approvedAt: Instant,
        executeBefore: Instant,
        evidenceRef: String,
        commandFields: List<String>,
    ): String {
        val lines = listOf(
            "mop1", operation.code, approvalId, requestedBy, approvedBy, approvedAt.toEpochMilli().toString(), executeBefore.toEpochMilli().toString(), evidenceRef,
        ) + commandFields
        require(lines.none { '\n' in it }) { "fingerprint lines must not contain a line feed" }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return PREFIX + HexFormat.of().formatHex(digest)
    }
}
