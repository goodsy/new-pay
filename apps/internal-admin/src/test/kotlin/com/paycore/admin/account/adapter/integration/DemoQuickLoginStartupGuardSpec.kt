package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.adapter.web.DemoQuickLoginCondition
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

/**
 * 데모 간편 로그인 켜짐 조건·기동 거부(ADMIN-DEMO-LOGIN-001 §2 — T-2·T-3, D-ADMIN-AUTH-DEMO-001 ②).
 * - 기동 검사: LIVE(DEMO가 아닌 모드)에서 enabled=true면 거부, DEMO에서 enabled=true인데 허용 계정이 없거나 형식이 틀리면 거부. 보고는 설정 이름만.
 * - 등록 조건: DEMO **그리고** enabled=true일 때만 엔드포인트 bean이 생긴다.
 */
class DemoQuickLoginStartupGuardSpec : BehaviorSpec({

    fun props(enabled: Boolean?, vararg accounts: String) = DemoQuickLoginProperties(enabled, accounts.toList())

    // 계정 보안 설정 전체(시험 전용 공개 값 — LiveModeStartupGuardSpec과 같은 Demo 값). 바인딩이 먼저 실패하지 않게 둔다.
    val securityProperties = listOf(
        "paycore.admin.security.login-max-failures=5", "paycore.admin.security.totp-window-steps=1",
        "paycore.admin.security.enrollment-code-validity=24h", "paycore.admin.security.enrollment-step-validity=10m",
        "paycore.admin.security.password-min-length=12", "paycore.admin.security.password-max-bytes=72", "paycore.admin.security.bcrypt-cost=12",
        "paycore.admin.security.totp-issuer=PayCore Admin (Demo)", "paycore.admin.security.totp-cipher.current-key-version=1",
        "paycore.admin.security.totp-cipher.keys.1=dGVzdC1vbmx5LXRvdHAtY2lwaGVyLWtleS0zMmJ5dGU=",
        "paycore.admin.security.login-name-hmac-key=dGVzdC1vbmx5LWxvZ2luLW5hbWUtaG1hYy1rZXktMzI=",
        "paycore.admin.security.session.anonymous-idle=10m", "paycore.admin.security.session.password-step-idle=5m",
        "paycore.admin.security.session.authenticated-idle=15m", "paycore.admin.security.session.absolute=8h",
        "paycore.admin.recovery.episode-validity=24h",
    )

    Given("the startup check") {
        Then("LIVE with enabled=true is refused by setting name only (whatever the account list)") {
            DemoQuickLoginStartupGuard.violations(DeploymentMode.LIVE, props(true, "demo-operator")) shouldBe
                listOf("paycore.admin.demo-quick-login.enabled is not allowed outside DEMO")
            DemoQuickLoginStartupGuard.violations(DeploymentMode.LIVE, props(true)).first() shouldBe
                "paycore.admin.demo-quick-login.enabled is not allowed outside DEMO"
        }
        Then("DEMO with enabled=true needs at least one well-formed login name; values are not reported") {
            DemoQuickLoginStartupGuard.violations(DeploymentMode.DEMO, props(true)) shouldBe
                listOf("paycore.admin.demo-quick-login.accounts is required when enabled")
            val bad = DemoQuickLoginStartupGuard.violations(DeploymentMode.DEMO, props(true, "demo-operator", "Bad Name!"))
            bad shouldBe listOf("paycore.admin.demo-quick-login.accounts contains an invalid login name")
            bad.joinToString() shouldNotContain "Bad Name!"
        }
        Then("disabled (missing or false) passes in both modes, and DEMO with a valid list passes") {
            listOf(DeploymentMode.DEMO, DeploymentMode.LIVE).forEach { mode ->
                DemoQuickLoginStartupGuard.violations(mode, props(null)) shouldBe emptyList()
                DemoQuickLoginStartupGuard.violations(mode, props(false, "demo-operator")) shouldBe emptyList()
            }
            DemoQuickLoginStartupGuard.violations(DeploymentMode.DEMO, props(true, "demo-operator", "demo-approver", "demo-sa-a")) shouldBe emptyList()
        }
        Then("the application context refuses to start in LIVE with enabled=true (the guard bean runs in web and one-shot modes)") {
            ApplicationContextRunner()
                .withUserConfiguration(AccountSecurityConfiguration::class.java)
                .withPropertyValues(
                    *securityProperties.toTypedArray(), "paycore.admin.deployment-mode=LIVE", "paycore.admin.demo-quick-login.enabled=true", "paycore.admin.demo-quick-login.accounts=demo-operator",
                )
                .run { context ->
                    val messages = generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                    messages shouldContain "demo quick login refused"
                    messages shouldContain "paycore.admin.demo-quick-login.enabled is not allowed outside DEMO"
                }
        }
        Then("the application context refuses to start in DEMO with enabled=true and no accounts, and starts with a valid list") {
            ApplicationContextRunner()
                .withUserConfiguration(AccountSecurityConfiguration::class.java)
                .withPropertyValues(*securityProperties.toTypedArray(), "paycore.admin.deployment-mode=DEMO", "paycore.admin.demo-quick-login.enabled=true")
                .run { context ->
                    val messages = generateSequence<Throwable>(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                    messages shouldContain "paycore.admin.demo-quick-login.accounts is required when enabled"
                }
            ApplicationContextRunner()
                .withUserConfiguration(AccountSecurityConfiguration::class.java)
                .withPropertyValues(
                    *securityProperties.toTypedArray(), "paycore.admin.deployment-mode=DEMO", "paycore.admin.demo-quick-login.enabled=true",
                    "paycore.admin.demo-quick-login.accounts=demo-operator,demo-approver",
                )
                .run { context -> context.startupFailure shouldBe null }
        }
    }

    Given("the key classification for the startup WARN (review MINOR-2(b) — report only: Demo deployments use generated keys)") {
        Then("the repository's known demo keys are classified as known; fresh keys are not") {
            val known = AdminSecurityProperties(
                loginMaxFailures = 5, totpWindowSteps = 1, enrollmentCodeValidity = java.time.Duration.ofHours(24), enrollmentStepValidity = java.time.Duration.ofMinutes(10),
                passwordMinLength = 12, passwordMaxBytes = 72, bcryptCost = 12, totpIssuer = "PayCore Admin (Demo)",
                totpCipher = AdminSecurityProperties.TotpCipher(1, mapOf("1" to "dGVzdC1vbmx5LXRvdHAtY2lwaGVyLWtleS0zMmJ5dGU=")),
                loginNameHmacKey = "dGVzdC1vbmx5LWxvZ2luLW5hbWUtaG1hYy1rZXktMzI=",
                session = AdminSecurityProperties.Session(java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(15), java.time.Duration.ofHours(8)),
            )
            LiveModeStartupGuard.usesKnownDemoKeys(known) shouldBe true
            val fresh = java.util.Base64.getEncoder().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
            val other = AdminSecurityProperties(
                loginMaxFailures = 5, totpWindowSteps = 1, enrollmentCodeValidity = java.time.Duration.ofHours(24), enrollmentStepValidity = java.time.Duration.ofMinutes(10),
                passwordMinLength = 12, passwordMaxBytes = 72, bcryptCost = 12, totpIssuer = "PayCore Admin (Demo)",
                totpCipher = AdminSecurityProperties.TotpCipher(1, mapOf("1" to fresh)), loginNameHmacKey = "dGVzdC1vbmx5LWxvZ2luLW5hbWUtaG1hYy1rZXktMzI=",
                session = AdminSecurityProperties.Session(java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(15), java.time.Duration.ofHours(8)),
            )
            LiveModeStartupGuard.usesKnownDemoKeys(other) shouldBe false
        }
    }

    Given("the endpoint registration condition") {
        fun registered(vararg properties: String): Boolean {
            var result = false
            ApplicationContextRunner().withUserConfiguration(ConditionProbe::class.java).withPropertyValues(*properties).run { result = it.containsBean("probe") }
            return result
        }
        Then("only DEMO and enabled=true registers it") {
            registered("paycore.admin.deployment-mode=DEMO", "paycore.admin.demo-quick-login.enabled=true") shouldBe true
            registered("paycore.admin.deployment-mode=DEMO", "paycore.admin.demo-quick-login.enabled=false") shouldBe false
            registered("paycore.admin.deployment-mode=DEMO") shouldBe false
            registered("paycore.admin.deployment-mode=LIVE", "paycore.admin.demo-quick-login.enabled=true") shouldBe false
            registered("paycore.admin.deployment-mode=LIVE") shouldBe false
            registered("paycore.admin.demo-quick-login.enabled=true") shouldBe false
            // 리뷰 NIT-1: 모드 철자는 enum 바인딩(관대 변환)과 같은 정규화 — DEMO가 되는 값이면 켜지고, LIVE가 되는 값이면 꺼진다.
            listOf("demo", "Demo", "de-mo", "D_EMO").forEach { registered("paycore.admin.deployment-mode=$it", "paycore.admin.demo-quick-login.enabled=true") shouldBe true }
            listOf("live", "LI-VE", "DEMOX").forEach { registered("paycore.admin.deployment-mode=$it", "paycore.admin.demo-quick-login.enabled=true") shouldBe false }
        }
    }
}) {
    @Configuration(proxyBeanMethods = false)
    class ConditionProbe {
        @Bean
        @Conditional(DemoQuickLoginCondition::class)
        fun probe(): String = "registered"
    }
}
