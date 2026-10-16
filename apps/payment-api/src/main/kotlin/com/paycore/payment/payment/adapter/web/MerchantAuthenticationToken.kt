package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.MerchantAuthentication
import org.springframework.security.authentication.AbstractAuthenticationToken

/** Spring Security 인증 객체. principal = [MerchantAuthentication](MID·environment·keyId). 자격 증명(키 원문)은 담지 않는다. */
class MerchantAuthenticationToken(private val merchant: MerchantAuthentication) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): MerchantAuthentication = merchant

    override fun toString(): String = "MerchantAuthenticationToken(keyId=${merchant.credentialKeyId.value})"
}
