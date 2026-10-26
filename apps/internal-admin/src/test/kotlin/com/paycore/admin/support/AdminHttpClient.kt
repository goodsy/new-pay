package com.paycore.admin.support

import jakarta.servlet.http.Cookie
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant

/**
 * 브라우저 한 개를 흉내 내는 MockMvc 클라이언트(시험 전용): 응답의 `Set-Cookie`를 보관해 다음 요청에 보내고(`Max-Age=0`이면 지움), 상태 변경 요청에는
 * XSRF 쿠키 값을 `X-XSRF-TOKEN` 헤더로 붙인다(SPA API client와 같은 동작). 원격 주소는 [remoteAddr].
 */
class AdminHttpClient(private val mockMvc: MockMvc, var remoteAddr: String = "127.0.0.1") {
    val cookies = linkedMapOf<String, String>()

    val sessionCookie: String? get() = cookies[SESSION_COOKIE]
    val xsrfToken: String? get() = cookies[XSRF_COOKIE]

    fun perform(builder: MockHttpServletRequestBuilder, xsrf: Boolean = true): MockHttpServletResponse {
        if (cookies.isNotEmpty()) builder.cookie(*cookies.map { (n, v) -> Cookie(n, v) }.toTypedArray())
        if (xsrf) xsrfToken?.let { builder.header(XSRF_HEADER, it) }
        builder.with { it.remoteAddr = remoteAddr; it }
        val response = mockMvc.perform(builder).andReturn().response
        response.getHeaders("Set-Cookie").forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=')
            if (header.contains("Max-Age=0") || value.isEmpty()) cookies.remove(name) else cookies[name] = value
        }
        return response
    }

    fun csrf(): MockHttpServletResponse = perform(get("/api/auth/csrf"))

    fun password(loginName: String, password: String): MockHttpServletResponse =
        perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content(json("loginName" to loginName, "password" to password)))

    fun totp(code: String): MockHttpServletResponse =
        perform(post("/api/auth/totp").contentType(MediaType.APPLICATION_JSON).content(json("code" to code)))

    /** csrf → 비밀번호 → TOTP(시험 쪽 독립 계산, [at] 시점 단계). 마지막 응답을 돌려준다. */
    fun login(loginName: String, password: String, secret: ByteArray, at: Instant = Instant.now()): MockHttpServletResponse {
        csrf()
        check(password(loginName, password).status == 200) { "password step failed" }
        return totp(TestTotp.code(secret, at))
    }

    companion object {
        const val SESSION_COOKIE = "__Host-PCADMIN"
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
        const val XSRF_HEADER = "X-XSRF-TOKEN"

        fun json(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    }
}
