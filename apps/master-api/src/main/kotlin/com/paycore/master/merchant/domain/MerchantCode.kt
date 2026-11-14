package com.paycore.master.merchant.domain

/**
 * 사람이 읽는 가맹점 코드(MERCHANT-CODE-001 §3.1 — D-MCODE-001 Q-MC-2·5, C-1): 영문 소문자 + 숫자, 첫 글자 영문, 6~14자 `^[a-z][a-z0-9]{5,13}$`.
 * **정규화 없음**(D-CP-001 원칙) — 대문자·공백·하이픈·밑줄은 그대로 거절한다. `_`가 없어 기존 ID(`xxx_<ULID>`)·ACQ 참조와 형식이 겹치지 않는다.
 * 형식 위반은 입력 오류(IllegalArgument → parser 400)이고, 예약어([requireAssignable])는 Domain 업무 거절 `MERCHANT_CODE_RESERVED`(저장된 거절)이다 — 둘을 섞지 않으려고
 * 생성자는 형식만 본다. 코드는 비밀이 아니고 인증 근거도 아니다(L-5). 메시지에 입력 값을 넣지 않는다.
 */
@JvmInline
value class MerchantCode(val value: String) {
    init {
        require(FORMAT.matches(value)) { "merchantCode must match ^[a-z][a-z0-9]{5,13}$" }
    }

    /** 새로 배정할 수 있는 코드인가(예약어 — 완전·접두 일치, Demo seed 고정 코드 예외). 아니면 `MERCHANT_CODE_RESERVED`. */
    fun requireAssignable() {
        if (MerchantCodeReservedWords.isReserved(value)) throw MasterRuleViolation(MasterRejection.MERCHANT_CODE_RESERVED)
    }

    override fun toString(): String = value

    companion object {
        /** binary 비교(대소문자 구별) — DB CHECK `REGEXP_LIKE(…, 'c')`와 같은 문법. */
        val FORMAT = Regex("^[a-z][a-z0-9]{5,13}$")
    }
}
