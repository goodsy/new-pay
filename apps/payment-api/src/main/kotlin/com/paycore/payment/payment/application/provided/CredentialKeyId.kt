package com.paycore.payment.payment.application.provided

/**
 * 가맹점 공개 API Credential(D-CPA-001)의 keyId. 키 원문은 `pc_<test|live>_sk_<keyId>.<256bit 난수 base64url 43자>`이고 keyId는 그중
 * 조회용 식별자다(비밀 아님 — 로그·감사에 남겨도 된다). 형식은 8..32자 영숫자(`_`·`.` 없음: 원문 해석이 모호하지 않게).
 * 오류 메시지에 값을 넣지 않는다.
 */
@JvmInline
value class CredentialKeyId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "credential keyId must be 8..32 alphanumeric characters" }
    }

    companion object {
        val PATTERN = Regex("^[A-Za-z0-9]{8,32}$")
    }
}
