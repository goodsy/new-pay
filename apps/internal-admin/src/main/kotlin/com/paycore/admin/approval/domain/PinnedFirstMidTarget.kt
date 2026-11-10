package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * 첫 Default MID 발급 요청 시점의 고정 대상(MERCHANT-OPS-002 B2-3 §9.5 pinned): `{merchantId, merchantName, accountCount:0, contractId, agreementId, revisionNo, approved:true,
 * effectiveFrom, effectiveTo, primarySiteId, channelType}` — 사업자등록번호·채널 식별값 없음. 승인 TX1이 MASTER 재조회 값과 [sameTargetAs]로 비교한다(다르면 INVALIDATED(TARGET_CHANGED),
 * 표시 이름은 비교하지 않는다). 발급 적격의 권위는 MASTER 실행 TX(DEFAULT_ALREADY_ISSUED·REVISION_NOT_APPROVED·SITE_OUTSIDE_CONTRACT_SCOPE 등).
 */
data class PinnedFirstMidTarget(
    override val merchantId: String,
    val merchantName: String,
    val contractId: String,
    val agreementId: String,
    val revisionNo: Int,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val primarySiteId: String,
    val channelType: String,
    val accountCount: Int = 0,
    val approved: Boolean = true,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(PinnedContractApprovalTarget.NAME.matches(merchantName)) { "merchant name" }
        require(PinnedContractApprovalTarget.CONTRACT_ID.matches(contractId)) { "contract id format" }
        require(AGREEMENT_ID.matches(agreementId)) { "agreement id format" }
        require(revisionNo >= 1) { "revision number" }
        require(PinnedContractApprovalTarget.SITE_ID.matches(primarySiteId)) { "site id format" }
        require(CHANNEL.matches(channelType)) { "channel type" }
        require(accountCount >= 0) { "account count" }
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) { "effective period" }
    }

    fun sameTargetAs(current: PinnedFirstMidTarget): Boolean = copy(merchantName = current.merchantName) == current

    override fun toSnapshotJson(): String {
        val time = PinnedContractApprovalTarget.TIME
        return "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)},\"accountCount\":$accountCount," +
            "\"contractId\":\"$contractId\",\"agreementId\":\"$agreementId\",\"revisionNo\":$revisionNo,\"approved\":$approved," +
            "\"effectiveFrom\":\"${time.format(effectiveFrom)}\",\"effectiveTo\":${effectiveTo?.let { "\"${time.format(it)}\"" } ?: "null"}," +
            "\"primarySiteId\":\"$primarySiteId\",\"channelType\":\"$channelType\"}"
    }

    companion object {
        val AGREEMENT_ID = Regex("^csa_[0-9A-HJKMNP-TV-Z]{26}$")
        private val CHANNEL = Regex("^[A-Z][A-Z_]{0,15}$")
    }
}
