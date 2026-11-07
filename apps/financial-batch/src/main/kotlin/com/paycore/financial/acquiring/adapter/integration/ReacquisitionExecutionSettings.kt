package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.ReacquisitionExecutionPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 재매입 실행 기한 정책(ACQ-ADMIN-001 — 명세 I-2, D-OPS-002 선례). ADMIN 채널 설정과 **같은 속성**을 읽는다(`paycore.financial.admin-channel.max-execution-window`·
 * `clock-window` — 채널 설정 [com.paycore.financial.acquiring.adapter.web.admin.AdminChannelSettings]가 범위를 검사하고 기동을 거부한다. 여기서도 같은 범위를 다시 본다).
 * 운영 기본값 없음(Demo PT24H·PT300S — 운영값 LIVE Gate).
 */
@ConfigurationProperties(prefix = "paycore.financial.admin-channel")
class ReacquisitionExecutionSettings(maxExecutionWindow: Duration, clockWindow: Duration) : ReacquisitionExecutionPolicy {

    override val maxExecutionWindow: Duration = maxExecutionWindow.also {
        require(it >= Duration.ofMinutes(1) && it <= Duration.ofDays(7)) { "admin-channel max-execution-window must be within 1m..7d" }
    }

    override val clockWindow: Duration = clockWindow.also {
        require(it >= Duration.ofSeconds(1) && it < maxExecutionWindow) { "admin-channel clock-window must be positive and shorter than max-execution-window" }
    }

    override fun toString(): String = "ReacquisitionExecutionSettings(maxExecutionWindow=$maxExecutionWindow, clockWindow=$clockWindow)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReacquisitionExecutionSettings::class)
class ReacquisitionExecutionConfiguration
