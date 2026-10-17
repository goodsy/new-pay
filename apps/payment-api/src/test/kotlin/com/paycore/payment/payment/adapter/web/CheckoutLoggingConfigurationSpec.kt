package com.paycore.payment.payment.adapter.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.web.filter.AbstractRequestLoggingFilter

/**
 * 경로 query·본문이 로그에 남는 경로 차단(AUTHN-001 §12.6, 검토 #3 n-1) — 운영 설정 그대로의 컨텍스트에서:
 * - Tomcat access log 미사용(쓰게 되면 pattern에 요청 줄 `%r`·query `%q`가 없어야 한다 — 결제창 토큰·state·결과 참조가 query에 있다),
 * - 요청 로깅 필터(`AbstractRequestLoggingFilter` 계열) bean 없음,
 * - SQL·jOOQ·HTTP client·Spring web·Security 요청 로그 로거가 DEBUG/TRACE가 아님(바인딩 값·URL 원문 비노출), `spring.jpa.show-sql` 꺼짐,
 * - JDK HttpClient 디버그 로그(`jdk.httpclient.HttpClient.log`) 미설정.
 */
@PaymentApiTest
class CheckoutLoggingConfigurationSpec(
    private val env: SpringEnvironment,
    private val context: ApplicationContext,
) : BehaviorSpec({

    Given("the application's logging configuration") {
        Then("no access log with request lines or queries, no request logging filter, and no debug logging of SQL, HTTP clients or request URLs") {
            val accessLog = env.getProperty("server.tomcat.accesslog.enabled", Boolean::class.java, false)
            if (accessLog) {
                val pattern = env.getProperty("server.tomcat.accesslog.pattern", "common")
                withClue(pattern) { listOf("%r", "%q", "common", "combined").none { pattern.contains(it) } shouldBe true }
            }
            context.getBeansOfType(AbstractRequestLoggingFilter::class.java) shouldBe emptyMap()
            env.getProperty("spring.jpa.show-sql", Boolean::class.java, false) shouldBe false
            listOf(
                "org.hibernate.SQL", "org.hibernate.orm.jdbc.bind", "org.jooq", "org.springframework.web", "org.springframework.web.client",
                "org.springframework.security", "org.apache.catalina", "org.apache.coyote", "com.paycore",
            ).forEach { name ->
                val level = (LoggerFactory.getLogger(name) as Logger).effectiveLevel
                withClue("$name=$level") { level.isGreaterOrEqual(Level.INFO) shouldBe true }
            }
            System.getProperty("jdk.httpclient.HttpClient.log") shouldBe null
        }
    }
})
