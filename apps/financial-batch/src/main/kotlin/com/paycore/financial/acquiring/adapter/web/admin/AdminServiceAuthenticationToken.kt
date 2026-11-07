package com.paycore.financial.acquiring.adapter.web.admin

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * 서명이 맞은 ADMIN 서비스 호출. 권한은 하나다: 조회 키 = `ADMIN_SERVICE`, 조작 키 = `ADMIN_OPERATION`(ACQ-ADMIN-001 — D-ACQA-001, 서로 다른 경로만 연다).
 * principal = 사용한 kid. 자격 증명(서명·키)은 담지 않는다.
 */
class AdminServiceAuthenticationToken(private val keyId: String, operation: Boolean = false) :
    AbstractAuthenticationToken(
        listOf(SimpleGrantedAuthority(if (operation) AdminChannelAuthenticationFilter.ADMIN_OPERATION else AdminChannelAuthenticationFilter.ADMIN_SERVICE)),
    ) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): String = keyId

    override fun toString(): String = "AdminServiceAuthenticationToken(keyId=$keyId)"
}
