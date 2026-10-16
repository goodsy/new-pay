package com.paycore.master.merchant.domain

/**
 * 기관(또는 PG 심사) 근거 참조(D-MD-016·017·018, D-MS-003): 근거 문서·회신 번호 같은 **불투명 참조**만 담는다(원문·개인정보·Secret 금지).
 * 1~128자 `[A-Za-z0-9._:/#-]`. 오류 메시지에 입력값을 넣지 않는다.
 */
@JvmInline
value class EvidenceRef(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "evidenceRef must be 1..$MAX_LENGTH characters of [A-Za-z0-9._:/#-]" }
    }

    private companion object {
        const val MAX_LENGTH = 128

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '/' || c == '#' || c == '-'
    }
}
