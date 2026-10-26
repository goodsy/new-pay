package com.paycore.admin.account.domain

/**
 * 로그인 이름(변경 기능 없음 — D-06). 형식: 소문자 영문·숫자로 시작하는 3~64자, 소문자·숫자·`.`·`_`·`-`만(구현 결정 D-62).
 * 대문자를 정규화하지 않고 거부한다 — 저장·비교가 입력 그대로(utf8mb4_0900_bin)라 "Admin"과 "admin"이 서로 다른 계정이 되는 혼동을 입구에서 막는다.
 */
@JvmInline
value class LoginName(val value: String) {
    init {
        require(FORMAT.matches(value)) { "login name must be 3-64 characters of a-z, 0-9, '.', '_', '-' starting with a letter or digit" }
    }

    override fun toString(): String = value

    companion object {
        val FORMAT = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    }
}
