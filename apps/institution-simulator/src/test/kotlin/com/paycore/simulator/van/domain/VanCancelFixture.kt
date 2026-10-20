package com.paycore.simulator.van.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 가상 VAN 취소 domain Named Test Fixture(CANCEL-001 §6 — Demo 값, 운영값 아님). 원 승인은 [VanFixture]의 승인 판정으로 만든 원장 행이고, 취소 요청의 echo 필드는 기본으로 그
 * 원 승인 값과 같다 — 시험이 바꾸는 필드만 인자로 고친다. 이미 승인된 취소 합계는 [approved]로 명시한다(독립 난수 없음).
 */
object VanCancelFixture {
    val now: Instant = VanFixture.now

    /** 원 승인 시각(취소 기한 기준) — [now]보다 하루 전. */
    val approvedAt: Instant = now.minus(Duration.ofDays(1))

    fun cancelRequestId(age: Duration = Duration.ofSeconds(1), seq: Int = 21): String = "cr_" + VanUlid.format(now.minus(age), seq)

    /** 원장의 원 승인(APPROVED). 카드 참조가 취소 시나리오를 정한다(CN-25). */
    fun originalApproved(
        cardRef: String = "VC-CREDIT-OK",
        cardType: String = "CREDIT",
        amountMinor: Long = 10_000,
        plan: VanPlan = VanPlan.LUMP_SUM,
        account: String = "SIMMERCH0001",
        at: Instant = approvedAt,
    ): VanLedgerEntry {
        val request = VanFixture.request(requestId = VanFixture.requestId(age = Duration.between(at, now).plusSeconds(1)), amountMinor = amountMinor, plan = plan)
        val namespace = VanNamespace(VanFixture.CLIENT, "TEST", account, VanNamespace.AUTHORIZE, request.requestId)
        val approval = VanApproval("30000001", "SIMPTX-0000000001", amountMinor, "KRW", plan, cardType, VanDates.seoulDate(at), VanDates.seoulDate(at))
        return VanLedgerEntry.approved(namespace, "vd_" + VanUlid.format(at, 1), request.payloadHash(), "sim-ev-A", cardRef, approval, at)
    }

    fun originalDeclined(): VanLedgerEntry {
        val request = VanFixture.request(requestId = VanFixture.requestId(age = Duration.ofDays(1)))
        val namespace = VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.AUTHORIZE, request.requestId)
        return VanLedgerEntry.declined(namespace, "vd_" + VanUlid.format(approvedAt, 2), request.payloadHash(), "sim-ev-A", "VC-VAN-DECLINE", VanDeclineCategory.ISSUER_DECLINED, approvedAt)
    }

    fun originalClosure(): VanLedgerEntry {
        val namespace = VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.AUTHORIZE, VanFixture.requestId(age = Duration.ofDays(1)))
        return VanLedgerEntry.closure(namespace, "vc_" + VanUlid.format(approvedAt, 3), null, approvedAt)
    }

    @Suppress("LongParameterList")
    fun request(
        original: VanLedgerEntry = originalApproved(),
        amountMinor: Long = checkNotNull(original.approval).amountMinor,
        scope: VanCancelScope = VanCancelScope.FULL,
        requestId: String = cancelRequestId(),
        environment: String = "TEST",
        originalProviderTransactionId: String = original.approval?.providerTransactionId ?: "SIMPTX-0000000001",
        originalApprovalNumber: String = original.approval?.approvalNumber ?: "30000001",
        originalTransactionDate: LocalDate = original.approval?.institutionTransactionDate ?: VanDates.seoulDate(approvedAt),
        currency: String = "KRW",
        account: String = original.namespace.institutionAccount,
    ) = VanCancelRequest(
        requestId = requestId, institutionAccount = account, merchantRef = "mid-demo-0001", environment = environment, routeRef = "SIM-VAN-01",
        originalRequestId = original.namespace.requestId, originalProviderTransactionId = originalProviderTransactionId, originalApprovalNumber = originalApprovalNumber,
        originalTransactionDate = originalTransactionDate, cancelAmountMinor = amountMinor, currency = currency, scope = scope,
    )

    /** 취소 판정 식별자: 결정 `vd_`·종결 `vc_`, 취소 승인번호·취소 PTX(Demo 형식). */
    val ids = VanDecisionIds(
        decisionId = "vd_" + VanUlid.format(now, 201), pendingId = "vp_" + VanUlid.format(now, 202), closureId = "vc_" + VanUlid.format(now, 203),
        approvalNumber = "40000001", providerTransactionId = "SIMPTX-C000000001",
    )

    @Suppress("LongParameterList")
    fun cancel(
        request: VanCancelRequest = request(),
        original: VanLedgerEntry? = originalApproved(),
        existing: VanCancelEntry? = null,
        approved: VanApprovedCancels = VanApprovedCancels.NONE,
        profile: VanProfile = VanFixture.profileA,
        at: Instant = now,
    ): VanCancelJudgement = VanCancellation.judge(VanFixture.CLIENT, request, existing, original, approved, profile, ids, at)
}
