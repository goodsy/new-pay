package com.paycore.simulator.van.application.provided

import java.time.LocalDate

/**
 * 가상 VAN 원장 조회(가상 매입사 대조용 — ACQUIRING-001 2-E §7). 기관 거래 참조로 승인·승인된 취소를 찾는다(D-ACQ-008: 기관 측 대조는 기관 거래 참조).
 * 같은 참조가 둘 이상이면(원장 정상 동작에서는 없다) 찾지 못한 것으로 답한다 — 매입 판정이 근거를 골라 쓰지 않는다. 호출자 TX 안에서만(MANDATORY, 읽기만).
 */
interface VanAcquiringEvidenceQuery {
    fun authorization(clientId: String, providerTransactionId: String): VanAuthorizationFact?

    fun cancellation(clientId: String, providerTransactionId: String): VanCancellationFact?
}

/** 승인 사실 + 그 원승인에 VAN이 승인한 취소 금액 합(한 스냅샷 SELECT — `VanCancelLedgerStore.approvedTotals`). */
data class VanAuthorizationFact(
    val entryId: String,
    val providerTransactionId: String,
    val approvalNumber: String,
    val amountMinor: Long,
    val currency: String,
    val institutionTransactionDate: LocalDate,
    val approvedCancelSumMinor: Long,
)

/** 승인된 취소 사실. [originalAuthorizationEntryId] = 그 취소가 가리키는 원승인 VAN 원장 행(같은 namespace의 원 요청 ID로 찾음 — 없으면 null). */
data class VanCancellationFact(
    val entryId: String,
    val originalAuthorizationEntryId: String?,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val cancelledAmountMinor: Long,
    val currency: String,
    val institutionTransactionDate: LocalDate,
)
