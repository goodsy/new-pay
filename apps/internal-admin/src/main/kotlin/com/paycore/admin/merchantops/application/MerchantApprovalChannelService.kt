package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.ContractRevisionTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTargetResult
import com.paycore.admin.merchantops.application.provided.MerchantApprovalClient
import com.paycore.admin.merchantops.application.provided.MerchantApprovalCommandOutput
import com.paycore.admin.merchantops.application.provided.MerchantApprovalContract
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.RevisionTargetResult
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.domain.ContractApprovalCommandBody
import com.paycore.admin.merchantops.domain.FirstMidIssuanceCommandBody
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * [MerchantApprovalClient]·[MerchantApprovalContract](MERCHANT-OPS-002 B2-2). MASTER 호출은 모두 [MasterOpsGateway](TX 안 호출 거부·permit·서명·응답 모양 검사)에 위임한다.
 * - [revisionTarget]: MASTER 상세(이미 모양 검사된 문서)에서 그 계약·Revision 한 줄과 가맹점 이름만 꺼낸다. 문서 모양이 예상과 다르면(시험 대역 결함 등) `Unavailable(RESPONSE_UNREADABLE)`.
 * - 본문·지문은 순수 Domain [ContractApprovalCommandBody](MASTER와 코드 공유 없음 — 독립 벡터).
 * 감사는 호출자(approval)가 승인 행 기준으로 남긴다 — 이 서비스는 상세 조회 감사(`MERCHANT_VIEWED`)를 남기지 않는다(승인 흐름의 내부 확인이지 사람의 조회가 아니다).
 */
@Service
class MerchantApprovalChannelService(private val gateway: MasterOpsGateway) : MerchantApprovalClient, MerchantApprovalContract {

    override fun revisionTarget(merchantId: String, contractId: String, revisionNo: Int): RevisionTargetResult =
        when (val found = gateway.detail(merchantId)) {
            MasterCallResult.NotFound -> RevisionTargetResult.MerchantNotFound
            is MasterCallResult.Unavailable -> RevisionTargetResult.Unavailable(found.reason)
            is MasterCallResult.Found -> runCatching { target(found.document, merchantId, contractId, revisionNo) }
                .getOrElse { RevisionTargetResult.Unavailable(UNREADABLE) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun target(document: Map<String, Any?>, merchantId: String, contractId: String, revisionNo: Int): RevisionTargetResult {
        val merchant = document["merchant"] as Map<String, Any?>
        val contract = (document["contracts"] as List<Map<String, Any?>>).firstOrNull { it["contractId"] == contractId } ?: return RevisionTargetResult.RevisionNotFound
        val revision = (contract["revisions"] as List<Map<String, Any?>>).firstOrNull { (it["revisionNo"] as Long) == revisionNo.toLong() }
            ?: return RevisionTargetResult.RevisionNotFound
        return RevisionTargetResult.Found(
            ContractRevisionTarget(
                merchantId = merchant["merchantId"] as String, merchantName = merchant["name"] as String, contractId = contractId, revisionNo = revisionNo,
                scopeKind = revision["scopeKind"] as String, scopeSiteIds = (revision["scopeSiteIds"] as List<String>).sorted(),
                effectiveFrom = Instant.parse(revision["effectiveFrom"] as String), effectiveTo = (revision["effectiveTo"] as String?)?.let(Instant::parse),
                approved = revision["approvedAt"] != null, registeredBy = revision["registeredBy"] as String,
            ).also { check(it.merchantId == merchantId) { "detail of another merchant" } },
        )
    }

    override fun firstMidTarget(merchantId: String, contractId: String, agreementId: String, revisionNo: Int, primarySiteId: String): FirstMidTargetResult =
        when (val found = gateway.detail(merchantId)) {
            MasterCallResult.NotFound -> FirstMidTargetResult.MerchantNotFound
            is MasterCallResult.Unavailable -> FirstMidTargetResult.Unavailable(found.reason)
            is MasterCallResult.Found -> runCatching { firstMid(found.document, merchantId, contractId, agreementId, revisionNo, primarySiteId) }
                .getOrElse { FirstMidTargetResult.Unavailable(UNREADABLE) }
        }

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun firstMid(document: Map<String, Any?>, merchantId: String, contractId: String, agreementId: String, revisionNo: Int, primarySiteId: String): FirstMidTargetResult {
        val merchant = document["merchant"] as Map<String, Any?>
        check(merchant["merchantId"] == merchantId) { "detail of another merchant" }
        val contract = (document["contracts"] as List<Map<String, Any?>>).firstOrNull { it["contractId"] == contractId }
        val revision = (target(document, merchantId, contractId, revisionNo) as? RevisionTargetResult.Found)?.target
        val agreementExists = contract != null && (contract["agreements"] as List<Map<String, Any?>>).any { it["agreementId"] == agreementId }
        val site = (document["sites"] as List<Map<String, Any?>>).firstOrNull { it["merchantSiteId"] == primarySiteId }
        val inScope = revision != null && site != null && (revision.scopeKind == "MERCHANT" || primarySiteId in revision.scopeSiteIds)
        return FirstMidTargetResult.Found(
            FirstMidTarget(
                merchantId, merchant["name"] as String, (document["accounts"] as List<*>).size, contractId, agreementId, agreementExists, revision, primarySiteId,
                site?.get("lifecycle") as String?, site?.get("channelType") as String?, inScope,
            ),
        )
    }

    override fun execute(operation: MerchantApprovalOperation, body: ByteArray): MerchantApprovalResult = gateway.approval(operation, body, deadline = false)

    override fun confirmDeadline(operation: MerchantApprovalOperation, body: ByteArray): MerchantApprovalResult = gateway.approval(operation, body, deadline = true)

    override fun decision(approvalId: String): MerchantApprovalResult = gateway.approvalDecision(approvalId)

    override fun firstDefaultIssuance(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantId: String, contractId: String,
        agreementId: String, revisionNo: Int, primarySiteId: String,
    ): MerchantApprovalCommandOutput {
        val body = FirstMidIssuanceCommandBody(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, merchantId, contractId, agreementId, revisionNo, primarySiteId)
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    override fun contractRevisionApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, contractId: String, revisionNo: Int,
    ): MerchantApprovalCommandOutput {
        val body = ContractApprovalCommandBody(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, contractId, revisionNo)
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    private companion object {
        const val UNREADABLE = "RESPONSE_UNREADABLE"
    }
}
