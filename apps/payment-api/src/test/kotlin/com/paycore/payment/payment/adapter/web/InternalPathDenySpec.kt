package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.context.WebApplicationContext

/**
 * 기본 체인의 `/internal` 아래(ADMIN-PAYMENT-OPS-001 §4.2 M-3, I-02): Entitlement Inbox 정확 경로만 permitAll(컨트롤러 자체 HMAC 유지), 그 밖 `/internal` 아래 전부는 **denyAll** —
 * 인증 여부와 무관하게 거부(`authenticated()`였다면 인증된 주체는 통과한다). ADMIN 채널(`/internal/v1/admin` 아래)은 별도 체인.
 *
 * 실서버(내부 포트)에서는 Bearer 필터가 내부 경로를 검사하지 않으므로 유효한 가맹점 키로도 익명 → 거부다. denyAll ↔ authenticated() 차이(핵심 변이)는 **이미 인증된 주체**로만
 * 드러나므로 실제 보안 필터 체인(springSecurityFilterChain)을 MockMvc에 붙이고 인증 주체를 넣어 403을 확인한다(경로 격리 필터는 MockMvc에 없음 — 실서버 부분이 본다).
 */
@PaymentApiTest
class InternalPathDenySpec(
    private val internal: InternalConnector,
    private val db: DbAccess,
    private val context: WebApplicationContext,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val s = ApiSpecSupport(db, payments, controls, transactionManager)
    val internalApi = ApiClient(internal.localPort)
    val unknownInternal = listOf("/internal/x", "/internal/v1/x", "/internal/v1/adminx", "/internal/v1/entitlement-snapshots/x", "/internal")

    // checkpoint L-2: 실서버 내부 포트는 Bearer 필터가 내부 경로를 검사하지 않아 익명 → 정확히 401 + 기존 UnauthenticatedEntryPoint 본문(인증 주체가 있으면 403 — MockMvc 부분).
    fun rejected(r: ApiClient.Response, path: String) = withClue("$path -> ${r.status}") {
        r.status shouldBe 401
        s.json(r.body).get("error").get("code").stringValue() shouldBe "UNAUTHENTICATED"
        r.header("WWW-Authenticate") shouldBe "Bearer realm=\"paycore\""
    }

    Given("the internal connector over real HTTP") {
        Then("unknown /internal paths are rejected without authentication") {
            unknownInternal.forEach { p ->
                rejected(internalApi.get(p), p)
                rejected(internalApi.post(p, "{}"), p)
            }
        }
        Then("a valid merchant Bearer key does not open any other /internal path") {
            val cred = TestCredentials.issue(db)
            unknownInternal.forEach { p ->
                rejected(internalApi.get(p, cred.key), p)
                rejected(internalApi.post(p, "{}", cred.key, s.newKey()), p)
            }
        }
        Then("the exact Entitlement Inbox path still reaches its controller, which judges its own HMAC (unsigned -> 401 SIGNATURE_INVALID)") {
            val r = internalApi.post("/internal/v1/entitlement-snapshots", "{}")
            r.status shouldBe 401
            r.body shouldBe """{"error":"SIGNATURE_INVALID"}"""
        }
    }

    Given("the real security filter chain with an already authenticated principal (mutation: denyAll -> authenticated())") {
        val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        val principal = UsernamePasswordAuthenticationToken.authenticated("merchant", null, emptyList())

        fun status(method: String, path: String) = mvc.perform(
            MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).servletPath(path).with(authentication(principal)),
        ).andReturn().response.status

        Then("other /internal paths are 403 even for an authenticated principal") {
            unknownInternal.forEach { p ->
                withClue(p) {
                    status("GET", p) shouldBe 403
                    status("POST", p) shouldBe 403
                }
            }
        }
        Then("the same principal is not denied on a non-internal path (the principal injection itself works)") {
            withClue("authenticated /v1 route reaches MVC") { (status("GET", "/v1/nothing") == 404) shouldBe true }
        }
    }
})
