package com.paycore.simulator.acquirer.domain

import java.time.LocalDate

/**
 * 가상 VAN 원장의 승인 사실(매입 대조용 — 기관 거래 참조 providerTransactionId로 찾은 APPROVED 결정 하나). [approvedCancelSumMinor] = 그 원승인에 VAN이 승인한
 * 취소 금액 합(상계 금액 대조 — D-ACQ-002 상계는 VAN이 승인한 취소 안에서만 가능하다). [entryId]는 판정 근거로 기록한다.
 */
data class AuthorizationEvidence(
    val entryId: String,
    val providerTransactionId: String,
    val approvalNumber: String,
    val amountMinor: Long,
    val currency: String,
    val institutionTransactionDate: LocalDate,
    val approvedCancelSumMinor: Long,
)
