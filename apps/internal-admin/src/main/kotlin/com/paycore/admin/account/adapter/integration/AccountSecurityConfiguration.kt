package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.LoginNameDigester
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.application.required.TotpSecretGenerator
import com.paycore.admin.account.application.required.TotpVerifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration
import java.time.Instant

/** account 보안 adapter 조립(설정 → Port 구현). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties::class, AdminDeploymentProperties::class, RecoveryProperties::class)
class AccountSecurityConfiguration {

    /** 배치 모드 가드(§4.8.2): LIVE에서 Demo 키·Demo 수치·BCrypt 하한 미달이면 이 bean 생성이 실패해 기동하지 않는다. 다른 보안 bean이 이것에 의존한다. */
    @Bean
    fun liveModeStartupGuard(deployment: AdminDeploymentProperties, properties: AdminSecurityProperties, environment: Environment): LiveModeGuardPassed {
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
}
