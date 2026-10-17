package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.DefaultCsrfToken
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 결제창 CSRF 토큰 저장소(AUTHN-001 §12.3, AN-17): 토큰 = `base64url(SHA-256("paycore.checkout.csrf.v1" ‖ 0x00 ‖ 쿠키 비밀))` — 경로 handle의 쿠키
 * `__Host-pc_checkout_<handle>` 비밀에서 파생한 세션 결합 synchronizer 토큰. 저장하지 않는다(원문 비밀을 DB·세션에 두지 않음). 쿠키 없이는 계산할 수 없고, 토큰이 노출돼도
 * 쿠키 비밀을 역산할 수 없다. 쿠키가 없거나 handle이 형식에 맞지 않으면 [loadToken]은 null이고 [generateToken]은 어떤 요청과도 맞지 않는 무작위 값이다 → POST는 403.
 * 화면의 숨김 필드 마스킹(BREACH 대응)은 Spring Security 기본 `XorCsrfTokenRequestAttributeHandler`가 한다.
 */
class CheckoutCsrfTokenRepository : CsrfTokenRepository {
    private val random = SecureRandom()

    override fun generateToken(request: HttpServletRequest): CsrfToken =
        loadToken(request) ?: DefaultCsrfToken(HEADER, PARAMETER, Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)))

    override fun saveToken(token: CsrfToken?, request: HttpServletRequest, response: HttpServletResponse) = Unit

    override fun loadToken(request: HttpServletRequest): CsrfToken? {
        val handle = CheckoutCookies.handleOf(request) ?: return null
        val secret = CheckoutCookies.secretOf(request, handle) ?: return null
        return DefaultCsrfToken(HEADER, PARAMETER, derive(secret))
    }

    companion object {
        const val PARAMETER = "_csrf"
        private const val HEADER = "X-CSRF-TOKEN"
        private val LABEL = "paycore.checkout.csrf.v1".toByteArray(StandardCharsets.US_ASCII)

        fun derive(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(LABEL)
            digest.update(0)
            digest.update(secret.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
        }
    }
}
