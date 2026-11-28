package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessId
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사업자 중요정보 변경 조회 채널(BIZ-CRIT-APPROVAL-001 §5.1 — ADMIN 서명 채널 뒤, 조회 permit). 모두 사업자 범위이고 TX를 열지 않는다(조회 Use Case만).
 *
 * - `GET {biz}/approval-state`: A 단계(m-1) — `{"version","lifecycle","registrationStatus"}`만.
 * - `GET {biz}/critical-change-proposals/{bcp}`: 승인 상세(마스킹 항목·변경 후 상호·요청 시각·digest — 원문 없음).
 * - `GET {biz}/critical-change-proposals/{bcp}/original`: 변경 후 원문(M-1). ADMIN이 호출자 역할(APPROVER·그 요청 요청자)과 미해결 상태를 확인한 뒤에만 부른다(§5.1·§5.2 — MASTER는 역할을 모른다).
 * - `GET {biz}/business-change-evidence/{historyId}`: 1인 경로 선택 근거 원문 + 저장 행위자·시각(R3-m2 — 재리뷰 nit: 사업자 범위, 다른 사업자의 이력 ID는 404).
 *
 * 로그는 endpoint·결과 분류·요청 ID만(원문·근거·이름 없음). 형식 위반 400, 없음 404.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/businesses")
class BusinessCriticalChangeQueryController(private val query: BusinessCriticalChangeQuery, private val permits: AdminChannelPermits) {

    private val log = LoggerFactory.getLogger(BusinessCriticalChangeQueryController::class.java)

    @GetMapping("/{businessId}/approval-state")
    fun approvalState(@PathVariable businessId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "businessApprovalState") {
        val id = businessId.takeIf(BusinessSearchRequestParser.BUSINESS_ID::matches) ?: return@guarded malformed(request)
        val state = query.approvalState(BusinessId(id)) ?: return@guarded notFound(request, AdminChannelResponses.BUSINESS_NOT_FOUND)
        AdminChannelResponses.json(
            request, OK, linkedMapOf("version" to state.version, "lifecycle" to state.lifecycle.name, "registrationStatus" to state.registrationStatus.name),
        )
    }

    @GetMapping("/{businessId}/critical-change-proposals/{proposalId}")
    fun proposal(@PathVariable businessId: String, @PathVariable proposalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        guarded(request, "businessChangeProposal") {
            val (biz, bcp) = ids(businessId, proposalId) ?: return@guarded malformed(request)
            val p = query.proposal(biz, bcp) ?: return@guarded notFound(request, AdminChannelResponses.CHANGE_PROPOSAL_NOT_FOUND)
            AdminChannelResponses.json(
                request, OK,
                linkedMapOf(
                    "proposalId" to p.proposalId, "businessId" to p.businessId, "baseVersion" to p.baseVersion, "contentDigest" to p.contentDigest, "displayName" to p.displayName,
                    "changedFields" to p.changedFields, "importantFields" to p.importantFields, "representativeChanged" to p.representativeChanged,
                    "subjectCorrection" to p.subjectCorrection, "changes" to p.changes, "requestedBy" to p.requestedBy, "requestedAt" to AdminChannelResponses.time(p.requestedAt),
                ),
            )
        }

    @GetMapping("/{businessId}/critical-change-proposals/{proposalId}/original")
    fun original(@PathVariable businessId: String, @PathVariable proposalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        guarded(request, "businessChangeProposalOriginal") {
            val (biz, bcp) = ids(businessId, proposalId) ?: return@guarded malformed(request)
            val o = query.original(biz, bcp) ?: return@guarded notFound(request, AdminChannelResponses.CHANGE_PROPOSAL_NOT_FOUND)
            AdminChannelResponses.json(request, OK, linkedMapOf("proposalId" to o.proposalId, "businessId" to o.businessId, "content" to o.content))
        }

    @GetMapping("/{businessId}/business-change-evidence/{historyId}")
    fun evidence(@PathVariable businessId: String, @PathVariable historyId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        guarded(request, "businessChangeEvidence") {
            val id = businessId.takeIf(BusinessSearchRequestParser.BUSINESS_ID::matches) ?: return@guarded malformed(request)
            val history = historyId.takeIf(SEQ::matches)?.toLongOrNull() ?: return@guarded malformed(request)
            val e = query.evidence(BusinessId(id), history) ?: return@guarded notFound(request, AdminChannelResponses.CHANGE_EVIDENCE_NOT_FOUND)
            AdminChannelResponses.json(
                request, OK, linkedMapOf("historyId" to e.historyId, "evidenceRef" to e.evidenceRef, "recordedBy" to e.recordedBy, "recordedAt" to AdminChannelResponses.time(e.recordedAt)),
            )
        }

    private fun ids(businessId: String, proposalId: String): Pair<BusinessId, BusinessChangeProposalId>? {
        if (!BusinessSearchRequestParser.BUSINESS_ID.matches(businessId) || !PROPOSAL_ID.matches(proposalId)) return null
        return BusinessId(businessId) to BusinessChangeProposalId(proposalId)
    }

    private fun guarded(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block().also { log.info("operation=BusinessCriticalChangeQuery endpoint={} requestId={} status={}", endpoint, AdminChannelResponses.requestId(request), it.statusCode.value()) }
            } catch (e: RuntimeException) {
                log.warn(
                    "operation=BusinessCriticalChangeQuery endpoint={} requestId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    endpoint, AdminChannelResponses.requestId(request), e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun malformed(request: HttpServletRequest) = AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)

    private fun notFound(request: HttpServletRequest, code: String) = AdminChannelResponses.error(request, NOT_FOUND, code)

    private companion object {
        val SEQ = Regex("^[1-9][0-9]{0,18}$")
        val PROPOSAL_ID = Regex("^bcp_[0-9A-HJKMNP-TV-Z]{26}$")
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
    }
}
