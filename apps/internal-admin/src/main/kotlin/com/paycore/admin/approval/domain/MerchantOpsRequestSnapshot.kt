package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * 가맹점 2인 승인 요청 고정 값(MERCHANT-OPS-002 B2-2 `admin_merchant_ops_command` — INSERT-only). 승인 요청과 같은 TX에서 한 번 쓰고 바꾸지 않는다. [pinnedTarget]이 승인 TX1 비교의
 * 권위 값, [evidenceRef]는 업무 근거 참조(D-MOPS-008 ① — MASTER 본문·`mop1` 지문에 들어가고, ADMIN 화면에는 APPROVER·요청자만 별도 조회). 근거 참조는 감사·로그·지표·목록·상세에 없다.
 * [commandKind]는 B2-3(첫 MID 발급)이 같은 표를 쓰도록 둔 구분 값이다.
 */
data class MerchantOpsRequestSnapshot(
    val approvalId: String,
    val commandKind: MerchantOpsCommandKind,
    val merchantId: String,
    val pinnedTarget: PinnedMerchantTarget,
    val evidenceRef: String,
    val createdAt: Instant,
) {
    init {
        require(ID.matches(approvalId)) { "approval id format" }
        require(pinnedTarget.merchantId == merchantId) { "the pinned target is the commanded merchant" }
        require(
            (commandKind == MerchantOpsCommandKind.CONTRACT_APPROVAL && pinnedTarget is PinnedContractApprovalTarget) ||
                (commandKind == MerchantOpsCommandKind.FIRST_MID_ISSUANCE && pinnedTarget is PinnedFirstMidTarget) ||
                (commandKind == MerchantOpsCommandKind.PAYMENT_METHOD_ACTIVATION && pinnedTarget is PinnedActivationApprovalTarget) ||
                (commandKind == MerchantOpsCommandKind.SITE_REVIEW_APPROVAL && pinnedTarget is PinnedSiteReviewApprovalTarget) ||
                (commandKind == MerchantOpsCommandKind.CURRENCY_ENABLEMENT && pinnedTarget is PinnedCurrencyEnablementTarget),
        ) { "the pinned target kind matches the command kind" }
        require(MerchantOpsInputRules.evidenceProblem(evidenceRef) == null) { "evidence format" }
    }

    override fun toString(): String = "MerchantOpsRequestSnapshot(approvalId=$approvalId, kind=$commandKind, merchantId=$merchantId)"

    private companion object {
        val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}

/** 명령 행 종류(V9 CHECK와 같다). */
enum class MerchantOpsCommandKind {
    CONTRACT_APPROVAL,

    /** B2-3 첫 Default MID 발급. */
    FIRST_MID_ISSUANCE,

    /** B3-1 결제수단 개통 승인(§9.16). V11 CHECK에는 B3-2~4 값도 있으나 앱은 배포된 배치의 값만 만든다(C-42 — B3-1 둘 + B3-2 통화). */
    PAYMENT_METHOD_ACTIVATION,

    /** B3-1 Site 심사 승인(APPROVED 고정). */
    SITE_REVIEW_APPROVAL,

    /** B3-2 통화 활성화(효력 시작 AT_EXECUTION·DATE — D-MOPS-009 ②). */
    CURRENCY_ENABLEMENT,
}
