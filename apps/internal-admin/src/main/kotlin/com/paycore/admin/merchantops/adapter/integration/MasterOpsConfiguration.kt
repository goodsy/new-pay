package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.MerchantApprovalPolicy
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * merchantops 조립(MERCHANT-OPS-001 §4). 기동 검사:
 * - context path 금지(서명 경로 = 요청 경로 — `server.servlet.context-path`가 비어 있지 않으면 거부).
 * - LIVE 배치 모드에서 Demo·계약 golden 시험 키면 거부.
 * - **MASTER 키 공간 분리(R-2)**: master-ops 키의 kid 또는 바이트가 PAYMENT ops 키(`paycore.admin.payment-ops.key-id`·`signing-key`)와 같으면 거부 — 한쪽 키 유출이 다른 소유 서비스
 *   호출 권한이 되지 않게. paymentops 설정 타입에 의존하지 않고 Environment 속성만 읽는다(모듈 경계). 실패 메시지에 키·kid가 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MasterOpsSettings::class, MerchantApprovalSettings::class)
class MasterOpsConfiguration {

    /** 가맹점 2인 승인 실행 시간 정책(MERCHANT-OPS-002 C-19 — Demo 값, 기본값 없음). stale = master-ops connect + read + 30초. */
    @Bean
    fun merchantApprovalPolicy(approval: MerchantApprovalSettings, settings: MasterOpsSettings): MerchantApprovalPolicy =
        approval.policy(settings.connectTimeout, settings.readTimeout)

    /** TXN-LIST-ENRICH-001: 같은 객체가 라벨 Port(`MasterLabelGateway`)도 구현한다 — 반환 타입이 구현 타입이라 두 Port로 주입된다(PaymentOpsConfiguration과 같은 방식). */
    @Bean
    fun masterOpsGateway(settings: MasterOpsSettings, environment: Environment): HttpMasterOpsGateway {
        startupGuard(
            environment.getProperty("server.servlet.context-path"), environment.getProperty("paycore.admin.deployment-mode"), settings,
            environment.getProperty(PAYMENT_KEY_ID), environment.getProperty(PAYMENT_KEY),
        )
        return HttpMasterOpsGateway(settings, MasterCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
    }

    companion object {
        private const val PAYMENT_KEY_ID = "paycore.admin.payment-ops.key-id"
        private const val PAYMENT_KEY = "paycore.admin.payment-ops.signing-key"

        /** 공개된 Demo·계약 golden 시험 키(Base64) — LIVE에서 쓰면 기동 실패. */
        val DEMO_KEYS: List<String> = listOf(
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx",
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAy",
            "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx",
        )

        fun startupGuard(contextPath: String?, deploymentMode: String?, settings: MasterOpsSettings, paymentKeyId: String?, paymentKey: String?) {
            check(contextPath.isNullOrEmpty()) { "server.servlet.context-path must be empty (signed path = request path — MERCHANT-OPS-001 §4)" }
            check(deploymentMode != "LIVE" || DEMO_KEYS.none(settings::usesKey)) { "LIVE deployment must not use a Demo MASTER ops signing key" }
            check(paymentKeyId == null || paymentKeyId != settings.keyId) { "master-ops key id must differ from the payment-ops key id (separate key spaces)" }
            val paymentBytes = paymentKey?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            check(paymentBytes == null || !MessageDigest.isEqual(paymentBytes, settings.signingKey())) {
                "master-ops signing key must differ from the payment-ops signing key (separate key spaces)"
            }
        }
    }
}
