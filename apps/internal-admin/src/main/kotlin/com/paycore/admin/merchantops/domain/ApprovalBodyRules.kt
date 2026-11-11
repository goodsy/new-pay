package com.paycore.admin.merchantops.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * B3 승인 본문 공통 규칙(MASTER 파서와 같은 문법 복제 — 여기서 거르지 못하면 MASTER 400 → 재전송 중단). 값은 모두 제한된 ASCII라 JSON 이스케이프가 필요 없다.
 * 본문 순서: idempotencyKey(`mop1.<approvalId>`), correlationId(`mop.<approvalId>`), approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, approvalFingerprint,
 * operation, 명령 필드.
 */
internal object ApprovalBodyRules {
    const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    private const val TAG = "mop1"
    private const val NANOS_PER_MILLI = 1_000_000
    private val APPROVAL_ID = Regex("^apr_$ULID$")
    private val ACTOR = Regex("^admin:adm_$ULID$")
    private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    @Suppress("LongParameterList")
    fun common(approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String) {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        require(ACTOR.matches(requestedBy) && ACTOR.matches(approvedBy)) { "actor format (admin:adm_<ULID>)" }
        require(EVIDENCE.matches(evidenceRef)) { "evidence format" }
        require(executeBefore.isAfter(approvedAt)) { "deadline after approval" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "millisecond precision" }
    }

    @Suppress("LongParameterList")
    fun fingerprint(
        operation: String, approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, fields: List<String>,
    ): String {
        val lines = listOf(TAG, operation, approvalId, requestedBy, approvedBy, approvedAt.toEpochMilli().toString(), executeBefore.toEpochMilli().toString(), evidenceRef) + fields
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return "$TAG:" + digest.joinToString("") { "%02x".format(it) }
    }

    @Suppress("LongParameterList")
    fun json(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, fingerprint: String, operation: String,
        fields: List<Pair<String, String>>,
    ): ByteArray = (
        """{"idempotencyKey":"mop1.$approvalId","correlationId":"mop.$approvalId","approvalId":"$approvalId","requestedBy":"$requestedBy","approvedBy":"$approvedBy",""" +
            """"approvedAt":"${TIME.format(approvedAt)}","executeBefore":"${TIME.format(executeBefore)}","evidenceRef":"$evidenceRef","approvalFingerprint":"$fingerprint",""" +
            """"operation":"$operation"""" + fields.joinToString("") { (k, v) -> ""","$k":"$v"""" } + "}"
        ).toByteArray(StandardCharsets.UTF_8)
}
