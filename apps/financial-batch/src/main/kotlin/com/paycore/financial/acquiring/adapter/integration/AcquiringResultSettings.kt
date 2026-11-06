package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 매입 결과 마감 설정(2-F — D-ACQ-004 "마감까지 결과 없는 건 UNKNOWN"). **Demo 값이며 운영값은 LIVE Gate §14-4**. 운영 기본값 없음: 값이 없으면 결과 마감을 하지 않는다
 * (fail-closed — 임의 기본값으로 UNKNOWN을 만들지 않는다). 값이 있으면 1분..30일.
 *
 * @param deadline 전송 완료(TRANSMITTED) 뒤 결과를 기다리는 시간(`paycore.financial.acquiring.results.deadline`).
 */
@ConfigurationProperties(prefix = "paycore.financial.acquiring.results")
class AcquiringResultSettings(deadline: Duration?) : AcquisitionResultPolicy {

    override val resultDeadline: Duration? = deadline?.also {
        require(it >= MIN && it <= MAX) { "paycore.financial.acquiring.results.deadline must be within $MIN..$MAX" }
    }

    override fun toString(): String = "AcquiringResultSettings(deadline=$resultDeadline)"

    private companion object {
        val MIN: Duration = Duration.ofMinutes(1)
        val MAX: Duration = Duration.ofDays(30)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcquiringResultSettings::class)
class AcquiringResultConfiguration
