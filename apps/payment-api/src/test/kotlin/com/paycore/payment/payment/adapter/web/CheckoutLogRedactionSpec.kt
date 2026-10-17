package com.paycore.payment.payment.adapter.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.ScriptedCardAuthenticationPort
import com.paycore.payment.payment.domain.ProviderOutcome
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제창 로그 비노출(AUTHN-001 §12.6, AC 6): 전체 흐름(발급 → 교환 → 화면 → 인증 시작 → 실패 복귀 → 재시도 → 확인 불가 → 성공 복귀 → 중복 복귀 → 다른 결제창 취소)을 실행하며
 * 로그를 모두 모아(PayCore 로거는 DEBUG까지) 비밀 원문 — 토큰, 쿠키 비밀, CSRF 토큰, state, 결과 참조, 기관 evidenceRef — 과 `authenticationRef` 전체 값이 0회 나타남을 본다.
 * 운영 로깅 설정 그대로(다른 로거 수준은 바꾸지 않는다).
 */
@PaymentApiTest
class CheckoutLogRedactionSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val checkoutConnector: CheckoutConnector,
    private val institution: ScriptedCardAuthenticationPort,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    Given("the whole checkout flow with logs captured") {
        Then("no secret value and no full authenticationRef appears in any log line") {
            val paycore = LoggerFactory.getLogger("com.paycore") as Logger
            val previous = paycore.level
            paycore.level = Level.DEBUG
            val browser = CheckoutBrowser(api, CheckoutClient(checkoutConnector.localPort), s, db, institution)
            val (lines, authenticationRef) = try {
                CapturedLogs().use { logs ->
                    val o = browser.opened()
                    browser.start(o).status shouldBe 303
                    browser.web.get(browser.returnPath(o, ProviderOutcome.FAILED), o.cookie).status shouldBe 200
                    browser.start(o).status shouldBe 303
                    browser.web.get(browser.returnPath(o, outcome = null), o.cookie).status shouldBe 503
                    browser.start(o).status shouldBe 303
                    val path = browser.returnPath(o)
                    val verified = browser.web.get(path, o.cookie)
                    verified.status shouldBe 303
                    browser.web.get(path, o.cookie).status shouldBe 303
                    val other = browser.opened()
                    browser.cancel(other).status shouldBe 303
                    logs.lines() to verified.header("Location")!!.substringAfter("authenticationRef=")
                }
            } finally {
                paycore.level = previous
            }
            authenticationRef shouldStartWith "aref_"
            lines.isNotEmpty() shouldBe true
            (browser.secrets + authenticationRef).forEach { secret ->
                withClue(secret.take(6) + "…") { lines.count { it.contains(secret) } shouldBe 0 }
            }
        }
    }
})
