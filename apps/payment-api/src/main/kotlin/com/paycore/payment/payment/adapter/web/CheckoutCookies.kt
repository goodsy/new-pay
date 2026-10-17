package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseCookie
import java.time.Duration

/**
 * 결제창 쿠키 규칙(AUTHN-001 §12.2, 판정 m-1·m-D): 이름 `__Host-pc_checkout_<handle>`(handle별 — 동시 결제창이 섞이지 않는다), 값 256bit base64url, Secure·HttpOnly·
 * SameSite=Lax·Path=/·Domain 없음(`__Host-` 접두를 브라우저가 강제), Max-Age = 결제창 세션 남은 초. 삭제는 같은 이름 `Max-Age=0`.
 * handle은 컨테이너가 정규화한 경로(servletPath)에서만 읽는다 — 형식(22자 base64url)이 아니면 쿠키를 보지 않는다.
 */
object CheckoutCookies {
    private const val PREFIX = "__Host-pc_checkout_"
    private val HANDLE = Regex("^[A-Za-z0-9_-]{22}$")
    private val SECRET = Regex("^[A-Za-z0-9_-]{43}$")
    private val HANDLE_PATH = Regex("^/checkout/s/([^/]+)(/.*)?$")

    fun isHandle(value: String): Boolean = HANDLE.matches(value)

    /** `/checkout/s/{handle}/…` 경로의 handle(형식이 맞을 때만). */
    fun handleOf(request: HttpServletRequest): String? {
        val path = request.servletPath + (request.pathInfo ?: "")
        return HANDLE_PATH.find(path)?.groupValues?.get(1)?.takeIf(::isHandle)
    }

    /** 그 handle 쿠키의 비밀(같은 이름이 둘 이상이면 신뢰하지 않는다). */
    fun secretOf(request: HttpServletRequest, handle: String): String? {
        val values = request.cookies.orEmpty().filter { it.name == PREFIX + handle }.map { it.value }
        return values.singleOrNull()?.takeIf { SECRET.matches(it) }
    }

    fun issue(handle: String, secret: String, maxAgeSeconds: Long): String =
        ResponseCookie.from(PREFIX + handle, secret).path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(Duration.ofSeconds(maxAgeSeconds)).build().toString()

    fun delete(handle: String): String =
        ResponseCookie.from(PREFIX + handle, "").path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(Duration.ZERO).build().toString()
}
