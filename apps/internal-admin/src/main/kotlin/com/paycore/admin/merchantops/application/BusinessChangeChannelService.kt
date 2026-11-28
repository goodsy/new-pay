package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.BusinessChangeClient
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalRequest
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalResult
import com.paycore.admin.merchantops.application.provided.BusinessChangeRead
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import org.springframework.stereotype.Service

/**
 * [BusinessChangeClient] 구현(BIZ-CRIT-APPROVAL-001 §5.1 — MASTER 사업자 채널 [MasterBusinessGateway] 위 얇은 층, DB TX 없음). 감사는 호출하는 approval 서비스가 한다.
 * - 제안: 2인 경로 입력 규칙(사유 필수 `reason` REQUIRED·근거 참조 필수·형식·카드번호 모양 — BR-BIZ-232, 내용 값 규칙 = 1인 수정과 같은 `BusinessProfileInputRules`) → MASTER 명령.
 *   MASTER 키 = `mo1.<계정 ULID>.<submissionId>`(1인 명령과 같은 이름공간 규칙 — 다른 operation 범위라 충돌 없음), correlationId `mos.<submissionId>`.
 * - 조회: 엄격 모양 검사 결과만 돌려준다(값을 바꾸지 않는다).
 */
@Service
class BusinessChangeChannelService(private val gateway: MasterBusinessGateway, private val window: BusinessReadWindow) : BusinessChangeClient {

    override fun inputErrors(request: BusinessChangeProposalRequest): List<BusinessFieldError>? {
        val change = BusinessProfileRequest.Change(
            BusinessProfileOperation.UPDATE, request.businessId, request.submissionId, request.version, request.reason, request.content, request.evidenceRef,
        )
        if (!BusinessProfileInputRules.identifiersWellFormed(change)) return null
        return buildList {
            if (request.reason == null) add(BusinessFieldError("reason", "REQUIRED"))
            addAll(BusinessProfileInputRules.errors(change))
        }.sortedWith(compareBy({ it.path }, { it.code }))
    }

    override fun propose(request: BusinessChangeProposalRequest): BusinessChangeProposalResult {
        check(ACCOUNT_ID.matches(request.actorId)) { "session actor id format" }
        val errors = inputErrors(request) ?: return BusinessChangeProposalResult.Invalid(emptyList())
        if (errors.isNotEmpty()) return BusinessChangeProposalResult.Invalid(errors)
        val body = linkedMapOf<String, Any?>("businessId" to request.businessId, "version" to request.version, "reason" to request.reason)
        BusinessCommandBodies.content(body, request.content)
        body["evidenceRef"] = request.evidenceRef
        val command = MasterBusinessCommand(
            BusinessProfileOperation.UPDATE, "mo1.${request.actorId.removePrefix(ACCOUNT_PREFIX)}.${request.submissionId}", ACTOR_PREFIX + request.actorId,
            CORRELATION_PREFIX + request.submissionId, body,
        )
        return when (val result = gateway.criticalChangeProposal(command)) {
            is MasterBusinessCommandResult.Decided -> if (result.succeeded) proposed(result) else BusinessChangeProposalResult.Rejected(requireNotNull(result.rejection), result.replayed)
            is MasterBusinessCommandResult.Invalid -> BusinessChangeProposalResult.Invalid(result.fieldErrors)
            MasterBusinessCommandResult.KeyReused -> BusinessChangeProposalResult.KeyReused
            is MasterBusinessCommandResult.Busy -> BusinessChangeProposalResult.Busy(result.reason)
            is MasterBusinessCommandResult.RetrySameKey -> BusinessChangeProposalResult.RetrySameKey(result.reason)
            is MasterBusinessCommandResult.Unknown -> BusinessChangeProposalResult.Unknown(result.reason)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun proposed(result: MasterBusinessCommandResult.Decided): BusinessChangeProposalResult {
        val p = result.proposal ?: return BusinessChangeProposalResult.Unknown(UNREADABLE)
        return BusinessChangeProposalResult.Proposed(
            proposalId = p["proposalId"] as String, baseVersion = p["baseVersion"] as Long, contentDigest = p["contentDigest"] as String,
            changedFields = p["changedFields"] as List<String>, representativeChanged = p["representativeChanged"] as Boolean, subjectCorrection = p["subjectCorrection"] as Boolean,
            requestedBy = p["requestedBy"] as String, replayed = result.replayed,
        )
    }

    override fun approvalState(businessId: String): BusinessChangeRead = read(gateway.approvalState(businessId))

    override fun original(actorId: String, businessId: String, proposalId: String): BusinessChangeRead =
        if (!window.admit(actorId)) BusinessChangeRead.RateLimited else read(gateway.proposalOriginal(businessId, proposalId))

    private fun read(result: MasterCallResult): BusinessChangeRead = when (result) {
        is MasterCallResult.Found -> BusinessChangeRead.Found(result.document)
        MasterCallResult.NotFound -> BusinessChangeRead.NotFound
        is MasterCallResult.Unavailable -> BusinessChangeRead.Unavailable(result.reason)
    }

    private companion object {
        const val ACCOUNT_PREFIX = "adm_"
        const val ACTOR_PREFIX = "admin:"
        const val CORRELATION_PREFIX = "mos."
        const val UNREADABLE = "RESPONSE_UNREADABLE"
        val ACCOUNT_ID = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
