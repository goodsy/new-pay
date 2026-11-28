package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.Lifecycle
import java.time.Instant

/**
 * 사업자 중요정보 변경 조회(BIZ-CRIT-APPROVAL-001 §5.1 — jOOQ Reader, 비권위·판정에 쓰지 않음). 모두 **사업자 범위**: 다른 사업자의 제안·이력 ID는 null(404).
 * 호출자 역할(APPROVER·요청자)·승인 요청 미해결 판단은 ADMIN이 MASTER 호출 전에 한다(§5.1·§5.2 — MASTER는 역할을 모른다).
 */
interface BusinessCriticalChangeQuery {
    /** A 단계 조회(m-1): version·생명주기·등록 상태만. */
    fun approvalState(businessId: BusinessId): BusinessApprovalState?

    /** 승인 상세용 제안(마스킹 전후 항목·변경 후 상호·요청 시각). */
    fun proposal(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalView?

    /** 변경 후 원문(M-1 — 대조용. 대표자 이름·주소·연락처 원문 포함 — 로그·지표 금지). */
    fun original(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalOriginal?

    /** 1인 경로 선택 근거 원문(R3-m2 — 그 사업자의 변경 이력 행에 연결된 근거만). */
    fun evidence(businessId: BusinessId, historyId: Long): BusinessChangeEvidenceView?
}

data class BusinessApprovalState(val version: Long, val lifecycle: Lifecycle, val registrationStatus: BusinessRegistrationStatus)

/**
 * 제안 조회 값. [changes]는 제안 시점에 마스킹한 항목(§9 — 원문 없음), [displayName]은 변경 후 상호(사업자 정보 — 개인정보 아님), [representativeChanged]는
 * **대표자 변경이 중요로 판정된 경우만** true(Coordinator 결정 — 감사 `rep=Y`와 같은 뜻, `importantFields`에 `representatives`). Domain
 * `BusinessChangeProposal.representativeChanged`(저장 열 `representative_changed`)는 다른 뜻 — 대표자 집합이 바뀌었는가(일반 포함)다(NIT-1). [contentDigest]는 저장된 `h1:` 값.
 */
data class BusinessChangeProposalView(
    val proposalId: String,
    val businessId: String,
    val baseVersion: Long,
    val contentDigest: String,
    val displayName: String,
    val changedFields: List<String>,
    val importantFields: List<String>,
    val representativeChanged: Boolean,
    val subjectCorrection: Boolean,
    val changes: List<Map<String, Any?>>,
    val requestedBy: String,
    val requestedAt: Instant,
) {
    override fun toString(): String = "BusinessChangeProposalView(proposalId=$proposalId, fields=$changedFields)"
}

/** 변경 후 원문([content] — 저장된 정규 내용 JSON을 그대로 읽은 값). toString 비노출. */
data class BusinessChangeProposalOriginal(val proposalId: String, val businessId: String, val content: Map<String, Any?>) {
    override fun toString(): String = "BusinessChangeProposalOriginal(proposalId=$proposalId)"
}

/** 1인 경로 근거 원문 1건 + 저장 행위자·시각(재리뷰 nit). toString 비노출. */
data class BusinessChangeEvidenceView(val historyId: Long, val businessId: String, val evidenceRef: String, val recordedBy: String, val recordedAt: Instant) {
    override fun toString(): String = "BusinessChangeEvidenceView(historyId=$historyId)"
}
