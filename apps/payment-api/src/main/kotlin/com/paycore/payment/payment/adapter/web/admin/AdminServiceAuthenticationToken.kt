package com.paycore.payment.payment.adapter.web.admin

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** 서명이 맞은 ADMIN 서비스 호출(권한 `ADMIN_SERVICE` 하나). principal = 사용한 kid. 자격 증명(서명·키)은 담지 않는다. */
class AdminServiceAuthenticationToken(private val keyId: String) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(AdminChannelAuthenticationFilter.ADMIN_SERVICE))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): String = keyId

    override fun toString(): String = "AdminServiceAuthenticationToken(keyId=$keyId)"
}
