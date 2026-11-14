package com.paycore.admin.approval.domain

/**
 * 가맹점 2인 승인 요청 입력 규칙(MERCHANT-OPS-002 B2-2 §9.5 — TX·MASTER 호출 전, 서버는 자르거나 고치지 않는다). 첫 거절 필드와 고정 이유 code(값 원문 없음).
 * - 근거 참조(D-MOPS-008 ① 필수): 재매입 `evidence_ref`와 같은 문법 [ReacquisitionInputRules.EVIDENCE]·카드 번호 모양 거부(ACQ-ADMIN-001 B1-A 리뷰 M-1 — 같은 형식 규칙).
 * - merchantId `mer_ULID`, contractId `ctr_ULID`, revisionNo 1..2147483647(C-30).
 */
object MerchantOpsInputRules {
    fun contractApprovalProblem(merchantId: String, contractId: String, revisionNo: Long, evidenceRef: String): Pair<String, String>? = when {
        !ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId) -> "merchantId" to FORMAT
        !PinnedContractApprovalTarget.CONTRACT_ID.matches(contractId) -> "contractId" to FORMAT
        revisionNo !in 1..Int.MAX_VALUE.toLong() -> "revisionNo" to "RANGE"
        else -> evidenceProblem(evidenceRef)?.let { "evidenceRef" to it }
    }

    /** B2-3 첫 MID 발급: merchantId·contractId·agreementId(`csa_ULID`)·revisionNo·primarySiteId(`site_ULID`)·근거 참조(같은 문법). */
    @Suppress("LongParameterList")
    fun firstMidProblem(merchantId: String, contractId: String, agreementId: String, revisionNo: Long, primarySiteId: String, evidenceRef: String): Pair<String, String>? = when {
        !ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId) -> "merchantId" to FORMAT
        !PinnedContractApprovalTarget.CONTRACT_ID.matches(contractId) -> "contractId" to FORMAT
        !PinnedFirstMidTarget.AGREEMENT_ID.matches(agreementId) -> "agreementId" to FORMAT
        revisionNo !in 1..Int.MAX_VALUE.toLong() -> "revisionNo" to "RANGE"
        !PinnedContractApprovalTarget.SITE_ID.matches(primarySiteId) -> "primarySiteId" to FORMAT
        else -> evidenceProblem(evidenceRef)?.let { "evidenceRef" to it }
    }

    /** B3-1 개통 승인: merchantId·merchantAccountId(`mid_ULID`)·activationId(`act_ULID`)·근거 참조(같은 문법 — 기관 회신 참조, C-36). */
    fun activationProblem(merchantId: String, merchantAccountId: String, activationId: String, evidenceRef: String): Pair<String, String>? = when {
        !ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId) -> "merchantId" to FORMAT
        !PinnedActivationApprovalTarget.MERCHANT_ACCOUNT_ID.matches(merchantAccountId) -> "merchantAccountId" to FORMAT
        !PinnedActivationApprovalTarget.ACTIVATION_ID.matches(activationId) -> "activationId" to FORMAT
        else -> evidenceProblem(evidenceRef)?.let { "evidenceRef" to it }
    }

    /** B3-1 Site 심사 승인: merchantId·merchantSiteId(`site_ULID`)·사유 코드(승인 목록 C-37 — `REVIEW_PASSED`만)·근거 참조(PG 심사 근거). */
    fun siteReviewProblem(merchantId: String, merchantSiteId: String, reasonCode: String, evidenceRef: String): Pair<String, String>? = when {
        !ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId) -> "merchantId" to FORMAT
        !PinnedContractApprovalTarget.SITE_ID.matches(merchantSiteId) -> "merchantSiteId" to FORMAT
        reasonCode !in PinnedSiteReviewApprovalTarget.APPROVE_REASONS -> "reasonCode" to "NOT_ALLOWED"
        else -> evidenceProblem(evidenceRef)?.let { "evidenceRef" to it }
    }

    /**
     * B3-2 통화 활성화(§9.26 — D-MOPS-009 ②, C-41): merchantId·merchantAccountId·통화(KRW만 — `NOT_ALLOWED`)·`effectiveFromMode`(`AT_EXECUTION`·`DATE`, 그 밖 `NOT_ALLOWED`)·
     * `effectiveFromDate`(DATE만 필수 `REQUIRED`, AT_EXECUTION이면 `NOT_ALLOWED`, `YYYY-MM-DD` 실재 날짜 `FORMAT`, [today](요청 시각 KST 날짜) 다음 날 이후 — 아니면 `NOT_FUTURE`)·
     * `effectiveToDate`(선택, 같은 형식, 시작일(AT_EXECUTION이면 [today]) 뒤 — 아니면 `RANGE`, C-7 배타 끝)·근거 참조. 날짜 판정은 advisory(권위는 MASTER — 소급·빈 기간 거절).
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun currencyProblem(
        merchantId: String, merchantAccountId: String, currency: String, effectiveFromMode: String, effectiveFromDate: String?, effectiveToDate: String?, evidenceRef: String,
        today: java.time.LocalDate,
    ): Pair<String, String>? {
        val from = effectiveFromDate?.let(::isoDate)
        val to = effectiveToDate?.let(::isoDate)
        return when {
            !ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId) -> "merchantId" to FORMAT
            !PinnedActivationApprovalTarget.MERCHANT_ACCOUNT_ID.matches(merchantAccountId) -> "merchantAccountId" to FORMAT
            currency !in PinnedCurrencyEnablementTarget.CURRENCIES -> "currency" to NOT_ALLOWED
            effectiveFromMode !in PinnedCurrencyEnablementTarget.MODES -> "effectiveFromMode" to NOT_ALLOWED
            effectiveFromMode == PinnedCurrencyEnablementTarget.AT_EXECUTION && effectiveFromDate != null -> "effectiveFromDate" to NOT_ALLOWED
            effectiveFromMode == PinnedCurrencyEnablementTarget.DATE && effectiveFromDate == null -> "effectiveFromDate" to "REQUIRED"
            effectiveFromDate != null && from == null -> "effectiveFromDate" to FORMAT
            from != null && !from.isAfter(today) -> "effectiveFromDate" to "NOT_FUTURE"
            effectiveToDate != null && to == null -> "effectiveToDate" to FORMAT
            to != null && !to.isAfter(from ?: today) -> "effectiveToDate" to "RANGE"
            else -> evidenceProblem(evidenceRef)?.let { "evidenceRef" to it }
        }
    }

    /** 정확히 `YYYY-MM-DD`이고 실재하는 날짜(ISO, 자동 보정 없음). */
    private fun isoDate(text: String): java.time.LocalDate? =
        if (ISO_DATE.matches(text)) runCatching { java.time.LocalDate.parse(text) }.getOrNull()?.takeIf { it.toString() == text } else null

    fun evidenceProblem(evidenceRef: String): String? = when {
        !ReacquisitionInputRules.EVIDENCE.matches(evidenceRef) -> FORMAT
        OperatorCancelInputRules.containsCardNumber(evidenceRef) -> OperatorCancelInputRules.CARD_NUMBER_PATTERN
        else -> null
    }

    private const val FORMAT = "FORMAT"
    private const val NOT_ALLOWED = "NOT_ALLOWED"
    private val ISO_DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
}
