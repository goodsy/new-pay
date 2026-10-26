package com.paycore.admin.account.application.required

/** 미인증 시도의 login_name 감사용 비밀 키 HMAC-SHA256(n-2 — 키는 설정, LIVE에서 Demo 키면 기동 실패는 S4). */
interface LoginNameDigester {
    fun digest(loginName: String): ByteArray
}
