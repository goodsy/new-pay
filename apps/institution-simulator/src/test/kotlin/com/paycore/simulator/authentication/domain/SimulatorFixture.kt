package com.paycore.simulator.authentication.domain

/** Simulator Named Fixture(Test Fixture Policy, src/test 전용). Demo 값 — 운영 MID·카드사·URL 아님. 바인딩은 한 요청 값에서만 나온다. */
object SimulatorFixture {
    const val RETURN_ORIGIN = "https://checkout.example.test"
    const val HANDLE = "Hh0123456789abcdefghij"
    const val STATE = "s0123456789abcdefghijklmnopqrstuvwxyzABCDEF"

    fun request(
        environment: String = "TEST",
        amountMinor: Long = 10_000,
        plan: PlanEcho = PlanEcho("LUMP_SUM", null),
        state: String = STATE,
    ) = RegistrationRequest(
        paymentId = "pay_demo0001",
        merchantRef = "mid-demo-0001",
        environment = environment,
        amountMinor = amountMinor,
        currency = "KRW",
        plan = plan,
        cardCompany = "SIMISSA",
        state = state,
        returnUrl = "$RETURN_ORIGIN/checkout/s/$HANDLE/authentication/return",
    )
}
