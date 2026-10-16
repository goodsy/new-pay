package com.paycore.payment.payment.adapter.web

import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * 내부 경로 격리(MASTER-SNAPSHOT-001 이월, CP-API-001 명세 §8). 내장 Tomcat에 내부 전용 connector([PaymentApiSettings.internalPort])를 더하고
 * 진입 필터가 요청이 들어온 connector로 가른다:
 * - 공개 포트: `/internal` 아래는 404(없는 경로와 같은 응답 — 존재 비노출).
 * - 내부 포트: `/internal` 아래만, 그 밖(`/v1` 아래 포함)은 404.
 * 내부 경로 자체의 인증은 기존 봉투 HMAC(D-MS-004)이고, 운영망 노출 제한·mTLS는 배포 작업이다. 경로 규칙만 쓰는 대신 포트를 나누는 이유:
 * LB/프록시 경로 규칙 하나의 실수로 내부 endpoint가 외부에 열리는 단일 실패점을 없앤다(심층 방어).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentApiSettings::class)
class InternalConnectorConfiguration {

    /**
     * 내부 connector. 공개 포트(`server.port`)와 같은 포트면 기동을 거부한다(review round1 n-4) — 같은 포트면 connector 구분이 불가능하다.
     * 0(시험용 임의 포트)은 비교하지 않는다.
     */
    @Bean
    fun internalConnector(settings: PaymentApiSettings, @Value("\${server.port:8080}") serverPort: Int): InternalConnector {
        check(settings.internalPort == 0 || settings.internalPort != serverPort) {
            "paycore.payment.api.internal-port must differ from server.port ($serverPort): the internal connector would not be separable"
        }
        return InternalConnector(
            Connector().apply {
                port = settings.internalPort
                settings.internalAddress?.let { setProperty("address", it) }
            },
        )
    }

    @Bean
    fun internalConnectorCustomizer(internal: InternalConnector): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory -> factory.addAdditionalConnectors(internal.connector) }

    /** 가장 먼저 실행된다(Spring Security 필터 체인보다 앞). */
    @Bean
    fun apiEntryFilter(internal: InternalConnector): FilterRegistrationBean<ApiEntryFilter> =
        FilterRegistrationBean(ApiEntryFilter(internal)).apply { order = Ordered.HIGHEST_PRECEDENCE }
}

/** 내부 connector 참조(기동 뒤 실제 포트를 알 수 있다 — 설정 0이면 임의 포트). */
class InternalConnector(val connector: Connector) {
    /** 실제 수신 포트. 서버가 시작되지 않았으면(MockMvc 등) -1. */
    val localPort: Int get() = connector.localPort
}
