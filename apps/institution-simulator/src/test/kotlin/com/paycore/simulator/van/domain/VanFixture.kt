package com.paycore.simulator.van.domain

import java.time.Duration
import java.time.Instant

/**
 * 가상 VAN domain Named Test Fixture(Demo 값 — 운영값 아님). 요청·인증 증거·프로파일은 업무 조건이 드러나게 명시적으로 만든다. 증거는 기본 요청과 같은 바인딩
 * (금액·통화·Plan·MID·코드)으로 만들고, 시험이 바꾸는 필드만 [evidence]의 인자로 고친다.
 */
object VanFixture {
    val now: Instant = Instant.parse("2026-10-16T01:00:31.000Z")
    const val CLIENT = "pg-demo"

    /** 요청 ID의 ULID 시각이 [now]보다 [age] 이전인 `ar_` 요청 ID. */
    fun requestId(age: Duration = Duration.ofSeconds(1), seq: Int = 1): String = "ar_" + VanUlid.format(now.minus(age), seq)

    fun request(
        requestId: String = requestId(),
        amountMinor: Long = 10_000,
        plan: VanPlan = VanPlan.LUMP_SUM,
        evidenceRef: String = "sim-ev-A",
        environment: String = "TEST",
        merchantRef: String = "mid-demo-0001",
        issuerCode: String = "SIMISS-A",
    ) = VanAuthRequest(
        requestId = requestId, institutionAccount = "SIMMERCH0001", merchantRef = merchantRef, environment = environment, routeRef = "SIM-VAN-01",
        amountMinor = amountMinor, currency = "KRW", plan = plan,
        authentication = VanAuthentication(evidenceRef = evidenceRef, providerCode = "SIMAUTH-1", issuerCode = issuerCode, acquirerCode = "SIMACQ-A"),
    )

    fun evidence(
        request: VanAuthRequest = request(),
        cardRef: String = "VC-CREDIT-OK",
        cardType: String = "CREDIT",
        authenticated: Boolean = true,
        clientId: String = CLIENT,
        validUntil: Instant = now.plusSeconds(300),
        amountMinor: Long = request.amountMinor,
        plan: VanPlan = request.plan,
    ) = VanEvidence(
        evidenceRef = request.authentication.evidenceRef, clientId = clientId, authenticated = authenticated, environment = request.environment,
        merchantRef = request.merchantRef, amountMinor = amountMinor, currency = request.currency, plan = plan, cardRef = cardRef, cardType = cardType,
        issuerCode = request.authentication.issuerCode, acquirerCode = request.authentication.acquirerCode, providerCode = request.authentication.providerCode,
        validUntil = validUntil,
    )

    /** 프로파일 A(무효과 확정 Capability 있음 — Demo). */
    val profileA = VanProfile(
        name = "A", confirmsNoEffect = true, closableAfter = Duration.ofMinutes(2), inquiryRetention = Duration.ofDays(7),
        creditInstallmentMonths = setOf(2, 3, 6, 12), pendingDelay = Duration.ofSeconds(5),
    )

    /** 프로파일 B(무효과 확정 없음 — Demo). */
    val profileB = profileA.copy(name = "B", confirmsNoEffect = false)

    val ids = VanDecisionIds(
        decisionId = "vd_" + VanUlid.format(now, 101), pendingId = "vp_" + VanUlid.format(now, 102), closureId = "vc_" + VanUlid.format(now, 103),
        approvalNumber = "30000001", providerTransactionId = "SIMPTX-0000000001",
    )

    fun authorize(
        request: VanAuthRequest = request(),
        existing: VanLedgerEntry? = null,
        pending: VanPendingDecision? = null,
        evidence: VanEvidence? = evidence(request),
        evidenceUsedByAnotherRequest: Boolean = false,
        profile: VanProfile = profileA,
        at: Instant = now,
    ): VanJudgement = VanAuthorization.judge(CLIENT, request, existing, pending, evidence, evidenceUsedByAnotherRequest, profile, ids, at)
}
