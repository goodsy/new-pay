package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * 사업자 중요정보 변경 승인 요청의 고정 값(BIZ-CRIT-APPROVAL-001 §3.1·§3.2 — 명령 행 `admin_business_change_command`, INSERT 전용). MASTER 제안 응답에서 받은 **식별자·digest·항목 이름**만 담는다 —
 * 상호·대표자·주소 등 원문은 ADMIN에 저장하지 않는다(M-5, D-ADMIN-005). 원문은 MASTER 제안 행에만 있고 승인 상세는 MASTER 조회로 읽는다.
 *
 * - [baseVersion]: 제안 시점 사업자 version(권위 기준 — 승인 TX1이 MASTER 재조회 version과 비교, MASTER 실행 TX가 다시 비교).
 * - [representativeChanged]: 대표자 변경이 **중요로 판정됨**(MASTER 제안 응답 값 — 감사 `rep=Y|N`과 같은 뜻, Coordinator 결정).
 * - [evidenceRef]: 업무 근거 참조(필수 — P-4, 형식 BR-BIZ-232). 감사·로그·목록에는 원문 없음(`evidence=PRESENT`).
 * toString에는 승인 ID·사업자 ID만.
 */
@Suppress("LongParameterList")
class BusinessChangeRequestSnapshot(
    val approvalId: String,
    val businessId: String,
    val baseVersion: Long,
    val proposalId: String,
    val contentDigest: String,
    val changedFields: List<String>,
    val representativeChanged: Boolean,
    val subjectCorrection: Boolean,
    val evidenceRef: String,
    val createdAt: Instant,
) {
    init {
        require(ApprovalTarget.Business.BUSINESS_ID.matches(businessId)) { "business id format" }
        require(baseVersion >= 0) { "base version must not be negative" }
        require(PROPOSAL_ID.matches(proposalId)) { "proposal id format (bcp_ + ULID)" }
        require(DIGEST.matches(contentDigest)) { "content digest format (h1:)" }
        require(changedFields.isNotEmpty() && changedFields.all(FIELD::matches) && changedFields.distinct() == changedFields) { "changed field names" }
        require(EVIDENCE.matches(evidenceRef)) { "evidence format" }
    }

    /** 감사 detail(값 없음 — §5.2): 항목 이름·대표자 중요 변경·정정 여부·근거 있음. */
    fun auditDetail(): String = "fields=${changedFields.joinToString(",")};rep=${yn(representativeChanged)};subject=${yn(subjectCorrection)};evidence=PRESENT"

    override fun toString(): String = "BusinessChangeRequestSnapshot(approvalId=$approvalId, businessId=$businessId)"

    companion object {
        val PROPOSAL_ID = Regex("^bcp_[0-9A-HJKMNP-TV-Z]{26}$")
        val DIGEST = Regex("^h1:[0-9a-f]{64}$")

        /** MASTER 변경 항목 이름 문법(예: `displayName`, `registration.phoneNumber`, `representatives`). */
        val FIELD = Regex("^[a-z][A-Za-z]{0,39}(\\.[a-z][A-Za-z]{0,39})?$")
        private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")

        private fun yn(value: Boolean) = if (value) "Y" else "N"
    }
}
