package com.paycore.admin.techsmoke

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping

/**
 * TECH-001 Thymeleaf/Security smoke 전용 화면(테스트 소스에만 존재, 업무 의미 없음).
 * POST는 아무 상태도 바꾸지 않고 같은 화면으로 돌아간다(PRG).
 */
@Controller
class TechSmokePageController {

    @GetMapping(PATH)
    fun page(): String = "techsmoke/page"

    @PostMapping(PATH)
    fun submit(): String = "redirect:$PATH"

    companion object {
        const val PATH = "/tech-smoke"
    }
}
