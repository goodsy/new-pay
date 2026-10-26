package com.paycore.admin.security

import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.techsmoke.TechSmokeApiController
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.context.ApplicationContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * SPA/JSON CSRF(D-10, ADMIN-BOOT-001 §3.3 F-3 — TECH-001 Thymeleaf smoke의 보안 주장을 SPA/JSON으로 대체).
 *
 * 토큰은 **세션에 저장**하고(synchronizer) `__Host-PCADMIN-XSRF` 쿠키(HttpOnly 아님)로 전달만 한다. 상태 변경 요청은 `X-XSRF-TOKEN`
 * 헤더 값이 세션 토큰과 같아야 한다 — 쿠키와 헤더가 서로 같기만 한 double-submit 위조는 거부된다.
 * 인증 방식은 S4(2단계 로그인)에서 붙는다. 여기서는 인증 성공 경로를 시험용 mock user로만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
class SpaCsrfSpec(private val mockMvc: MockMvc, private val context: ApplicationContext) : FunSpec({

    test("actuator is present but never served on the web port: anonymous 401, authenticated 403 (S1 m3 — management port is D-15)") {
        // ADMIN-BOOT-001 S2: actuator 의존성 추가. 웹 포트에서는 SpaDocumentRequestMatcher 허용 목록 밖·/api 밖이라 인증 여부와 무관하게 거부된다.
        context.getBeanNamesForType(HealthEndpoint::class.java).toList().size shouldBe 1
        val anonymous = mockMvc.perform(get("/actuator/health")).andReturn().response
        anonymous.status shouldBe 401
        anonymous.contentAsString shouldBe """{"error":"UNAUTHENTICATED"}"""
        mockMvc.perform(get("/actuator/health").with(user("smoke-viewer"))).andReturn().response.status shouldBe 403
    }

    val path = TechSmokeApiController.PATH

    fun xsrfCookie(response: MockHttpServletResponse): String? =
        response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("$XSRF_COOKIE=") }

    fun tokenOf(setCookie: String): String = setCookie.substringAfter("$XSRF_COOKIE=").substringBefore(';')

    test("unauthenticated GET /api is 401 with a JSON error code") {
        val response = mockMvc.perform(get(path)).andReturn().response
        response.status shouldBe 401
        response.contentAsString shouldBe """{"error":"UNAUTHENTICATED"}"""
        xsrfCookie(response) shouldBe null
    }

    test("authenticated GET is 200 and delivers the session token in a readable __Host- cookie") {
        val response = mockMvc.perform(get(path).with(user("smoke-viewer"))).andReturn().response
        response.status shouldBe 200
        val cookie = xsrfCookie(response).shouldNotBeNull()
        tokenOf(cookie).length shouldNotBe 0
        cookie shouldContain "Path=/"
        cookie shouldContain "Secure"
        cookie shouldContain "SameSite=Strict"
        cookie shouldNotContain "HttpOnly"
        cookie shouldNotContain "Domain="
    }

    test("authenticated POST without the X-XSRF-TOKEN header is rejected with 403") {
        val response = mockMvc.perform(post(path).with(user("smoke-viewer"))).andReturn().response
        response.status shouldBe 403
        response.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
    }

    // S4: 세션은 Spring Session JDBC(쿠키 `__Host-PCADMIN`) — MockHttpSession 대신 쿠키를 이어 보내는 AdminHttpClient로 세션을 유지한다.
    // 세션은 로그인 전 토큰 발급 경로(GET /api/auth/csrf)가 명시적으로 만든다.
    test("authenticated POST with the session token in X-XSRF-TOKEN is accepted (200)") {
        val c = AdminHttpClient(mockMvc)
        c.csrf()
        val first = c.perform(get(path).with(user("smoke-viewer")))
        val token = tokenOf(xsrfCookie(first).shouldNotBeNull())
        token shouldBe c.xsrfToken
        c.perform(post(path).with(user("smoke-viewer"))).status shouldBe 200
    }

    test("forged double-submit (cookie == header but not the session token) is rejected with 403") {
        val c = AdminHttpClient(mockMvc)
        c.csrf()
        val forged = "attacker-chosen-value"
        c.cookies[XSRF_COOKIE] = forged
        c.perform(post(path).with(user("smoke-viewer"))).status shouldBe 403
    }

    test("a token from another session is rejected with 403") {
        val other = AdminHttpClient(mockMvc)
        other.csrf()
        val stolen = other.xsrfToken!!
        val c = AdminHttpClient(mockMvc)
        c.csrf()
        c.perform(post(path).with(user("smoke-viewer")).header(XSRF_HEADER, stolen), xsrf = false).status shouldBe 403
    }

    test("unauthenticated POST is denied even with a header (403 — CSRF runs before authorization) and creates no session") {
        val result = mockMvc.perform(post(path).header(XSRF_HEADER, "x")).andReturn()
        result.response.status shouldBe 403
        result.request.getSession(false) shouldBe null
        result.response.getHeaders("Set-Cookie").shouldBeEmpty()
    }

    test("anonymous POST without any token creates no session") {
        // S1 리뷰 M1: HttpSessionCsrfTokenRepository.saveToken은 세션을 만든다 — 세션이 없으면 저장하지 않아야 한다.
        val result = mockMvc.perform(post(path)).andReturn()
        result.response.status shouldBe 403
        result.request.getSession(false) shouldBe null
    }
}) {
    private companion object {
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
        const val XSRF_HEADER = "X-XSRF-TOKEN"
    }
}
