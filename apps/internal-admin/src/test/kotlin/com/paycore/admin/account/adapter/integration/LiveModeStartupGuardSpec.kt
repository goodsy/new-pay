package com.paycore.admin.account.adapter.integration

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * 배치 모드 가드(§4.8.2 n-4 + S3 리뷰 m-5 + S4 리뷰 m-5·m-9 — `LiveModeStartupGuardSpec`). LIVE에서: 저장소의 알려진 Demo 키(SHA-256 지문)·Demo 보안 수치(TOTP 창·
 * `spring.session.timeout` 포함)·BCrypt cost 하한 미달이면 기동 실패. DEMO는 통과. 보고에는 설정 이름만(키 값 없음). LIVE 보안 수치는 Q-ADMIN-SEC-VALUES 답변 전이라
 * LIVE는 사실상 기동할 수 없다(LIVE Gate 차단). Demo cost 12와 하한 12가 겹쳐 LIVE에서 실제 허용되는 최소 cost는 13이다(D-79).
 */
class LiveModeStartupGuardSpec : BehaviorSpec({

    val demoTotpKey = "dGVzdC1vbmx5LXRvdHAtY2lwaGVyLWtleS0zMmJ5dGU="
    val demoHmacKey = "dGVzdC1vbmx5LWxvZ2luLW5hbWUtaG1hYy1rZXktMzI="
    val demoSessionTimeout = Duration.ofMinutes(10)
    fun randomKey(): String = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun properties(cost: Int = 12) = AdminSecurityProperties(
        loginMaxFailures = 5, totpWindowSteps = 1, enrollmentCodeValidity = Duration.ofHours(24), enrollmentStepValidity = Duration.ofMinutes(10),
        passwordMinLength = 12, passwordMaxBytes = 72, bcryptCost = cost, totpIssuer = "PayCore Admin (Demo)",
        totpCipher = AdminSecurityProperties.TotpCipher(1, mapOf("1" to demoTotpKey)), loginNameHmacKey = demoHmacKey,
        session = AdminSecurityProperties.Session(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(8)),
    )

    val demoRecoveryValidity: Duration = Duration.ofHours(24)

    val demoProperties = listOf(
        "paycore.admin.deployment-mode=LIVE",
        "paycore.admin.security.login-max-failures=5", "paycore.admin.security.totp-window-steps=1",
        "paycore.admin.security.enrollment-code-validity=24h", "paycore.admin.security.enrollment-step-validity=10m",
        "paycore.admin.security.password-min-length=12", "paycore.admin.security.password-max-bytes=72", "paycore.admin.security.bcrypt-cost=12",
        "paycore.admin.security.totp-issuer=PayCore Admin (Demo)", "paycore.admin.security.totp-cipher.current-key-version=1",
        "paycore.admin.security.totp-cipher.keys.1=$demoTotpKey", "paycore.admin.security.login-name-hmac-key=$demoHmacKey",
        "paycore.admin.security.session.anonymous-idle=10m", "paycore.admin.security.session.password-step-idle=5m",
        "paycore.admin.security.session.authenticated-idle=15m", "paycore.admin.security.session.absolute=8h",
    )

    Given("DEMO mode") {
        Then("Demo keys and Demo values are allowed (local development and tests)") {
            LiveModeStartupGuard.violations(DeploymentMode.DEMO, properties(), demoSessionTimeout, demoRecoveryValidity) shouldBe emptyList()
        }
    }

    Given("LIVE mode") {
        Then("every Demo key, every Demo security value and a BCrypt cost below the LIVE minimum are reported — the complete list, by setting name") {
            LiveModeStartupGuard.violations(DeploymentMode.LIVE, properties(cost = 11), demoSessionTimeout, demoRecoveryValidity) shouldBe listOf(
                "paycore.admin.security.totp-cipher.keys.1 is a known demo key",
                "paycore.admin.security.login-name-hmac-key is a known demo key",
                "paycore.admin.security.bcrypt-cost is below the LIVE minimum 12",
                "paycore.admin.security.login-max-failures is the demo value",
                "paycore.admin.security.totp-window-steps is the demo value",
                "paycore.admin.security.enrollment-code-validity is the demo value",
                "paycore.admin.security.enrollment-step-validity is the demo value",
                "paycore.admin.security.password-min-length is the demo value",
                "paycore.admin.security.totp-issuer is the demo value",
                "paycore.admin.security.session.anonymous-idle is the demo value",
                "paycore.admin.security.session.password-step-idle is the demo value",
                "paycore.admin.security.session.authenticated-idle is the demo value",
                "paycore.admin.security.session.absolute is the demo value",
                "spring.session.timeout is the demo value",
                // S6 리뷰 Q-S6-2(Coordinator 판정): 복구 회차 유효 Demo 값(24h)도 LIVE에서 거부(강화 — 목록 확장).
                "paycore.admin.recovery.episode-validity is the demo value",
            )
            // cost 12는 하한은 넘지만 Demo 값이다 — LIVE에서 실제 허용되는 최소 cost는 13.
            LiveModeStartupGuard.violations(DeploymentMode.LIVE, properties(cost = 12), demoSessionTimeout, demoRecoveryValidity).filter { "bcrypt" in it } shouldBe
                listOf("paycore.admin.security.bcrypt-cost is the demo value")
        }
        Then("fresh keys and non-demo values above the cost minimum pass") {
            val session = AdminSecurityProperties.Session(Duration.ofMinutes(9), Duration.ofMinutes(4), Duration.ofMinutes(14), Duration.ofHours(7))
            val custom = AdminSecurityProperties(
                loginMaxFailures = 4, totpWindowSteps = 0, enrollmentCodeValidity = Duration.ofHours(12), enrollmentStepValidity = Duration.ofMinutes(9),
                passwordMinLength = 14, passwordMaxBytes = 72, bcryptCost = 13, totpIssuer = "PayCore Admin",
                totpCipher = AdminSecurityProperties.TotpCipher(1, mapOf("1" to randomKey())), loginNameHmacKey = randomKey(), session = session,
            )
            LiveModeStartupGuard.violations(DeploymentMode.LIVE, custom, Duration.ofMinutes(9), Duration.ofHours(12)) shouldBe emptyList()
        }
        Then("the application context refuses to start, naming settings only (no key material)") {
            ApplicationContextRunner()
                .withUserConfiguration(AccountSecurityConfiguration::class.java)
                .withPropertyValues(*demoProperties.toTypedArray(), "spring.session.timeout=10m")
                .run { context ->
                    val messages = generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                    messages shouldContain "LIVE deployment refused"
                    messages shouldContain "spring.session.timeout is the demo value"
                    messages shouldNotContain demoTotpKey
                    messages shouldNotContain demoHmacKey
                }
        }
        Then("the deployment mode has no default: with every other setting present, the failure names paycore.admin.deployment-mode") {
            ApplicationContextRunner()
                .withUserConfiguration(AccountSecurityConfiguration::class.java)
                .withPropertyValues(*demoProperties.filterNot { it.startsWith("paycore.admin.deployment-mode") }.toTypedArray())
                .run { context ->
                    val messages = generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                    messages shouldContain "paycore.admin.deployment-mode is required"
                }
        }
    }
})
