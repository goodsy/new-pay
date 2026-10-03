package com.paycore.admin.techsmoke

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Thymeleaf + Spring Security 기술 조합 smoke. 인증 방식은 아직 정하지 않았으므로 인증 성공 경로는 테스트용 mock user로만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TechSmokePageSecuritySpec(private val mockMvc: MockMvc) : FunSpec({

    val path = TechSmokePageController.PATH
    val csrfHiddenInput = Regex("""<input type="hidden" name="_csrf" value="[^"]+"""")

    test("unauthenticated GET is denied (401 or 403, never 200)") {
        val status = mockMvc.perform(get(path)).andReturn().response.status
        status shouldBeIn listOf(401, 403)
    }

    test("authenticated GET renders the Thymeleaf page with a CSRF hidden input") {
        val response = mockMvc.perform(get(path).with(user("tech-smoke-viewer"))).andReturn().response

        response.status shouldBe 200
        val html = response.contentAsString
        html shouldContain """id="tech-smoke-form""""
        html shouldContain """action="/tech-smoke""""
        csrfHiddenInput.containsMatchIn(html) shouldBe true
    }

    test("authenticated POST without CSRF token is rejected with 403") {
        mockMvc.perform(post(path).with(user("tech-smoke-viewer"))).andReturn().response.status shouldBe 403
    }

    test("authenticated POST with CSRF token is accepted (redirect)") {
        val response = mockMvc.perform(post(path).with(user("tech-smoke-viewer")).with(csrf())).andReturn().response
        response.status shouldBe 302
        response.redirectedUrl shouldBe path
    }

    test("unauthenticated POST is denied even with a CSRF token") {
        val status = mockMvc.perform(post(path).with(csrf())).andReturn().response.status
        status shouldBeIn listOf(401, 403)
    }
})
