package com.paycore.payment.payment.adapter.integration.institution

import java.time.Duration

/** 가상 VAN Route 등록부 Named Test Fixture(Demo — 운영값 아님). 프로파일 A = 무효과 확정 Capability 있음, B = 없음(AUTH-001 AU-15). 키는 golden 시험 키. */
object VanRouteFixture {
    fun capabilities(
        confirmsNoEffect: Boolean = true,
        closableAfter: Duration = Duration.ofMinutes(2),
        inquiryRetention: Duration = Duration.ofDays(7),
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
    ) = ConfiguredInstitutionRouteRegistry.Route(
        environment = environment, institution = "SIMVAN", protocolVersion = protocolVersion, profileVersion = profileVersion, host = host, port = port,
        keyId = StubVan.KEY_ID, keyBase64 = keyBase64, connectTimeout = connectTimeout, readTimeout = readTimeout, totalDeadline = totalDeadline,
        connectRetryBudget = connectRetryBudget, capabilities = capabilities(confirmsNoEffect, closableAfter, inquiryRetention),
    )

    fun registry(vararg routes: Pair<String, ConfiguredInstitutionRouteRegistry.Route>, backendType: String = "SIMULATOR", backendEnvironment: String = "TEST") =
        ConfiguredInstitutionRouteRegistry(
            backends = mapOf(backendEnvironment to ConfiguredInstitutionRouteRegistry.Backend(backendType)),
            routes = routes.toMap(),
            allowedHosts = emptyList(),
        )
}
