package com.paycore.admin.merchantops.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 첫 Default MID 발급 명령 본문과 `mop1` 지문(MERCHANT-OPS-002 B2-3 §9.3·§9.4 — MASTER `MerchantApprovalCommandParser.firstDefaultIssuance`와 코드 공유 없이 같은 계약, 독립 벡터
 * `FirstMidIssuanceCommandBodySpec` FB-1). 순수 계산(승인 TX1 안에서 만든다).
 * - 지문 줄: `mop1`, `IssueFirstDefaultMerchantAccount`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, merchantId, contractId, agreementId,
 *   revisionNo, primarySiteId(§9.4 표 순서). correlationId는 넣지 않는다.
 * - [json]: 근거 공통 9개 + 명령 필드 5개 = 정확히 14개 문자열 필드(고정 순서, `actor` 없음 — C-17). 값은 모두 제한된 ASCII라 이스케이프가 필요 없다.
 * toString에 근거 참조가 없다.
 */
data class FirstMidIssuanceCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val merchantId: String,
    val contractId: String,
    val agreementId: String,
    val revisionNo: Int,
    val primarySiteId: String,
) {
    init {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        require(ACTOR.matches(requestedBy) && ACTOR.matches(approvedBy)) { "actor format (admin:adm_<ULID>)" }
        require(EVIDENCE.matches(evidenceRef)) { "evidence format" }
        require(Regex("^mer_$ULID$").matches(merchantId)) { "merchant id format" }
        require(Regex("^ctr_$ULID$").matches(contractId)) { "contract id format" }
        require(Regex("^csa_$ULID$").matches(agreementId)) { "agreement id format" }
        require(Regex("^site_$ULID$").matches(primarySiteId)) { "site id format" }
        require(revisionNo >= 1) { "revision number" }
        require(executeBefore.isAfter(approvedAt)) { "deadline after approval" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "millisecond precision" }
    }

    fun fingerprint(): String {
        val lines = listOf(
            TAG, OPERATION, approvalId, requestedBy, approvedBy, approvedAt.toEpochMilli().toString(), executeBefore.toEpochMilli().toString(), evidenceRef,
            merchantId, contractId, agreementId, revisionNo.toString(), primarySiteId,
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return "$TAG:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun json(): ByteArray = (
        """{"idempotencyKey":"mop1.$approvalId","correlationId":"mop.$approvalId","approvalId":"$approvalId","requestedBy":"$requestedBy","approvedBy":"$approvedBy",""" +
            """"approvedAt":"${TIME.format(approvedAt)}","executeBefore":"${TIME.format(executeBefore)}","evidenceRef":"$evidenceRef","approvalFingerprint":"${fingerprint()}",""" +
            """"merchantId":"$merchantId","contractId":"$contractId","agreementId":"$agreementId","revisionNo":"$revisionNo","primarySiteId":"$primarySiteId"}"""
        ).toByteArray(StandardCharsets.UTF_8)

    override fun toString(): String = "FirstMidIssuanceCommandBody(approvalId=$approvalId, merchantId=$merchantId)"

    companion object {
        const val OPERATION = "IssueFirstDefaultMerchantAccount"
        private const val TAG = "mop1"
        private const val NANOS_PER_MILLI = 1_000_000
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        private val APPROVAL_ID = Regex("^apr_$ULID$")
        private val ACTOR = Regex("^admin:adm_$ULID$")
        private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
