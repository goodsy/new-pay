package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.LoginNameDigester
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 미인증 시도 login_name 감사값 = HMAC-SHA256(설정 비밀 키, UTF-8 login_name) 32바이트(n-2 — 원문·무염 hash를 남기지 않는다). */
class HmacLoginNameDigester(key: ByteArray) : LoginNameDigester {
    private val keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)

    override fun digest(loginName: String): ByteArray = Mac.getInstance(ALGORITHM).apply { init(keySpec) }.doFinal(loginName.toByteArray(Charsets.UTF_8))

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
