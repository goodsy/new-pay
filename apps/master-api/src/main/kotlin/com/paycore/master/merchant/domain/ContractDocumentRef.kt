package com.paycore.master.merchant.domain

/**
 * 계약서 수령 근거 참조(MERCHANT-FLOW-001 §4A.4 — D-FLOW-001 ②, D-FLOW-003 ⑨, D-MOPS-008 ① 형식): **자유 문구 불가**, 1~128자 `[A-Za-z0-9._:#/-]`, 카드번호 패턴 거부(고정 code
 * [BusinessFieldErrorCode.CARD_NUMBER_PATTERN] — 리뷰 m-5). 기존 승인 근거 [EvidenceRef]는 형식만 보고 바꾸지 않는다(그래서 별도 값 객체).
 * 쓰는 곳: 접수 건 계약 완료(필수 — 사업자 계약 버전의 근거로도 저장), 사업자 계약 해지(선택 — BR-BIZ-339). 원문은 MASTER 표에만 — toString·오류 메시지에 값을 넣지 않는다.
 */
@JvmInline
value class ContractDocumentRef(val value: String) {
    init {
        BusinessTextRule.require("contract document reference", errorOf(value))
    }

    override fun toString(): String = "ContractDocumentRef(***)"

    companion object {
        const val MAX_LENGTH = 128

        /** 빈 값 REQUIRED, 128자 초과 TOO_LONG, 허용 문자 밖 FORMAT, 그 뒤 카드번호 패턴 CARD_NUMBER_PATTERN(값 없음). */
        fun errorOf(raw: String): BusinessFieldErrorCode? = when {
            raw.isEmpty() -> BusinessFieldErrorCode.REQUIRED
            raw.length > MAX_LENGTH -> BusinessFieldErrorCode.TOO_LONG
            !raw.all(::isAllowed) -> BusinessFieldErrorCode.FORMAT
            CardNumberPattern.matches(raw) -> BusinessFieldErrorCode.CARD_NUMBER_PATTERN
            else -> null
        }

        private fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '#' || c == '/' || c == '-'
    }
}
