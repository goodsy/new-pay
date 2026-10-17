package com.paycore.payment.payment.domain

/**
 * VERIFIED 세션의 인증 참조(AUTHN-001 §7.3): `aref_` + base64url 22자(128bit, 서버 생성). 가맹점이 confirm에 넘기는 **식별자**이며 자격 증명이 아니다
 * (confirm은 MID 인증과 바인딩 대조를 요구한다). 로그에는 앞 9자만([toString]).
 */
@JvmInline
value class AuthenticationRef(val value: String) {
    init {
        require(FORMAT.matches(value)) { "authenticationRef must be aref_ followed by 22 base64url characters" }
    }

    override fun toString(): String = "AuthenticationRef(${value.take(VISIBLE)}…)"

    private companion object {
        val FORMAT = Regex("^aref_[A-Za-z0-9_-]{22}$")
        const val VISIBLE = 9
    }
}
