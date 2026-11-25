package com.paycore.master.merchant.domain

/**
 * 국내 사업자등록번호(D-MD-008: Business는 이 번호로 하나). 표기는 10자리 숫자만 받는다 — 하이픈 등 다른 표기를 암묵 정규화하지 않고
 * 거절해 같은 번호가 다른 문자열로 중복 등록되는 일을 막는다(fail-closed, 관례). 노출 통제(D-MD-008): [toString]·[masked]는 앞 3자리만
 * 보여 주고 나머지는 가린다. 로그·이벤트·이력·예외 메시지에 평문을 넣지 않는다. 검증번호(체크섬) 검사는 하지 않는다(형식만, 관례).
 */
@JvmInline
value class BusinessRegistrationNumber(val value: String) {
    init {
        require(value.length == LENGTH && value.all { it in '0'..'9' }) { "business registration number must be $LENGTH ASCII digits" }
    }

    /** 변경 이력·로그·[toString]용 가린 값(앞 3자리 + `*`). 관리자 화면·조회 응답은 원문을 쓴다(D-BIZ-007·D-PII-001 ① — 이 값이 아니다). */
    fun masked(): String = value.take(VISIBLE) + "*".repeat(LENGTH - VISIBLE)

    override fun toString(): String = "BusinessRegistrationNumber(${masked()})"

    companion object {
        private const val LENGTH = 10
        private const val VISIBLE = 3

        /**
         * 입력 해석기(BUSINESS-PROFILE-001 BIZ-2 parser — 필드 오류 `registrationNumber`)용 판정: 생성자와 같은 집합(빈 값 REQUIRED, 그 밖 10 ASCII 숫자 아님 FORMAT).
         * null = 통과. 값은 오류에 담지 않는다.
         */
        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.digitsErrorOf(raw, LENGTH)
    }
}
