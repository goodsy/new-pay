package com.paycore.admin.financialops.adapter.integration

import com.paycore.admin.financialops.application.required.FinancialOpsGateway
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * financialops 조립(ACQ-VIEW-001 §3.1·§4.6). 기동 검사:
 * - context path 금지(서명 경로 = 요청 경로).
 * - LIVE 배치 모드에서 Demo·계약 golden·시험 키면 거부.
 * - **채널별 별도 키**: financial-ops 키의 kid 또는 바이트가 PAYMENT ops(`paycore.admin.payment-ops.*`)·MASTER ops(`paycore.admin.master-ops.*`) 키와 같으면 거부 — 한쪽 키 유출이
 *   다른 소유 서비스 호출 권한이 되지 않게. 다른 모듈 설정 타입에 의존하지 않고 Environment 속성만 읽는다(모듈 경계). 실패 메시지에 키·kid가 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinancialOpsSettings::class)
class FinancialOpsConfiguration {

    /** 조회 Port([FinancialOpsGateway])와 조작 Port([com.paycore.admin.financialops.application.required.AcquiringOpsGateway])를 같은 HTTP 구현이 맡는다(키·permit은 분리). */
    @Bean
    fun financialOpsGateway(settings: FinancialOpsSettings, environment: Environment): HttpFinancialOpsGateway {
        startupGuard(
            environment.getProperty("server.servlet.context-path"), environment.getProperty("paycore.admin.deployment-mode"), settings,
            listOf(
                environment.getProperty("paycore.admin.payment-ops.key-id") to environment.getProperty("paycore.admin.payment-ops.signing-key"),
                environment.getProperty("paycore.admin.master-ops.key-id") to environment.getProperty("paycore.admin.master-ops.signing-key"),
            ),
        )
        return HttpFinancialOpsGateway(
            settings, FinancialCallSigner(settings.keyId, settings.signingKey()), FinancialCallSigner(settings.operationKeyId, settings.operationSigningKey()),
        ) { Instant.now() }
    }

    companion object {
        /** 공개된 Demo·계약 golden·시험 키(Base64) — LIVE에서 쓰면 기동 실패. */
        val DEMO_KEYS: List<String> = listOf(
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx",
            "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAy",
            "dGVzdC1vbmx5LWZpbmFuY2lhbC1hZG1pbi1jaGFubmVsLWtleS0wMDAx",
            // ACQ-ADMIN-001 시험 조작 키(fops-op-test-k1 — 공개 값).
            "dGVzdC1vbmx5LWZpbmFuY2lhbC1hZG1pbi1vcGVyYXRpb24ta2V5LTAwMDE=",
        )

        /** [otherChannels] = 다른 소유 서비스 채널의 (kid, Base64 키) 쌍들(없으면 null). */
        fun startupGuard(contextPath: String?, deploymentMode: String?, settings: FinancialOpsSettings, otherChannels: List<Pair<String?, String?>>) {
            check(contextPath.isNullOrEmpty()) { "server.servlet.context-path must be empty (signed path = request path — ACQ-VIEW-001 §3.1)" }
            check(deploymentMode != "LIVE" || DEMO_KEYS.none(settings::usesKey)) { "LIVE deployment must not use a Demo FINANCIAL ops signing key" }
            check(deploymentMode != "LIVE" || DEMO_KEYS.none(settings::operationUsesKey)) { "LIVE deployment must not use a Demo FINANCIAL operation signing key" }
            // ACQ-ADMIN-001 D-ACQA-001 "조작 전용 키 따로": 조작 키는 조회 키와 kid·바이트 모두 달라야 한다(조회 키가 새어도 매입 상태를 바꿀 수 없게 — FINANCIAL도 같은 기동 검사).
            check(settings.operationKeyId != settings.keyId) { "financial-ops operation key id must differ from the read key id (D-ACQA-001 separate key spaces)" }
            check(!MessageDigest.isEqual(settings.operationSigningKey(), settings.signingKey())) {
                "financial-ops operation signing key must differ from the read key (D-ACQA-001 separate key spaces)"
            }
            otherChannels.forEach { (keyId, key) ->
                check(keyId == null || (keyId != settings.keyId && keyId != settings.operationKeyId)) {
                    "financial-ops key ids must differ from the other owner-service channel key ids (separate key spaces)"
                }
                val bytes = key?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                check(bytes == null || (!MessageDigest.isEqual(bytes, settings.signingKey()) && !MessageDigest.isEqual(bytes, settings.operationSigningKey()))) {
                    "financial-ops signing keys must differ from the other owner-service channel keys (separate key spaces)"
                }
            }
        }
    }
}
