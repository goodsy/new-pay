package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.CheckoutValidityPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 결제 유효기간 설정(`paycore.payment.checkout-validity`, D-AUTHN-005). **운영 기본값이 없다** — 값이 없거나 형식이 틀리면 기동이 실패한다(n-g:
 * `duration` ms 단위 정수·1초 이상 — 검토 #2 n-3, version 1..64자). 시험·로컬 값은 Demo(운영값 아님)이며 test classpath `config/application.yml`에만 둔다.
 */
@ConfigurationProperties(prefix = "paycore.payment.checkout-validity")
class CheckoutValiditySettings(override val version: String, override val duration: Duration) : CheckoutValidityPolicy {
    init {
        require(version.isNotBlank() && version.length <= VERSION_MAX_LENGTH && version.all { it.code in 0x21..0x7E }) {
            "checkout-validity.version must be 1..$VERSION_MAX_LENGTH printable ASCII characters"
        }
        // DATETIME(3) 저장: ms 미만 기간은 반올림으로 checkout_expires_at = created_at(CHECK 위반)이 될 수 있다(검토 #2 n-3) → ms 단위 정수, 1초 이상.
        require(duration >= MIN_DURATION && duration.nano % NANOS_PER_MILLI == 0) { "checkout-validity.duration must be whole milliseconds and at least 1s" }
    }

    override fun toString(): String = "CheckoutValiditySettings(version=$version, duration=$duration)"

    private companion object {
        const val VERSION_MAX_LENGTH = 64
        const val NANOS_PER_MILLI = 1_000_000
        val MIN_DURATION: Duration = Duration.ofSeconds(1)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutValiditySettings::class)
class CheckoutValiditySettingsConfiguration
