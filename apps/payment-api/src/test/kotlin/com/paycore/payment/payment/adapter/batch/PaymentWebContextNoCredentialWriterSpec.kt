package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.application.DemoCredentialIssueService
import com.paycore.payment.payment.application.provided.DemoCredentialIssueUseCase
import com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore
import com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.context.ApplicationContext
import org.springframework.web.context.WebApplicationContext

/**
 * DEMO-SEED-001 I-23: 명령 속성이 없는 **웹** 컨텍스트(일반 PaymentDbTest — MockMvc 서블릿)에는 `merchant_api_credential`·`demo_credential_issuance`에 쓰는 빈이
 * 없다 — 두 쓰기 adapter·발급 서비스·키 생성기·명령 runner·명령 가드 모두. 웹 프로세스에서 INSERT 권한(D-DEMO-002)을 쓸 코드 경로가 없다는 코드 측 완화의 증거다.
 */
@PaymentDbTest
class PaymentWebContextNoCredentialWriterSpec(private val context: ApplicationContext) : FunSpec({

    test("the context is a web application context without the command property") {
        (context is WebApplicationContext) shouldBe true
        context.environment.containsProperty("paycore.payment.command") shouldBe false
    }

    test("the servlet web context has exactly the three security filter chains (merchant API, checkout, admin channel — @ConditionalOnWebApplication SERVLET)") {
        context.getBeansOfType(org.springframework.security.web.SecurityFilterChain::class.java).keys shouldBe
            setOf("paymentApiSecurityFilterChain", "checkoutSecurityFilterChain", "adminChannelSecurityFilterChain")
    }

    test("no Demo credential writer, issuer, key source, runner or command guard bean exists") {
        listOf(
            DemoCredentialProjectionWriter::class.java, DemoCredentialIssuanceStore::class.java, DemoCredentialIssueUseCase::class.java,
            DemoCredentialIssueService::class.java, DemoCredentialIssueService.KeySource::class.java, DemoCredentialCommandRunner::class.java,
            DemoCredentialCommandLine::class.java, OneShotModeGuard::class.java, ConsoleAccess::class.java,
        ).forEach { type -> context.getBeanNamesForType(type).toList().shouldBeEmpty() }
        context.beanDefinitionNames.filter { it.contains("demoCredential", ignoreCase = true) }.shouldBeEmpty()
    }
})
