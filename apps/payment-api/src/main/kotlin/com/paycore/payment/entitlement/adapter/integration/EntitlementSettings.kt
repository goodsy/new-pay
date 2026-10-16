package com.paycore.payment.entitlement.adapter.integration

import com.paycore.payment.entitlement.application.required.EntitlementTiming
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 판정 설정(명세 §8). **운영 기본값이 없다** — 값이 없으면 기동이 실패한다. 시계 오차 여유는 운영 미결(R7)이며 Demo(2초)·시험(100ms) 값은
 * 시험·로컬 설정에만 둔다.
 */
@ConfigurationProperties(prefix = "paycore.payment.entitlement")
class EntitlementSettings(override val clockSkewMargin: Duration) : EntitlementTiming {
    init {
        require(!clockSkewMargin.isNegative && clockSkewMargin < MAX_SKEW) { "clock-skew-margin must be within [0, $MAX_SKEW)" }
    }

    override fun toString(): String = "EntitlementSettings(clockSkewMargin=$clockSkewMargin)"

    private companion object {
        val MAX_SKEW: Duration = Duration.ofMinutes(10)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementSettings::class)
class EntitlementSettingsConfiguration
