package com.paycore.admin.security

import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.techsmoke.TechSmokeApiController
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 오류 비노출(§4.10 — `ErrorNonDisclosureSpec`): 404·500·409 본문은 고정 JSON code만(경로·메시지·stack·예외 이름 없음), `no-store`.
 * 409 `RETRY_LATER`는 1213·1205·version 충돌의 웹 매핑(§4.13). 시험 대역 엔드포인트는 테스트 소스의 TechSmokeApiController.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
class ErrorNonDisclosureSpec(private val mockMvc: MockMvc) : BehaviorSpec({

    Given("errors behind authentication") {
        Then("unknown API path → 404, unexpected exception → 500, retry-later → 409; bodies are fixed codes only") {
            val responses = listOf("/api/no-such-endpoint", TechSmokeApiController.BOOM, TechSmokeApiController.RETRY).map { path ->
                mockMvc.perform(get(path).with(user("smoke-viewer"))).andReturn().response
            }
            responses.map { it.status to it.contentAsString } shouldBe listOf(
                404 to """{"error":"NOT_FOUND"}""", 500 to """{"error":"INTERNAL_ERROR"}""", 409 to """{"error":"RETRY_LATER"}""",
            )
            responses.forEach {
                it.getHeader("Cache-Control") shouldBe "no-store"
                it.contentAsString shouldNotContain TechSmokeApiController.SECRET_DETAIL
                it.contentAsString shouldNotContain "Exception"
            }
        }
        Then("malformed input → 400 BAD_REQUEST (type mismatch, unreadable body); access denied inside a controller stays 403 FORBIDDEN (S4 review m-2)") {
            listOf(
                mockMvc.perform(get(TechSmokeApiController.TYPED).param("n", "not-a-number").with(user("smoke-viewer"))).andReturn().response,
                // csrf() 시험 post-processor는 공유 CsrfFilter의 저장소를 바꿔(객체 저장) 같은 컨텍스트의 다른 시험을 깨뜨린다 — 실제 토큰 흐름을 쓴다.
                AdminHttpClient(mockMvc).also { it.csrf() }
                    .perform(post(TechSmokeApiController.JSON).contentType("application/json").content("{not json").with(user("smoke-viewer"))),
                mockMvc.perform(get(TechSmokeApiController.DENIED).with(user("smoke-viewer"))).andReturn().response,
            ).map { it.status to it.contentAsString } shouldBe listOf(
                400 to """{"error":"BAD_REQUEST"}""", 400 to """{"error":"BAD_REQUEST"}""", 403 to """{"error":"FORBIDDEN"}""",
            )
        }
    }
})
