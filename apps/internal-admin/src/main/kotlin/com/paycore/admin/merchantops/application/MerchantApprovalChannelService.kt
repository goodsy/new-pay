package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.ActivationTarget
import com.paycore.admin.merchantops.application.provided.CodeChangeTarget
import com.paycore.admin.merchantops.application.provided.ContractRevisionTarget
import com.paycore.admin.merchantops.application.provided.CurrencyTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTargetResult
import com.paycore.admin.merchantops.application.provided.MerchantApprovalClient
import com.paycore.admin.merchantops.application.provided.MerchantApprovalCommandOutput
import com.paycore.admin.merchantops.application.provided.MerchantApprovalContract
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetResult
import com.paycore.admin.merchantops.application.provided.RevisionTargetResult
import com.paycore.admin.merchantops.application.provided.SiteReviewTarget
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.domain.ActivationApprovalCommandBody
import com.paycore.admin.merchantops.domain.ContractApprovalCommandBody
import com.paycore.admin.merchantops.domain.CurrencyEnablementCommandBody
import com.paycore.admin.merchantops.domain.FirstMidIssuanceCommandBody
import com.paycore.admin.merchantops.domain.MerchantCodeChangeCommandBody
import com.paycore.admin.merchantops.domain.SiteReviewApprovalCommandBody
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

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
                approved = revision["approvedAt"] != null, registeredBy = revision["registeredBy"] as String, merchantCode = merchant["merchantCode"] as String,
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
                site?.get("lifecycle") as String?, site?.get("channelType") as String?, inScope, merchant["merchantCode"] as String,
            ),
        )
    }

    override fun activationTarget(merchantId: String, merchantAccountId: String, activationId: String, now: Instant): MerchantTargetResult<ActivationTarget> =
        read(merchantId) { document -> activation(document, merchantId, merchantAccountId, activationId, now) }

    override fun siteReviewTarget(merchantId: String, merchantSiteId: String): MerchantTargetResult<SiteReviewTarget> =
        read(merchantId) { document -> siteReview(document, merchantId, merchantSiteId) }

    override fun currencyTarget(merchantId: String, merchantAccountId: String, currency: String, now: Instant): MerchantTargetResult<CurrencyTarget> =
        read(merchantId) { document -> currency(document, merchantId, merchantAccountId, currency, now) }

    /**
     * B3-2 통화 활성화 대상: MID 한 줄 → 같은 통화 기록 중 [now]에 끝나지 않은 것(종료 시각 없음 또는 now < 종료). 지난 기간만 있는 기록은 열린 기록이 아니다.
     * ADMIN 미리 보기는 끝나지 않은 기록이 하나라도 있으면 부적격으로 본다(MASTER 중첩 판정보다 넓게 막을 수는 있어도 더 허용하지 않는다 — fail-closed).
     */
    @Suppress("UNCHECKED_CAST")
    private fun currency(document: Map<String, Any?>, merchantId: String, merchantAccountId: String, currency: String, now: Instant): CurrencyTarget {
        val merchant = document["merchant"] as Map<String, Any?>
        check(merchant["merchantId"] == merchantId) { "detail of another merchant" }
        val account = (document["accounts"] as List<Map<String, Any?>>).firstOrNull { it["merchantAccountId"] == merchantAccountId }
        val open = (account?.get("currencies") as List<Map<String, Any?>>?).orEmpty().any {
            it["currencyCode"] == currency && (it["effectiveTo"] as String?)?.let { to -> now.isBefore(Instant.parse(to)) } != false
        }
        return CurrencyTarget(merchantId, merchant["name"] as String, merchantAccountId, account?.get("lifecycle") as String?, currency, open, merchant["merchantCode"] as String)
    }

    override fun codeChangeTarget(merchantId: String): MerchantTargetResult<CodeChangeTarget> = read(merchantId) { document -> codeChange(document, merchantId) }

    /** MC-1b: 상세(응답 모양 검사를 통과한 문서)의 `merchant.merchantCode`·`merchantCodeHistory`·`merchantCodeChangeable`. */
    @Suppress("UNCHECKED_CAST")
    private fun codeChange(document: Map<String, Any?>, merchantId: String): CodeChangeTarget {
        val merchant = document["merchant"] as Map<String, Any?>
        check(merchant["merchantId"] == merchantId) { "detail of another merchant" }
        val history = document["merchantCodeHistory"] as List<Map<String, Any?>>
        return CodeChangeTarget(merchantId, merchant["name"] as String, merchant["merchantCode"] as String, document["merchantCodeChangeable"] as Boolean, history.size - 1)
    }

    override fun merchantCodeChange(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantId: String, currentCode: String,
        newCode: String, reasonCode: String,
    ): MerchantApprovalCommandOutput {
        val body = MerchantCodeChangeCommandBody(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, merchantId, currentCode, newCode, reasonCode)
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    private fun <T> read(merchantId: String, extract: (Map<String, Any?>) -> T): MerchantTargetResult<T> = when (val found = gateway.detail(merchantId)) {
        MasterCallResult.NotFound -> MerchantTargetResult.MerchantNotFound
        is MasterCallResult.Unavailable -> MerchantTargetResult.Unavailable(found.reason)
        is MasterCallResult.Found -> runCatching { MerchantTargetResult.Found(extract(found.document)) }.getOrElse { MerchantTargetResult.Unavailable(UNREADABLE) }
    }

    /**
     * B3-1 개통 승인 대상: MID 한 줄 → 그 MID의 개통 행·[now]에 열린 PRIMARY Assignment(ACTIVE·시작 ≤ now < 종료, C-45).
     * **MASTER와 다른 점(review m-1 — 의도된 보수적 판정)**: MASTER `SiteAssignment.isOpenAt`은 ENDED가 아닌 모든 상태(예: ACTIVE 외 상태)를 열린 것으로 본다. ADMIN 미리 보기는 ACTIVE만 열린 것으로 봐서
     * MASTER가 열렸다고 볼 PRIMARY를 부적격으로 볼 수는 있어도(요청 거절 — fail-closed) 그 반대는 없다. 고정한 PRIMARY의 권위 판정은 MASTER 실행 TX(`PRIMARY_SITE_MISMATCH`)다.
     * PRIMARY가 없거나 둘 이상이면 null(미리 보기 부적격 — 권위 판정은 MASTER `PRIMARY_SITE_MISMATCH`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun activation(document: Map<String, Any?>, merchantId: String, merchantAccountId: String, activationId: String, now: Instant): ActivationTarget {
        val merchant = document["merchant"] as Map<String, Any?>
        check(merchant["merchantId"] == merchantId) { "detail of another merchant" }
        val account = (document["accounts"] as List<Map<String, Any?>>).firstOrNull { it["merchantAccountId"] == merchantAccountId }
        val method = (account?.get("paymentMethods") as List<Map<String, Any?>>?)?.firstOrNull { it["activationId"] == activationId }
        val primary = (account?.get("assignments") as List<Map<String, Any?>>?)?.filter {
            it["role"] == "PRIMARY" && it["status"] == "ACTIVE" && !Instant.parse(it["effectiveFrom"] as String).isAfter(now) &&
                (it["effectiveTo"] as String?)?.let { to -> now.isBefore(Instant.parse(to)) } != false
        }?.singleOrNull()
        return ActivationTarget(
            merchantId, merchant["name"] as String, merchantAccountId, account?.get("lifecycle") as String?, activationId, method?.get("paymentMethod") as String?,
            method?.get("status") as String?, method?.get("registeredBy") as String?, primary?.get("merchantSiteId") as String?, merchant["merchantCode"] as String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun siteReview(document: Map<String, Any?>, merchantId: String, merchantSiteId: String): SiteReviewTarget {
        val merchant = document["merchant"] as Map<String, Any?>
        check(merchant["merchantId"] == merchantId) { "detail of another merchant" }
        val site = (document["sites"] as List<Map<String, Any?>>).firstOrNull { it["merchantSiteId"] == merchantSiteId }
        val review = site?.get("review") as Map<String, Any?>?
        return SiteReviewTarget(
            merchantId, merchant["name"] as String, merchantSiteId, site?.get("lifecycle") as String?, site?.get("channelType") as String?,
            site?.let { (review?.get("decision") as String?) ?: PENDING }, site?.get("registeredBy") as String?, merchant["merchantCode"] as String,
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

    override fun paymentMethodActivationApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, activationId: String, primarySiteId: String,
    ): MerchantApprovalCommandOutput {
        val body = ActivationApprovalCommandBody(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, activationId, primarySiteId)
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    override fun siteReviewApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantSiteId: String, reasonCode: String,
    ): MerchantApprovalCommandOutput {
        val body = SiteReviewApprovalCommandBody(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, merchantSiteId, reasonCode)
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    override fun currencyEnablement(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantAccountId: String, currency: String,
        effectiveFromMode: String, effectiveFromDate: LocalDate?, effectiveToDate: LocalDate?,
    ): MerchantApprovalCommandOutput {
        // D-MOPS-009 ②: AT_EXECUTION은 문자열 그대로(확정 시각은 MASTER 실행 시각), DATE는 그 KST 날짜 00:00(C-7). 승인 시각으로 바꾸지 않는다.
        val from = when (effectiveFromMode) {
            CurrencyEnablementCommandBody.AT_EXECUTION -> {
                require(effectiveFromDate == null) { "AT_EXECUTION has no start date" }
                CurrencyEnablementCommandBody.AT_EXECUTION
            }
            DATE_MODE -> CurrencyEnablementCommandBody.startOfKstDay(requireNotNull(effectiveFromDate) { "DATE needs a start date" })
            else -> throw IllegalArgumentException("effective start mode")
        }
        val body = CurrencyEnablementCommandBody(
            approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, merchantAccountId, currency, from, effectiveToDate?.let { CurrencyEnablementCommandBody.startOfKstDay(it) },
        )
        return MerchantApprovalCommandOutput(body.fingerprint(), body.json())
    }

    private companion object {
        const val DATE_MODE = "DATE"
        const val UNREADABLE = "RESPONSE_UNREADABLE"

        /** 심사 결정이 없는 Site(MASTER SiteReviewStatus PENDING). */
        const val PENDING = "PENDING"
    }
}
