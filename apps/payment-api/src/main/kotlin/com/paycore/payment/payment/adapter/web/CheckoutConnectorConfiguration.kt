package com.paycore.payment.payment.adapter.web

import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 결제창 connector 설정(`paycore.payment.checkout`, AUTHN-001 §12.1 AN-15 — **운영 기본값 없음**: 값이 없으면 기동 실패). 0은 시험용 임의 포트.
 * @param address 바인딩 주소(선택). 운영 배포는 checkout 전용 origin(배포 작업).
 */
@ConfigurationProperties(prefix = "paycore.payment.checkout")
class CheckoutConnectorSettings(val port: Int, val address: String? = null) {
    init {
        require(port in 0..MAX_PORT) { "checkout.port must be within 0..$MAX_PORT" }
        require(address == null || address.isNotBlank()) { "checkout.address must not be blank when set" }
    }

    private companion object {
        const val MAX_PORT = 65_535
    }
}

/**
 * 결제창 전용 connector(D-AUTHN-006 승인 편차 — payment-api 안 별도 connector·별도 SecurityFilterChain). 진입 필터([ApiEntryFilter])가 요청이 들어온 connector로 가른다:
 * checkout 포트는 `/checkout` 아래만, `/checkout` 아래는 checkout 포트에서만(그 밖 404). 공개·내부 포트와 같은 포트면 기동을 거부한다(0 — 시험 임의 포트 — 는 비교하지 않는다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutConnectorSettings::class)
class CheckoutConnectorConfiguration {

    @Bean
    fun checkoutConnector(settings: CheckoutConnectorSettings, api: PaymentApiSettings, @Value("\${server.port:8080}") serverPort: Int): CheckoutConnector {
        check(settings.port == 0 || (settings.port != serverPort && settings.port != api.internalPort)) {
            "paycore.payment.checkout.port must differ from server.port and the internal port: the checkout connector would not be separable"
        }
        return CheckoutConnector(
            Connector().apply {
                port = settings.port
                settings.address?.let { setProperty("address", it) }
            },
        )
    }

    @Bean
    fun checkoutConnectorCustomizer(checkout: CheckoutConnector): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory -> factory.addAdditionalConnectors(checkout.connector) }
}

/** 결제창 connector 참조(기동 뒤 실제 포트 — 설정 0이면 임의 포트). */
class CheckoutConnector(val connector: Connector) {
    /** 실제 수신 포트. 서버가 시작되지 않았으면 -1. */
    val localPort: Int get() = connector.localPort
}
