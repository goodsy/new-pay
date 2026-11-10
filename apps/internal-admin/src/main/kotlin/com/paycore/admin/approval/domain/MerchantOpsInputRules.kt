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

    fun evidenceProblem(evidenceRef: String): String? = when {
        !ReacquisitionInputRules.EVIDENCE.matches(evidenceRef) -> FORMAT
        OperatorCancelInputRules.containsCardNumber(evidenceRef) -> OperatorCancelInputRules.CARD_NUMBER_PATTERN
        else -> null
    }

    private const val FORMAT = "FORMAT"
}
