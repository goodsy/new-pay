package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.PaymentOpsGateway
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Instant

/**
 * paymentops 조립(ADMIN-PAYMENT-OPS-001 §4.2·§4.7). 기동 검사:
 * - context path 금지(L-3, I-31): `server.servlet.context-path`가 비어 있지 않으면(루트 `/` 포함 — I-44와 같은 판정) 기동 거부.
 * - LIVE 배치 모드에서 Demo·시험 서명 키면 기동 거부(§4.2 키 공간 — 계약 golden 시험 키는 공개 값이다).
 * 설정 값 범위·시간 순서 검사는 [PaymentOpsSettings] 생성 때.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentOpsSettings::class)
class PaymentOpsConfiguration {

    @Bean
    fun paymentOpsGateway(settings: PaymentOpsSettings, environment: Environment): PaymentOpsGateway {
        startupGuard(environment.getProperty("server.servlet.context-path"), environment.getProperty("paycore.admin.deployment-mode"), settings)
        return HttpPaymentOpsGateway(settings, AdminCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
    }

    companion object {
        /** 공개된 Demo·계약 golden 시험 키(Base64) — LIVE에서 쓰면 기동 실패. */
        val DEMO_KEYS: List<String> = listOf(
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx",
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAy",
        )

        fun startupGuard(contextPath: String?, deploymentMode: String?, settings: PaymentOpsSettings) {
            check(contextPath.isNullOrEmpty()) { "server.servlet.context-path must be empty (signed path = request path — ADMIN-PAYMENT-OPS-001 L-3)" }
            check(deploymentMode != "LIVE" || DEMO_KEYS.none(settings::usesKey)) { "LIVE deployment must not use a Demo PAYMENT ops signing key" }
        }
    }
}
