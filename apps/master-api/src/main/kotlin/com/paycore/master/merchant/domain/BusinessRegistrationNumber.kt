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

    /** 감사·화면용 가린 값(앞 3자리 + `*`). */
    fun masked(): String = value.take(VISIBLE) + "*".repeat(LENGTH - VISIBLE)

    override fun toString(): String = "BusinessRegistrationNumber(${masked()})"

    private companion object {
        const val LENGTH = 10
        const val VISIBLE = 3
    }
}
