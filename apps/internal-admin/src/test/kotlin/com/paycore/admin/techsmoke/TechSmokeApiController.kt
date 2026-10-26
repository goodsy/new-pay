package com.paycore.admin.techsmoke

import com.paycore.admin.account.application.provided.AdminRetryLaterException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SPA/JSON 보안 조합 smoke 전용 API(테스트 소스에만 존재, 업무 의미 없음 — ADMIN-BOOT-001 §3.3 F-3: Thymeleaf 화면 smoke 대체).
 * POST는 아무 상태도 바꾸지 않는다. [BOOM]·[RETRY]는 오류 비노출 시험(`ErrorNonDisclosureSpec`)의 대역 — 예외 메시지에 시험 비밀을 넣어 응답에 새지 않는지 본다.
 */
@RestController
class TechSmokeApiController {

    @GetMapping(PATH)
    fun read(): Map<String, Boolean> = mapOf("ok" to true)

    @PostMapping(PATH)
    fun write(): Map<String, Boolean> = mapOf("ok" to true)

    @GetMapping(BOOM)
    fun boom(): Map<String, Boolean> = throw IllegalStateException("unexpected failure $SECRET_DETAIL")

    @GetMapping(RETRY)
    fun retry(): Map<String, Boolean> = throw AdminRetryLaterException(RuntimeException("deadlock $SECRET_DETAIL"))

    @GetMapping(TYPED)
    fun typed(@RequestParam n: Int): Map<String, Int> = mapOf("n" to n)

    @PostMapping(JSON)
    fun json(@RequestBody body: Map<String, String>): Map<String, Int> = mapOf("size" to body.size)

    @GetMapping(DENIED)
    fun denied(): Map<String, Boolean> = throw AccessDeniedException("denied $SECRET_DETAIL")

    companion object {
        const val TYPED = "/api/tech-smoke/typed"
        const val JSON = "/api/tech-smoke/json"
        const val DENIED = "/api/tech-smoke/denied"
        const val PATH = "/api/tech-smoke"
        const val BOOM = "/api/tech-smoke/boom"
        const val RETRY = "/api/tech-smoke/retry"
        const val SECRET_DETAIL = "test-secret-7f3c"
    }
}
