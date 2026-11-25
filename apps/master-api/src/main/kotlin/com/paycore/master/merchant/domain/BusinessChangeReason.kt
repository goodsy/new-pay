package com.paycore.master.merchant.domain

/**
 * 중요정보 변경 사유 — 화면 이름 '사유 및 비고'(§4.5·C-13, D-FLOW-006): 1~200 code point, 같은 문자 규칙(줄바꿈 불가). 원문은 MASTER 변경 이력에만 남는다(ADMIN 감사는 `reason=PRESENT`).
 * 자유 문구라 운영자가 개인정보를 적을 수 있다(L-4 — 화면 안내만). **카드번호 패턴은 거절**한다(SEC-BIZ-001 M-1 — BR-BIZ-305, D-FLOW-007 ① — 칸 전체에 항상):
 * 문자 규칙 판정 뒤 [CardNumberPattern]이 맞으면 [BusinessFieldErrorCode.CARD_NUMBER_PATTERN]. 새 저장부터 적용(값 객체 생성 시점 — 기존 이력 행은 읽기 경로에서 이 값 객체를 거치지 않는다).
 */
@JvmInline
value class BusinessChangeReason(val value: String) {
    init {
        BusinessTextRule.require("change reason", errorOf(value))
    }

    override fun toString(): String = "BusinessChangeReason(***)"

    companion object {
        const val MAX_CODE_POINTS = 200

        fun errorOf(raw: String): BusinessFieldErrorCode? =
            BusinessTextRule.errorOf(raw, MAX_CODE_POINTS) ?: BusinessFieldErrorCode.CARD_NUMBER_PATTERN.takeIf { CardNumberPattern.matches(raw) }
    }
}