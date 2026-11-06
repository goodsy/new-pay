package com.paycore.simulator.acquirer.domain

import java.time.LocalDate

/**
 * 가상 VAN 취소 원장의 승인된 취소 사실(CANCEL 레코드 대조용 — 취소의 기관 거래 참조로 찾은 APPROVED 결정 하나). [entryId]는 판정 근거로 기록한다.
 * [originalAuthorizationEntryId] = 그 취소가 VAN에서 가리키는 원승인 행 — 수락된 원 SALE의 근거 행과 같아야 한다(재리뷰 n-4).
 */
data class CancellationEvidence(
    val entryId: String,
    val originalAuthorizationEntryId: String?,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val cancelledAmountMinor: Long,
    val currency: String,
    val institutionTransactionDate: LocalDate,
)
