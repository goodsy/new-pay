package com.paycore.admin.approval.domain

/**
 * 가맹점 코드 변경 승인 요청 시점의 고정 대상(MERCHANT-CODE-001 MC-1b §5.3 — D-MCODE-002 Q-MC-7·Q-MC-10): `{merchantId, merchantName, currentCode, newCode, reasonCode,
 * contractApproved:false, codeChangeCount:0}`. **등록자 필드 없음**(Q-MC-10 — 요청자 ≠ 승인자만). 근거 참조 없음.
 * 요청은 MASTER 상세 `merchantCodeChangeable`(= 승인 사실 없음 ∧ 변경 0회 ∧ ACTIVE — advisory)일 때만 만들어지므로 [contractApproved]·[codeChangeCount]는 항상 false·0이다(그 밖 값은 형식 위반).
 * A 단계 MASTER 재조회의 `currentCode`·변경 가능 여부가 다르면 INVALIDATED(TARGET_CHANGED) — MASTER 미호출(§5.3). 새 코드 사용 가능 여부는 A 단계에서 보지 않는다(C-4 — 권위는 MASTER 판정 12).
 * 표시 이름은 비교하지 않는다(C-16). 코드는 공개값(비밀 아님 — L-5)이라 감사 detail `from=;to=`에 남긴다(D-MCODE-002 ⑤).
 */
data class PinnedMerchantCodeChangeTarget(
    override val merchantId: String,
    val merchantName: String,
    val currentCode: String,
    val newCode: String,
    val reasonCode: String,
    val contractApproved: Boolean = false,
    val codeChangeCount: Int = 0,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(PinnedContractApprovalTarget.NAME.matches(merchantName)) { "merchant name" }
        require(CODE.matches(currentCode) && CODE.matches(newCode)) { "merchant code format (§3.1)" }
        require(currentCode != newCode) { "the new code differs from the current code" }
        require(reasonCode in REASONS) { "reason code (C-12)" }
        require(!contractApproved && codeChangeCount == 0) { "a change is requested only before the first contract approval and before any change" }
    }

    fun sameTargetAs(current: PinnedMerchantCodeChangeTarget): Boolean = copy(merchantName = current.merchantName) == current

    /** 감사 detail(D-MCODE-002 ⑤ — 이 승인 건의 감사 행 공통): `from=<현재>;to=<새>;reason=<사유>`. */
    fun auditDetail(): String = "from=$currentCode;to=$newCode;reason=$reasonCode"

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)},\"currentCode\":\"$currentCode\",\"newCode\":\"$newCode\"," +
            "\"reasonCode\":\"$reasonCode\",\"contractApproved\":$contractApproved,\"codeChangeCount\":$codeChangeCount}"

    companion object {
        /** MASTER `MerchantCode`와 같은 문법(§3.1 — 정규화 없음, 코드 공유 없이 복제). */
        val CODE = Regex("^[a-z][a-z0-9]{5,13}$")

        /** C-12 고정 사유 목록(Demo 값 — MASTER `MerchantCodeChangeReason` 복제). */
        val REASONS = listOf("TYPO_CORRECTION", "MERCHANT_REQUEST", "OTHER_POLICY")

        /** 스냅샷 키별 추출(MySQL JSON 열은 공백·키 순서를 정규화해 돌려준다 — 키 하나씩 찾는다). */
        private fun field(snapshot: String, name: String, value: String): String? = Regex("\"$name\"\\s*:\\s*\"($value)\"").find(snapshot)?.groupValues?.get(1)

        /**
         * 승인 행 스냅샷([toSnapshotJson] — 저장 뒤 MySQL 정규화 표기 포함)에서 감사 detail을 다시 만든다(TX2·회복 job의 감사 행도 같은 `from=;to=;reason=` — §5.5). 이 종류가 아니거나 모양이 다르면 null.
         */
        fun auditDetailOf(request: ApprovalRequest): String? {
            if (request.kind != ApprovalKind.MERCHANT_CODE_CHANGE) return null
            val from = field(request.targetSnapshot, "currentCode", CODE_TEXT) ?: return null
            val to = field(request.targetSnapshot, "newCode", CODE_TEXT) ?: return null
            val reason = field(request.targetSnapshot, "reasonCode", "[A-Z_]{1,32}") ?: return null
            return "from=$from;to=$to;reason=$reason"
        }

        private const val CODE_TEXT = "[a-z][a-z0-9]{5,13}"
    }
}
