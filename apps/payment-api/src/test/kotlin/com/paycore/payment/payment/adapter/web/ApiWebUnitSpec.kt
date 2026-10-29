package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.MerchantAuthenticator
import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.provided.MerchantAuthenticationUnavailable
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 웹 어댑터 단위 시험(서블릿 mock, DB 없음): Bearer 인증 필터의 scheme 해석(review round1 n-2)·검증 불가/결함 분류(n-7), 가맹점 `X-Request-Id` 정제
 * (m-3, 명세 §4.1). HTTP 수준의 같은 경로는 ApiSecuritySpec·CreatePaymentApiSpec이 실서버로 본다.
 */
class ApiWebUnitSpec : BehaviorSpec({

    val settings = PaymentApiSettings(
        internalPort = 0,
        retryAfter = PaymentApiSettings.RetryAfter(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(1)),
        internalMaxThreads = 16,
        internalAcceptCount = 16,
    )
    val mapper = JsonMapper.builder().build()

    afterEach { SecurityContextHolder.clearContext() }

    /** [authenticate]로 필터를 한 번 실행한다. 결과: 응답, 다음 필터로 넘어갔는지. */
    fun runFilter(authenticate: MerchantAuthenticator, vararg authorization: String): Pair<MockHttpServletResponse, Boolean> {
        val request = MockHttpServletRequest("POST", "/v1/payments").apply {
            servletPath = "/v1/payments"
            authorization.forEach { addHeader("Authorization", it) }
        }
        val response = MockHttpServletResponse()
        var passed = false
        MerchantBearerAuthenticationFilter(authenticate, settings).doFilter(request, response, FilterChain { _, _ -> passed = true })
        return response to passed
    }

    fun auth(block: (String?) -> MerchantAuthenticationResult): MerchantAuthenticator = object : MerchantAuthenticator {
        override fun authenticate(presentedKey: String?): MerchantAuthenticationResult = block(presentedKey)
    }

    fun code(response: MockHttpServletResponse): String =mapper.readTree(response.contentAsByteArray).get("error").get("code").stringValue()

    Given("the Authorization header scheme (RFC 7235 §2.1: the scheme name is case-insensitive, review round1 n-2)") {
        Then("Bearer, bearer and BEARER all pass the key unchanged; another scheme or two headers never reach the key check as that key") {
            val presented = CopyOnWriteArrayList<String?>()
            val recording = auth { key ->
                presented += key
                MerchantAuthenticationResult.Rejected(AuthenticationFailureReason.MALFORMED)
            }
            val key = "pc_live_sk_AbCdEf12.secretValue"
            listOf("Bearer $key", "bearer $key", "BEARER $key", "bEaReR $key").forEach { runFilter(recording, it).second shouldBe true }
            presented.toList() shouldBe List(4) { key }

            presented.clear()
            runFilter(recording, "Basic $key")
            runFilter(recording, "Bearer $key", "Bearer $key")
            runFilter(recording, "Bearer$key")
            runFilter(recording)
            presented.size shouldBe 4
            presented.take(3).forEach { (it == key) shouldBe false }
            presented[3] shouldBe null
        }
    }

    Given("the credential store cannot answer or the check itself is defective (review round1 n-7)") {
        Then("a store failure (deadlock, lock wait, connection) is 503 TEMPORARILY_UNAVAILABLE with Retry-After — never a false 401") {
            listOf<RuntimeException>(
                MerchantAuthenticationUnavailable(FailureKind.LOCK_TIMEOUT, StoreDiagnostic(1205, "40001"), RuntimeException("x")),
                MerchantAuthenticationUnavailable(FailureKind.DEADLOCK, null, RuntimeException("x")),
                MerchantAuthenticationUnavailable(FailureKind.DB_UNAVAILABLE, null, RuntimeException("x")),
                CannotCreateTransactionException("no connection"),
                TransactionSystemException("rollback failed after connection loss"),
            ).forEach { failure ->
                val (response, passed) = runFilter(auth { throw failure },"Bearer pc_live_sk_AbCdEf12.secretValue")
                passed shouldBe false
                response.status shouldBe 503
                code(response) shouldBe "TEMPORARILY_UNAVAILABLE"
                response.getHeader("Retry-After") shouldBe "3"
                response.getHeader("WWW-Authenticate") shouldBe null
            }
        }
        Then("any other exception is a defect: 500 INTERNAL_ERROR, no Retry-After, and the raw message is not logged") {
            val marker = "auth-defect-marker-7f3a"
            CapturedLogs().use { logs ->
                val (response, passed) = runFilter(auth { throw IllegalStateException("boom $marker") }, "Bearer pc_live_sk_AbCdEf12.secretValue")
                passed shouldBe false
                response.status shouldBe 500
                code(response) shouldBe "INTERNAL_ERROR"
                response.getHeader("Retry-After") shouldBe null
                response.contentAsString shouldNotContain marker
                logs.lines().any { "result=INTERNAL_ERROR" in it && "failure=DEFECT" in it && "IllegalStateException" in it } shouldBe true
                logs.lines().forEach { it shouldNotContain marker }
            }
        }
    }

    Given("the merchant X-Request-Id header (spec §4.1: kept for logs only when printable ASCII 1..128, otherwise dropped)") {
        Then("printable ASCII values up to 128 characters are kept; empty, longer, spaces, control and non-ASCII values are dropped") {
            CreatePaymentController.sanitizedMerchantRequestId("req-2026.10:14_A/b=c") shouldBe "req-2026.10:14_A/b=c"
            CreatePaymentController.sanitizedMerchantRequestId("r".repeat(128)) shouldBe "r".repeat(128)
            listOf(null, "", "r".repeat(129), "a b", "a\tb", "a\r\nmerchantRequestId=forged", "a\u0000", "a\u007F", "요청-1", "a b").forEach {
                CreatePaymentController.sanitizedMerchantRequestId(it) shouldBe null
            }
        }
    }
})
