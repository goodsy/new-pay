package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.DemoQuickLoginSettings
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.LoginNameDigester
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.application.required.TotpSecretGenerator
import com.paycore.admin.account.application.required.TotpVerifier
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration
import java.time.Instant

/** account 보안 adapter 조립(설정 → Port 구현). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties::class, AdminDeploymentProperties::class, RecoveryProperties::class, DemoQuickLoginProperties::class)
class AccountSecurityConfiguration {

    /**
     * 배치 모드 가드(§4.8.2): LIVE에서 Demo 키·Demo 수치·BCrypt 하한 미달이면 이 bean 생성이 실패해 기동하지 않는다. 다른 보안 bean이 이것에 의존한다.
     * ADMIN-DEMO-LOGIN-001 C-2: 데모 간편 로그인 검사(LIVE + enabled 거부 등)를 **먼저** 한다 — 웹·1회성 명령 모드 모두 이 bean을 만든다.
     */
    @Bean
    fun liveModeStartupGuard(
        deployment: AdminDeploymentProperties,
        properties: AdminSecurityProperties,
        demoQuickLogin: DemoQuickLoginProperties,
        environment: Environment,
    ): LiveModeGuardPassed {
        DemoQuickLoginStartupGuard.check(deployment.deploymentMode, demoQuickLogin)
        val binder = Binder.get(environment)
        LiveModeStartupGuard.check(
            deployment.deploymentMode, properties, binder.bind("spring.session.timeout", Duration::class.java).orElse(null),
            binder.bind("paycore.admin.recovery.episode-validity", Duration::class.java).orElse(null),
        )
        return LiveModeGuardPassed(deployment.deploymentMode)
    }

    @Bean
    fun passwordHasher(properties: AdminSecurityProperties, @Suppress("UNUSED_PARAMETER") guard: LiveModeGuardPassed): PasswordHasher = BCryptPasswordHasher(properties.bcryptCost)

    @Bean
    fun totpSecretCipher(properties: AdminSecurityProperties, @Suppress("UNUSED_PARAMETER") guard: LiveModeGuardPassed): TotpSecretCipher =
        AesGcmTotpSecretCipher(properties.totpCipher.keys, properties.totpCipher.currentKeyVersion)

    /**
     * 데모 간편 로그인 설정(ADMIN-DEMO-LOGIN-001 C-4): 켜짐 = DEMO **그리고** enabled(가드 통과 뒤에만 만든다).
     * 리뷰 MINOR-2(c): 켜져 있으면 기동 WARN 1줄(허용 계정 수·키 분류만 — 계정 이름·키 값 없음) + gauge `paycore.admin.demo_quick_login.active` = 1(꺼지면 0).
     */
    @Bean
    fun demoQuickLoginSettings(
        properties: DemoQuickLoginProperties,
        security: AdminSecurityProperties,
        meters: ObjectProvider<MeterRegistry>,
        guard: LiveModeGuardPassed,
    ): DemoQuickLoginSettings {
        // guard.mode = 배치 모드 설정 그대로(가드 통과 값).
        val active = guard.mode == DeploymentMode.DEMO && properties.enabled
        if (active) {
            LoggerFactory.getLogger(DemoQuickLoginSettings::class.java).warn(
                "DEMO QUICK LOGIN ENABLED (demo only — must be off before operation): accounts={} knownDemoKeys={}",
                properties.accounts.distinct().size, LiveModeStartupGuard.usesKnownDemoKeys(security),
            )
        }
        meters.ifAvailable?.let { registry ->
            Gauge.builder(DEMO_QUICK_LOGIN_ACTIVE) { if (active) 1.0 else 0.0 }.strongReference(true).register(registry)
        }
        return object : DemoQuickLoginSettings {
            override val active: Boolean = active
            override val allowedLoginNames: List<String> = properties.accounts.distinct()
        }
    }

    @Bean
    fun totpVerifier(): TotpVerifier = Rfc6238TotpVerifier()

    @Bean
    fun totpSecretGenerator(): TotpSecretGenerator = SecureRandomTotpSecretGenerator()

    @Bean
    fun enrollmentCodeGenerator(): EnrollmentCodeGenerator = SecureRandomEnrollmentCodeGenerator()

    @Bean
    fun loginNameDigester(properties: AdminSecurityProperties, @Suppress("UNUSED_PARAMETER") guard: LiveModeGuardPassed): LoginNameDigester = HmacLoginNameDigester(properties.loginNameHmacKey)

    @Bean
    fun adminClock(): AdminClock = object : AdminClock {
        override fun now(): Instant = Instant.now()
    }

    private companion object {
        const val DEMO_QUICK_LOGIN_ACTIVE = "paycore.admin.demo_quick_login.active"
    }
}
