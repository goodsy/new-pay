package com.paycore.simulator.authentication.adapter.web

import com.paycore.simulator.authentication.application.provided.CustomerAuthenticationUseCase
import com.paycore.simulator.authentication.domain.SimulatorRejection
import com.paycore.simulator.authentication.domain.TransactionId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView

/**
 * 고객 인증 화면(AUTHN-001 §13.4 — Thymeleaf, 인증 없음: 거래 ID가 128bit 난수). 가상 카드 참조만 고른다(카드번호 입력 없음). 완료·취소는 PG 반환 URL로 303.
 * 반환 URL은 등록 때 client origin + 고정 경로로 검증한 값뿐이다(요청 파라미터로 redirect 대상을 받지 않는다). 화면은 `th:text`만 쓴다.
 */
@Controller
class CustomerAuthenticationController(private val customers: CustomerAuthenticationUseCase) {

    @GetMapping("/sim/authenticate/{id}")
    fun view(@PathVariable id: String, model: Model, response: HttpServletResponse): String {
        val tx = transactionIdOf(id) ?: return notFound(response)
        return try {
            model.addAttribute("view", customers.view(tx))
            "simulator/authenticate"
        } catch (e: SimulatorRejection) {
            notFound(response)
        }
    }

    @PostMapping("/sim/authenticate/{id}")
    fun submit(
        @PathVariable id: String,
        @RequestParam action: String,
        @RequestParam(required = false) card: String?,
        response: HttpServletResponse,
    ): Any {
        val tx = transactionIdOf(id) ?: return notFound(response)
        return try {
            val target = when (action) {
                "authenticate" -> customers.authenticate(tx, card ?: "")
                "cancel" -> customers.cancel(tx)
                else -> return notFound(response)
            }
            RedirectView(target).also { it.setStatusCode(org.springframework.http.HttpStatus.SEE_OTHER) }
        } catch (e: SimulatorRejection) {
            response.status = HttpServletResponse.SC_CONFLICT
            "simulator/unavailable"
        }
    }

    private fun transactionIdOf(raw: String): TransactionId? = runCatching { TransactionId(raw) }.getOrNull()

    private fun notFound(response: HttpServletResponse): String {
        response.status = HttpServletResponse.SC_NOT_FOUND
        return "simulator/unavailable"
    }
}
