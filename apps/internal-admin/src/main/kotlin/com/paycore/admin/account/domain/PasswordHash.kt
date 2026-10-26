package com.paycore.admin.account.domain

/** 비밀번호 해시(`{bcrypt}` 접두 — D-03). 원문은 domain·DB 어디에도 없다. toString은 값을 내지 않는다(로그·예외 메시지 비노출 — §6). */
class PasswordHash(val value: String) {
    init {
        require(value.startsWith("{") && value.length in 20..100) { "password hash must be an encoded hash with an algorithm prefix" }
    }

    override fun equals(other: Any?): Boolean = other is PasswordHash && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PasswordHash(***)"
}
