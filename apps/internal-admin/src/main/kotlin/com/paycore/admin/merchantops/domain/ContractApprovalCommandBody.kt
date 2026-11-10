package com.paycore.admin.merchantops.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 계약 Revision 승인 명령 본문과 `mop1` 지문(MERCHANT-OPS-002 B2-2 §9.3·§9.4 — MASTER `MerchantApprovalCommandParser`·`ApprovalFingerprint`와 **코드 공유 없이** 같은 계약, 독립 벡터
 * `ContractApprovalCommandBodySpec` MB-1 = MASTER 시험 벡터). 순수 계산(DB·외부 호출 없음 — 승인 TX1 안에서 만든다).
 * - 문법은 MASTER 파서와 같다(여기서 거르지 못하면 MASTER 400 → 재전송 중단): `apr_ULID`, 정규형 `admin:adm_<ULID 대문자>`, 근거 참조 `[A-Za-z0-9._:#/-]{1,128}`, `ctr_ULID`,
 *   Revision 1..2147483647, 시각 ms 단위·기한 > 승인.
 * - [fingerprint] = `mop1:` + SHA-256 소문자 hex(UTF-8 `"\n"` 연결, 끝 줄바꿈 없음): `mop1`, `ApproveContractRevision`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms,
 *   evidenceRef, contractId, revisionNo. correlationId는 넣지 않는다.
 * - [json]: 정확히 11개 문자열 필드(고정 순서, `actor` 없음 — C-17). 키 `mop1.<approvalId>`, correlationId `mop.<approvalId>`. 값이 모두 제한된 ASCII라 이스케이프가 필요 없다.
 * toString에 근거 참조가 없다.
 */
data class ContractApprovalCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val contractId: String,
    val revisionNo: Int,
) {
    init {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        require(ACTOR.matches(requestedBy) && ACTOR.matches(approvedBy)) { "actor format (admin:adm_<ULID>)" }
        require(EVIDENCE.matches(evidenceRef)) { "evidence format" }
        require(CONTRACT_ID.matches(contractId)) { "contract id format" }
        require(revisionNo >= 1) { "revision number" }
        require(executeBefore.isAfter(approvedAt)) { "deadline after approval" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "millisecond precision" }
    }

    fun fingerprint(): String {
        val lines = listOf(
            TAG, OPERATION, approvalId, requestedBy, approvedBy, approvedAt.toEpochMilli().toString(), executeBefore.toEpochMilli().toString(), evidenceRef, contractId,
            revisionNo.toString(),
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return "$TAG:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun json(): ByteArray = (
        """{"idempotencyKey":"mop1.$approvalId","correlationId":"mop.$approvalId","approvalId":"$approvalId","requestedBy":"$requestedBy","approvedBy":"$approvedBy",""" +
            """"approvedAt":"${TIME.format(approvedAt)}","executeBefore":"${TIME.format(executeBefore)}","evidenceRef":"$evidenceRef","approvalFingerprint":"${fingerprint()}",""" +
            """"contractId":"$contractId","revisionNo":"$revisionNo"}"""
        ).toByteArray(StandardCharsets.UTF_8)

    override fun toString(): String = "ContractApprovalCommandBody(approvalId=$approvalId, contractId=$contractId, revisionNo=$revisionNo)"

    companion object {
        const val OPERATION = "ApproveContractRevision"
        private const val TAG = "mop1"
        private const val NANOS_PER_MILLI = 1_000_000
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        private val APPROVAL_ID = Regex("^apr_$ULID$")
        private val ACTOR = Regex("^admin:adm_$ULID$")
        private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")
        private val CONTRACT_ID = Regex("^ctr_$ULID$")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
