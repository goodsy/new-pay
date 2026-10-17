package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.CheckoutPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 결제창 설정(`paycore.checkout`, AUTHN-001 §12.1). **운영 기본값이 없다** — 값이 없거나 형식이 틀리면 기동이 실패한다. 시험·로컬 값은 Demo(운영값 아님)이며 test classpath
 * `config/application.yml`에만 둔다. origin 검사(m-9): `https://` + 소문자 host(+ 포트), userinfo·path·query·fragment 없음. 수명은 ms 단위 정수·1초 이상(DATETIME(3) 저장).
 */
@ConfigurationProperties(prefix = "paycore.checkout")
class CheckoutSettings(
    override val policyVersion: String,
    override val publicBaseUrl: String,
    override val tokenTtl: Duration,
    override val sessionTtl: Duration,
    override val authenticationTtl: Duration,
) : CheckoutPolicy {
    init {
        require(policyVersion.isNotBlank() && policyVersion.length <= VERSION_MAX_LENGTH && policyVersion.all { it.code in 0x21..0x7E }) {
            "checkout.policy-version must be 1..$VERSION_MAX_LENGTH printable ASCII characters"
        }
        require(HTTPS_ORIGIN.matches(publicBaseUrl)) { "checkout.public-base-url must be https://host[:port] without path, query or fragment" }
        listOf(tokenTtl, sessionTtl, authenticationTtl).forEach {
            require(it >= MIN_TTL && it.nano % NANOS_PER_MILLI == 0) { "checkout TTLs must be whole milliseconds and at least 1s" }
        }
    }

    override fun toString(): String =
        "CheckoutSettings(policyVersion=$policyVersion, publicBaseUrl=$publicBaseUrl, tokenTtl=$tokenTtl, sessionTtl=$sessionTtl, authenticationTtl=$authenticationTtl)"

    private companion object {
        const val VERSION_MAX_LENGTH = 64
        const val NANOS_PER_MILLI = 1_000_000
        val MIN_TTL: Duration = Duration.ofSeconds(1)
        val HTTPS_ORIGIN = Regex("^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]{1,5})?$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutSettings::class)
class CheckoutSettingsConfiguration
