package com.paycore.admin.console.adapter.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 서블릿 컨테이너 오류 경로(`/error` — MVC 밖에서 난 404·500 등)의 JSON 응답. Boot의 BasicErrorController(Whitelabel·오류 속성)를 대체한다:
 * 본문은 상태별 고정 code만(경로·메시지·예외 없음 — §4.10).
 */
@RestController
class JsonErrorController : ErrorController {

    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<String> =
        JsonErrors.response((request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int) ?: 500)
}
