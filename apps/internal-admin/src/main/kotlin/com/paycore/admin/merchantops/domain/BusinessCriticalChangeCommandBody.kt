package com.paycore.admin.merchantops.domain

import java.time.Instant

/**
 * 사업자 중요정보 변경 실행 본문과 `mop1` 지문(BIZ-CRIT-APPROVAL-001 §3.2 — MASTER `MerchantApprovalCommandParser.businessCriticalChange`와 코드 공유 없이 같은 계약, 독립 벡터는
 * `BusinessCriticalChangeCommandBodySpec`). 순수 계산(승인 TX1 안에서 한 번 — 실행·기한 확인·재전송은 저장한 바이트 그대로).
 * - 지문 줄: `mop1`, `ApplyBusinessCriticalChange`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, businessId, baseVersion, proposalId, contentDigest.
 * - 본문: 근거 공통 9 + `operation` + 명령 필드 4. 사업자등록번호·상호·내용 필드 없음(P-6·M-5 — 내용은 MASTER 제안 행). toString에 근거 참조·digest가 없다.
 */
data class BusinessCriticalChangeCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val businessId: String,
    val baseVersion: Long,
    val proposalId: String,
    val contentDigest: String,
) {
    init {
        ApprovalBodyRules.common(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef)
        require(Regex("^biz_${ApprovalBodyRules.ULID}$").matches(businessId)) { "business id format" }
        require(baseVersion >= 0) { "base version" }
        require(Regex("^bcp_${ApprovalBodyRules.ULID}$").matches(proposalId)) { "proposal id format" }
        require(Regex("^h1:[0-9a-f]{64}$").matches(contentDigest)) { "content digest format" }
    }

    private val fields: List<String> get() = listOf(businessId, baseVersion.toString(), proposalId, contentDigest)

    fun fingerprint(): String = ApprovalBodyRules.fingerprint(OPERATION, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fields)

    fun json(): ByteArray = ApprovalBodyRules.json(
        approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint(), OPERATION,
        listOf("businessId" to businessId, "baseVersion" to baseVersion.toString(), "proposalId" to proposalId, "contentDigest" to contentDigest),
    )

    override fun toString(): String = "BusinessCriticalChangeCommandBody(approvalId=$approvalId, businessId=$businessId)"

    companion object {
        const val OPERATION = "ApplyBusinessCriticalChange"
    }
}
