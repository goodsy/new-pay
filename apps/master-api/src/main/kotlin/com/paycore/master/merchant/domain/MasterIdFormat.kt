package com.paycore.master.merchant.domain

/**
 * MASTER가 발급하는 식별자 형식(관례, MASTER-DOMAIN-001 명세 §6): `<prefix>_` + ULID 26자(Crockford base32 대문자).
 * 모두 ASCII 30자 이하라 PAYMENT의 MID·Site 컬럼(VARCHAR(64) binary)과 값 객체(빈 값·짝 없는 서로게이트 거부)에 그대로 맞는다.
 * 순번을 드러내지 않는다. 형식은 **확장만** 허용한다(축소하면 저장된 키를 복원할 수 없다). DB CHECK가 같은 형식을 강제한다.
 * 오류 메시지에 입력값을 넣지 않는다(로그 주입·입력 노출 방지).
 */
internal object MasterIdFormat {
    const val ULID_LENGTH = 26
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun requireValid(value: String, prefix: String, field: String) {
        val body = value.removePrefix("${prefix}_")
        require(value.startsWith("${prefix}_") && body.length == ULID_LENGTH && body.all { it in CROCKFORD }) {
            "$field must be '${prefix}_' followed by $ULID_LENGTH Crockford base32 characters"
        }
    }
}
