package com.paycore.payment.payment.adapter.integration.institution

import java.time.Duration

/** 인증 backend 설정 Named Fixture(Demo — 운영값 아님). 시험 키는 시험 전용 값이다. */
object AuthenticationSettingsFixture {
    const val CUSTOMER_BASE = "https://sim.example.test"
    const val CLIENT_KEY = "test-only-simulator-client-key-1"

    fun profile() = AuthenticationSettings.Profile(
        version = "sim-auth-demo-1",
        cardCompanies = listOf(AuthenticationSettings.CardCompany("SIMISS-A", "가상카드A"), AuthenticationSettings.CardCompany("SIMISS-B", "가상카드B")),
        planPolicy = AuthenticationSettings.PlanPolicy("plan-demo-1", "KRW", listOf(2, 3, 6), 50_000),
    )

    /** [allowLoopbackHttp]: 시험·로컬 Demo 전용 표식(검토 #2 n-4) — 이 Fixture의 기본 서버 URL이 loopback http stub이라 기본값은 켬. */
    fun connection(
        baseUrl: String = "http://127.0.0.1:1",
        customerBase: String = CUSTOMER_BASE,
        readTimeout: Duration = Duration.ofMillis(800),
        allowLoopbackHttp: Boolean = true,
    ) = AuthenticationSettings.SimulatorConnection(baseUrl, customerBase, CLIENT_KEY, Duration.ofMillis(500), readTimeout, allowLoopbackHttp)

    fun settings(
        backends: Map<String, AuthenticationSettings.Backend> = mapOf("TEST" to AuthenticationSettings.Backend("SIMULATOR", "sim-auth-demo")),
        connection: AuthenticationSettings.SimulatorConnection = connection(),
    ) = AuthenticationSettings(backends, mapOf("sim-auth-demo" to profile()), connection)
}
