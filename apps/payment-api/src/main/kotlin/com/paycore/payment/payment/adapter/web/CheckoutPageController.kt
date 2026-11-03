package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CancelCheckoutCommand
import com.paycore.payment.payment.application.provided.CancelCheckoutResult
import com.paycore.payment.payment.application.provided.CancelCheckoutUseCase
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CheckoutPageQuery
import com.paycore.payment.payment.application.provided.CheckoutPageRequest
import com.paycore.payment.payment.application.provided.CheckoutPageResult
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationResult
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationUseCase
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionCommand
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionResult
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.application.provided.StartCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.StartCardAuthenticationResult
import com.paycore.payment.payment.application.provided.StartCardAuthenticationUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * 결제창 Controller(checkout 포트, AUTHN-001 §6.1~§6.3, D-AUTHN-006 — provided Use Case만). 화면 모델은 application DTO만(Entity 없음, OSIV off).
 * 화면은 React shell([CheckoutShell], CHECKOUT-REACT-001 — D-FE-001): 판정·상태 코드·헤더·redirect는 그대로이고 HTML만 shell + 화면 데이터로 바뀌었다.
 * - `GET /checkout?token=…`: 쿼리는 `token` 하나뿐. 교환 성공 → 303 `/checkout/s/{handle}`(상대 — 요청 Host로 만들지 않는다 m-9, handle은 경로 encode — m-3) + handle 쿠키.
 * - `GET /checkout/s/{handle}`: 쿼리 없음, handle 쿠키로 화면.
 * - `POST /checkout/s/{handle}/authentication`(CSRF — 보안 체인): 폼 닫힌 집합 `cardCompany`·`plan`·`_csrf`. 303 Simulator 고객 URL.
 * - `GET /checkout/s/{handle}/authentication/return?resultRef=…&state=…`: 303 successUrl / 200 실패 화면 / 503 확인 중 / 410 / 400.
 * - `POST /checkout/s/{handle}/cancel`(CSRF): 303 failUrl(code=USER_CANCELLED).
 * 쿠키: VERIFIED 뒤에는 지우지 않는다(같은 복귀 URL 재요청이 DUPLICATE 303 — m-D), REVOKED·EXPIRED·취소·PAYMENT_NOT_AVAILABLE·결제창 시각 경과는 `Max-Age=0`.
 * 로그·지표에는 토큰·쿠키·state·결과 참조 원문을 남기지 않는다(§12.6).
 */
@Controller
class CheckoutPageController(
    private val open: OpenCheckoutSessionUseCase,
    private val pages: CheckoutPageQuery,
    private val start: StartCardAuthenticationUseCase,
    private val complete: CompleteCardAuthenticationUseCase,
    private val cancel: CancelCheckoutUseCase,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
    private val shell: CheckoutShell,
) {

    @GetMapping("/checkout")
    fun exchange(request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val token = request.parameterMap.takeIf { it.keys == setOf(TOKEN_PARAM) }?.get(TOKEN_PARAM)?.singleOrNull()
            ?: return error(HttpStatus.BAD_REQUEST, CheckoutPages.CANNOT_OPEN, EXCHANGES_METRIC, "invalid_request")
        return when (val result = open.open(OpenCheckoutSessionCommand(token, ApiResponses.requestId(request)))) {
            is OpenCheckoutSessionResult.Opened -> {
                record(EXCHANGES_METRIC, "opened")
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.issue(result.handle, result.cookieSecret, result.maxAgeSeconds))
                seeOther(handlePath(result.handle), contextRelative = true)
            }
            OpenCheckoutSessionResult.InvalidToken -> error(HttpStatus.BAD_REQUEST, CheckoutPages.CANNOT_OPEN, EXCHANGES_METRIC, "invalid_token")
            is OpenCheckoutSessionResult.NotAvailable ->
                error(HttpStatus.CONFLICT, CheckoutPages.PAYMENT_NOT_AVAILABLE, EXCHANGES_METRIC, "not_available", failRedirect = result.failRedirect)
            OpenCheckoutSessionResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, EXCHANGES_METRIC, "unavailable")
        }
    }

    @GetMapping("/checkout/s/{handle}")
    fun page(@PathVariable("handle") handle: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (request.parameterMap.isNotEmpty() || !CheckoutCookies.isHandle(handle)) {
            return error(HttpStatus.BAD_REQUEST, CheckoutPages.SESSION_EXPIRED, PAGES_METRIC, "bad_request")
        }
        return render(handle, request, response, HttpStatus.OK, notice = null)
    }

    @PostMapping("/checkout/s/{handle}/authentication")
    fun startAuthentication(@PathVariable("handle") handle: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (!CheckoutCookies.isHandle(handle) || !closedForm(request, setOf("cardCompany", "plan", CheckoutCsrfTokenRepository.PARAMETER))) {
            return error(HttpStatus.BAD_REQUEST, CheckoutPages.REQUEST_REJECTED, STARTS_METRIC, "bad_request")
        }
        val command = StartCardAuthenticationCommand(
            handle, CheckoutCookies.secretOf(request, handle), request.getParameter("cardCompany"), request.getParameter("plan"), ApiResponses.requestId(request),
        )
        return when (val result = start.start(command)) {
            is StartCardAuthenticationResult.Redirect -> {
                record(STARTS_METRIC, "redirected")
                seeOther(result.customerUrl, contextRelative = false)
            }
            StartCardAuthenticationResult.NotAllowed -> {
                record(STARTS_METRIC, "not_allowed")
                render(handle, request, response, HttpStatus.UNPROCESSABLE_CONTENT, notice = CheckoutPages.NOT_ALLOWED)
            }
            StartCardAuthenticationResult.SessionExpired -> expired(handle, response, STARTS_METRIC)
            is StartCardAuthenticationResult.PaymentNotAvailable -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                error(HttpStatus.CONFLICT, CheckoutPages.PAYMENT_NOT_AVAILABLE, STARTS_METRIC, "not_available", failRedirect = result.failRedirect)
            }
            is StartCardAuthenticationResult.CheckoutTimeOver -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                error(HttpStatus.GONE, CheckoutPages.CHECKOUT_TIME_OVER, STARTS_METRIC, "time_over", failRedirect = result.failRedirect)
            }
            StartCardAuthenticationResult.Superseded -> error(HttpStatus.CONFLICT, CheckoutPages.OTHER_WINDOW, STARTS_METRIC, "superseded", retryUrl = handlePath(handle))
            StartCardAuthenticationResult.RegistrationFailed ->
                error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.REGISTRATION_FAILED, STARTS_METRIC, "registration_failed", retryUrl = handlePath(handle))
            StartCardAuthenticationResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, STARTS_METRIC, "unavailable")
        }
    }

    @GetMapping("/checkout/s/{handle}/authentication/return")
    fun completeAuthentication(@PathVariable("handle") handle: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (!CheckoutCookies.isHandle(handle)) return error(HttpStatus.BAD_REQUEST, CheckoutPages.RESULT_UNVERIFIED, RETURNS_METRIC, "bad_request")
        val parameters = request.parameterMap.mapValues { (_, v) -> v.toList() }
        val command = CompleteCardAuthenticationCommand(handle, CheckoutCookies.secretOf(request, handle), parameters, ApiResponses.requestId(request))
        return when (val result = complete.complete(command)) {
            is CompleteCardAuthenticationResult.Verified -> {
                record(RETURNS_METRIC, "verified")
                seeOther(result.successRedirect, contextRelative = false)
            }
            is CompleteCardAuthenticationResult.Failed ->
                error(HttpStatus.OK, CheckoutPages.AUTHENTICATION_INCOMPLETE, RETURNS_METRIC, "failed", failRedirect = result.failRedirect, retryUrl = handlePath(handle))
            CompleteCardAuthenticationResult.Unverifiable -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds())
                error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.RESULT_CHECKING, RETURNS_METRIC, "unverifiable", retryUrl = returnPath(handle, parameters))
            }
            CompleteCardAuthenticationResult.Ignored ->
                error(HttpStatus.GONE, CheckoutPages.AUTHENTICATION_TIME_OVER, RETURNS_METRIC, "ignored", retryUrl = handlePath(handle))
            is CompleteCardAuthenticationResult.CheckoutClosed -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                closed(result.code, result.failRedirect, RETURNS_METRIC)
            }
            CompleteCardAuthenticationResult.Rejected -> error(HttpStatus.BAD_REQUEST, CheckoutPages.RESULT_UNVERIFIED, RETURNS_METRIC, "rejected")
            CompleteCardAuthenticationResult.SessionExpired -> expired(handle, response, RETURNS_METRIC)
            CompleteCardAuthenticationResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, RETURNS_METRIC, "unavailable")
        }
    }

    @PostMapping("/checkout/s/{handle}/cancel")
    fun cancelCheckout(@PathVariable("handle") handle: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (!CheckoutCookies.isHandle(handle) || !closedForm(request, setOf(CheckoutCsrfTokenRepository.PARAMETER))) {
            return error(HttpStatus.BAD_REQUEST, CheckoutPages.REQUEST_REJECTED, CANCELS_METRIC, "bad_request")
        }
        return when (val result = cancel.cancel(CancelCheckoutCommand(handle, CheckoutCookies.secretOf(request, handle), ApiResponses.requestId(request)))) {
            is CancelCheckoutResult.Cancelled -> {
                record(CANCELS_METRIC, "cancelled")
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                seeOther(result.failRedirect, contextRelative = false)
            }
            CancelCheckoutResult.SessionExpired -> expired(handle, response, CANCELS_METRIC)
            is CancelCheckoutResult.PaymentNotAvailable -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                error(HttpStatus.CONFLICT, CheckoutPages.PAYMENT_NOT_AVAILABLE, CANCELS_METRIC, "not_available", failRedirect = result.failRedirect)
            }
            is CancelCheckoutResult.CheckoutTimeOver -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                error(HttpStatus.GONE, CheckoutPages.CHECKOUT_TIME_OVER, CANCELS_METRIC, "time_over", failRedirect = result.failRedirect)
            }
            CancelCheckoutResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, CANCELS_METRIC, "unavailable")
        }
    }

    private fun render(handle: String, request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, notice: String?): ModelAndView {
        val secret = CheckoutCookies.secretOf(request, handle)
        return when (val result = pages.page(CheckoutPageRequest(handle, secret, ApiResponses.requestId(request)))) {
            is CheckoutPageResult.Page -> {
                // CSRF 값은 보안 체인(CsrfFilter + CheckoutCsrfTokenRepository)이 이 요청에 둔 마스킹 값이다. 없으면 화면을 만들지 않는다(fail closed).
                val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
                    ?: return error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, PAGES_METRIC, "unavailable")
                record(PAGES_METRIC, "shown")
                // shell CSP(script-src 'self' + 그 세션의 form-action origin)는 View가 쓴다.
                ModelAndView(shell.page(result.view, notice, handlePath(handle) + "/authentication", handlePath(handle) + "/cancel", csrf)).also { it.status = status }
            }
            CheckoutPageResult.BadHandle -> error(HttpStatus.BAD_REQUEST, CheckoutPages.SESSION_EXPIRED, PAGES_METRIC, "bad_request")
            CheckoutPageResult.SessionExpired -> expired(handle, response, PAGES_METRIC)
            is CheckoutPageResult.NotAvailable -> {
                response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
                closed(result.code, result.failRedirect, PAGES_METRIC)
            }
            CheckoutPageResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, CheckoutPages.TEMPORARY_ERROR, PAGES_METRIC, "unavailable")
        }
    }

    /** 닫힌 결제창 안내(§6.3): 결제창 시각 경과 410 "결제 시간이 지났습니다.", 결제 불가 409 "결제를 진행할 수 없습니다." — 둘 다 failUrl 링크. */
    private fun closed(code: CheckoutFailCode, failRedirect: String, metric: String): ModelAndView =
        if (code == CheckoutFailCode.CHECKOUT_EXPIRED) {
            error(HttpStatus.GONE, CheckoutPages.CHECKOUT_TIME_OVER, metric, "checkout_expired", failRedirect = failRedirect)
        } else {
            error(HttpStatus.CONFLICT, CheckoutPages.PAYMENT_NOT_AVAILABLE, metric, "not_available", failRedirect = failRedirect)
        }

    private fun expired(handle: String, response: HttpServletResponse, metric: String): ModelAndView {
        response.addHeader(HttpHeaders.SET_COOKIE, CheckoutCookies.delete(handle))
        return error(HttpStatus.UNAUTHORIZED, CheckoutPages.SESSION_EXPIRED, metric, "session_expired")
    }

    private fun error(status: HttpStatus, message: String, metric: String, label: String, failRedirect: String? = null, retryUrl: String? = null): ModelAndView {
        record(metric, label)
        return ModelAndView(shell.error(message, retryUrl, failRedirect, pages.defaultFormActionOrigins())).also { it.status = status }
    }

    /** 폼(POST 본문) 이름은 닫힌 집합 — 모르는 이름·같은 이름 반복은 400(§6.1). 쿼리 문자열은 받지 않는다. */
    private fun closedForm(request: HttpServletRequest, allowed: Set<String>): Boolean =
        request.queryString == null && request.parameterMap.all { (name, values) -> name in allowed && values.size == 1 }

    private fun record(metric: String, result: String) {
        registry.counter(metric, "result", result).increment()
    }

    private companion object {
        const val TOKEN_PARAM = "token"
        const val EXCHANGES_METRIC = "paycore.checkout.exchanges"
        const val PAGES_METRIC = "paycore.checkout.pages"
        const val STARTS_METRIC = "paycore.authentication.starts"
        const val RETURNS_METRIC = "paycore.authentication.returns"
        const val CANCELS_METRIC = "paycore.checkout.cancels"

        fun handlePath(handle: String): String = "/checkout/s/" + UriUtils.encodePathSegment(handle, StandardCharsets.UTF_8)

        /** 확인 중 화면의 재시도 링크 = 같은 복귀 URL(검증된 이름만 다시 encode — 원문 query를 echo하지 않는다). */
        fun returnPath(handle: String, parameters: Map<String, List<String>>): String =
            handlePath(handle) + "/authentication/return?" + listOf("resultRef", "state").joinToString("&") { name ->
                name + "=" + UriUtils.encodeQueryParam(parameters[name]?.singleOrNull().orEmpty(), StandardCharsets.UTF_8)
            }

        /** 303(HTTP/1.1 — sendRedirect의 302·절대 URL화를 쓰지 않는다). 내부 경로는 상대 Location, 외부(가맹점·Simulator)는 저장·설정된 절대 URL. */
        fun seeOther(target: String, contextRelative: Boolean): ModelAndView = ModelAndView(
            RedirectView(target, contextRelative).apply {
                setHttp10Compatible(false)
                setStatusCode(HttpStatus.SEE_OTHER)
                setExposeModelAttributes(false)
                // 검토 #3 n-2: 저장·설정된 URL을 그대로 쓴다 — `{…}`를 URI 템플릿으로 확장하지 않는다.
                setExpandUriTemplateVariables(false)
            },
        )
    }
}
