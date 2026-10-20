package com.paycore.payment.payment.adapter.integration.institution

import java.time.Duration

/** 가상 VAN Route 등록부 Named Test Fixture(Demo — 운영값 아님). 프로파일 A = 무효과 확정 Capability 있음, B = 없음(AUTH-001 AU-15). 키는 golden 시험 키. */
object VanRouteFixture {
    fun capabilities(
        confirmsNoEffect: Boolean = true,
        closableAfter: Duration = Duration.ofMinutes(2),
        inquiryRetention: Duration = Duration.ofDays(7),
        cancel: ConfiguredInstitutionRouteRegistry.CancelCapabilities? = null,
    ) = ConfiguredInstitutionRouteRegistry.Capabilities(
        confirmsNoEffect = confirmsNoEffect,
        closableAfter = closableAfter,
        inquiryRetention = inquiryRetention,
        sameIdResendSafe = true,
        authenticationEvidenceReusableForResend = true,
        supportedCurrencies = listOf("KRW"),
        supportedCardTypes = listOf("CREDIT", "CHECK"),
        installmentCardTypes = listOf("CREDIT"),
        installmentMonths = listOf(2, 3, 6),
        installmentMinimumAmount = 50_000,
        cancel = cancel,
    )

    /** 취소 Capability(CANCEL-001 §6.6 CN-28 — Demo): 부분취소 = 신용·체크 일시불 + 신용 할부(프로파일 A 모양). */
    fun cancelCapabilities(
        version: String = "cancel-a-demo-1",
        confirmsNoEffect: Boolean = true,
        closableAfter: Duration = Duration.ofMinutes(2),
        inquiryRetention: Duration = Duration.ofDays(7),
        partialLumpSumCardTypes: List<String> = listOf("CREDIT", "CHECK"),
        partialInstallmentCardTypes: List<String> = listOf("CREDIT"),
    ) = ConfiguredInstitutionRouteRegistry.CancelCapabilities(
        version = version, partialLumpSumCardTypes = partialLumpSumCardTypes, partialInstallmentCardTypes = partialInstallmentCardTypes,
        confirmsNoEffect = confirmsNoEffect, closableAfter = closableAfter, inquiryRetention = inquiryRetention,
    )

    fun route(
        port: Int,
        confirmsNoEffect: Boolean = true,
        host: String = "127.0.0.1",
        environment: String = "TEST",
        protocolVersion: String = "sim-van-1",
        keyBase64: String = java.util.Base64.getEncoder().encodeToString(StubVan.KEY),
        connectTimeout: Duration = Duration.ofMillis(500),
        readTimeout: Duration = Duration.ofMillis(800),
        totalDeadline: Duration = Duration.ofSeconds(2),
        connectRetryBudget: Int = 2,
        closableAfter: Duration = Duration.ofMinutes(2),
        inquiryRetention: Duration = Duration.ofDays(7),
        profileVersion: String = "sim-van-a-demo-1",
        cancel: ConfiguredInstitutionRouteRegistry.CancelCapabilities? = null,
        institution: String = "SIMVAN",
    ) = ConfiguredInstitutionRouteRegistry.Route(
        environment = environment, institution = institution, protocolVersion = protocolVersion, profileVersion = profileVersion, host = host, port = port,
        keyId = StubVan.KEY_ID, keyBase64 = keyBase64, connectTimeout = connectTimeout, readTimeout = readTimeout, totalDeadline = totalDeadline,
        connectRetryBudget = connectRetryBudget, capabilities = capabilities(confirmsNoEffect, closableAfter, inquiryRetention, cancel),
    )

    /** sim-van-2 Route(취소 Capability 블록 포함 — CN-28). */
    fun cancelRoute(
        port: Int,
        cancel: ConfiguredInstitutionRouteRegistry.CancelCapabilities = cancelCapabilities(),
        environment: String = "TEST",
        institution: String = "SIMVAN",
        readTimeout: Duration = Duration.ofMillis(800),
        totalDeadline: Duration = Duration.ofSeconds(2),
        connectRetryBudget: Int = 2,
    ) = route(
        port, protocolVersion = "sim-van-2", profileVersion = "sim-van-a-demo-2", cancel = cancel, environment = environment, institution = institution,
        readTimeout = readTimeout, totalDeadline = totalDeadline, connectRetryBudget = connectRetryBudget,
    )

    fun registry(vararg routes: Pair<String, ConfiguredInstitutionRouteRegistry.Route>, backendType: String = "SIMULATOR", backendEnvironment: String = "TEST") =
        ConfiguredInstitutionRouteRegistry(
            backends = mapOf(backendEnvironment to ConfiguredInstitutionRouteRegistry.Backend(backendType)),
            routes = routes.toMap(),
            allowedHosts = emptyList(),
        )
}
