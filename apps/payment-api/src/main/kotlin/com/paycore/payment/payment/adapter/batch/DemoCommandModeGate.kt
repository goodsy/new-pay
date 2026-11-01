package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment

/**
 * DEMO 배포 모드 게이트(DEMO-SEED-001 명세 §4.6, M2 — master-api 게이트와 같은 판정, 앱 간 production 공유 없음 R-1). `META-INF/spring.factories`로 등록된
 * 환경 후처리기라 ApplicationContext·DataSource·Flyway·Hibernate validate **전**에 돈다 — LIVE DB에 연결하기 전에 거부한다. order는 설정 파일·
 * `spring.config.additional-location`이 환경에 들어온 **뒤**(`ConfigDataEnvironmentPostProcessor.ORDER + 1`)라 파일로 준 값도 본다.
 *
 * 명령 속성(`paycore.payment.command`)이 없으면 아무것도 하지 않는다(웹 프로세스 영향 없음). 있으면 `paycore.payment.deployment-mode`가 정확히 `DEMO`
 * (대소문자 구분)일 때만 통과하고, 아니면 [DemoModeRefusedException](exit 3)으로 기동을 멈춘다.
 */
class DemoCommandModeGate : EnvironmentPostProcessor, Ordered {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!isCommand(environment)) return
        if (!isDemo(environment)) throw DemoModeRefusedException()
    }

    override fun getOrder(): Int = ORDER

    companion object {
        const val COMMAND_PROPERTY = "paycore.payment.command"
        const val MODE_PROPERTY = "paycore.payment.deployment-mode"
        const val DEMO = "DEMO"

        /** 설정 파일 반영(ConfigData) 바로 뒤. */
        const val ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1

        fun isCommand(environment: Environment): Boolean = environment.containsProperty(COMMAND_PROPERTY)

        fun isDemo(environment: Environment): Boolean = environment.getProperty(MODE_PROPERTY) == DEMO
    }
}
