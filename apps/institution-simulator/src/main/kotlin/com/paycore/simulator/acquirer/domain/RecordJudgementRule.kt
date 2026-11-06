package com.paycore.simulator.acquirer.domain

/**
 * 레코드 판정 규칙(가상 매입사 — ACQUIRING-001 §7, Implementer 해석 I-31 rev 0.7). 기관 쪽 대조 기준은 가상 VAN 원장이다(D-ACQ-008: merchantRef는 PayCore MID이고
 * 기관 측 대조는 기관 거래 참조로 한다). **이중 매입 방어는 보낸 쪽 거래 ID가 아니라 기관 근거(VAN 원장 행)를 기준으로 한다**(리뷰 MJ-1):
 * - 같은 VAN 승인 행을 가리키는 두 번째 SALE ACCEPTED, 같은 VAN 취소 행을 가리키는 두 번째 CANCEL ACCEPTED → DUPLICATE_RECORD(거래 ID가 달라도).
 * - 원승인별 취소 소비 = 수락된 SALE의 상계액 + 그 원승인으로 수락된 CANCEL 금액 합 ≤ VAN이 승인한 그 원승인 취소 합 → 넘으면 NETTED_CANCEL_MISMATCH
 *   (상계된 취소가 별도 CANCEL로 다시 오는 이중 반영을 막는다).
 * - CANCEL의 원거래 참조는 수락된 원 SALE의 기관 거래 참조와 같아야 한다 → 아니면 CANCELLATION_MISMATCH.
 *
 * 판정 순서(앞의 것이 이긴다):
 * - SALE: 시나리오 REJECT → 같은 거래 ID 이미 ACCEPTED(DUPLICATE_RECORD) → 승인 없음(NO_MATCHING_AUTHORIZATION) → 같은 승인 행 이미 ACCEPTED(DUPLICATE_RECORD)
 *   → 참조·승인번호·거래일(AUTHORIZATION_MISMATCH) → 통화 → 승인액(AMOUNT_MISMATCH) → 상계 + 소비 > VAN 취소 합(NETTED_CANCEL_MISMATCH) → ACCEPTED
 * - CANCEL: 시나리오 REJECT → 같은 거래 ID 이미 ACCEPTED → 원 SALE 미수락(ORIGINAL_NOT_ACQUIRED — D-ACQ-009와 같은 조건) → 원거래 참조 ≠ 수락 SALE 참조
 *   (CANCELLATION_MISMATCH) → 승인된 취소 없음(NO_MATCHING_CANCELLATION) → 같은 취소 행 이미 ACCEPTED(DUPLICATE_RECORD) → 참조·원거래·거래일·
 *   VAN 취소의 원승인 행 ≠ 수락 SALE 근거 행(CANCELLATION_MISMATCH — 재리뷰 n-4) → 통화 → 취소액(AMOUNT_MISMATCH) → 소비 + 취소액 > VAN 취소 합(NETTED_CANCEL_MISMATCH) → ACCEPTED
 * SALE 순액 = 승인액 − 상계(D-ACQ-002)와 CANCEL 금액 ≤ 원승인액은 파일 읽기(RECORD_INVALID)가 이미 보장한다.
 */
object RecordJudgementRule {

    /**
     * @param transactionAccepted 이 매입사에서 같은 거래 ID·SALE이 이미 ACCEPTED
     * @param evidenceAccepted 이 매입사에서 [evidence] 승인 행으로 SALE이 이미 ACCEPTED
     * @param consumedCancelMinor 그 원승인에 이미 소비된 취소(수락 SALE 상계 + 수락 CANCEL)
     */
    fun judgeSale(
        record: ReceivedRecord, scenario: AcquirerScenarioRule?, transactionAccepted: Boolean, evidence: AuthorizationEvidence?, evidenceAccepted: Boolean,
        consumedCancelMinor: Long,
    ): RecordJudgement {
        require(record.type == ReceivedRecordType.SALE) { "a SALE record is judged against an authorization" }
        common(scenario, transactionAccepted)?.let { return it }
        val e = evidence ?: return rejected(NO_MATCHING_AUTHORIZATION, null)
        if (evidenceAccepted) return rejected(DUPLICATE_RECORD, e.entryId)
        val reason = when {
            e.providerTransactionId != record.providerTransactionId -> AUTHORIZATION_MISMATCH
            e.approvalNumber != record.approvalNumber || e.institutionTransactionDate != record.institutionTransactionDate -> AUTHORIZATION_MISMATCH
            e.currency != record.currency -> CURRENCY_MISMATCH
            e.amountMinor != record.approvedAmountMinor -> AMOUNT_MISMATCH
            record.nettedCancelAmountMinor + consumedCancelMinor > e.approvedCancelSumMinor -> NETTED_CANCEL_MISMATCH
            else -> null
        }
        return reason?.let { rejected(it, e.entryId) } ?: RecordJudgement(RecordResult.ACCEPTED, null, e.entryId, e.entryId)
    }

    /**
     * @param originalSale 이 매입사가 수락한 원 SALE(없으면 null)
     * @param evidenceAccepted 이 매입사에서 [evidence] 취소 행으로 CANCEL이 이미 ACCEPTED
     * @param consumedCancelMinor 원승인에 이미 소비된 취소(수락 SALE 상계 + 수락 CANCEL)
     * @param authorizationCancelSumMinor VAN이 그 원승인에 승인한 취소 합
     */
    @Suppress("LongParameterList")
    fun judgeCancel(
        record: ReceivedRecord, scenario: AcquirerScenarioRule?, transactionAccepted: Boolean, originalSale: AcceptedSale?, evidence: CancellationEvidence?,
        evidenceAccepted: Boolean, consumedCancelMinor: Long, authorizationCancelSumMinor: Long,
    ): RecordJudgement {
        require(record.type == ReceivedRecordType.CANCEL) { "a CANCEL record is judged against a cancellation" }
        common(scenario, transactionAccepted)?.let { return it }
        val sale = originalSale ?: return rejected(ORIGINAL_NOT_ACQUIRED, null)
        if (record.originalProviderTransactionId != sale.providerTransactionId) return rejected(CANCELLATION_MISMATCH, null)
        val e = evidence ?: return rejected(NO_MATCHING_CANCELLATION, null)
        if (evidenceAccepted) return rejected(DUPLICATE_RECORD, e.entryId)
        val reason = when {
            e.providerTransactionId != record.providerTransactionId -> CANCELLATION_MISMATCH
            e.originalProviderTransactionId != record.originalProviderTransactionId || e.institutionTransactionDate != record.institutionTransactionDate ->
                CANCELLATION_MISMATCH
            e.originalAuthorizationEntryId != sale.authorizationEntryId -> CANCELLATION_MISMATCH
            e.currency != record.currency -> CURRENCY_MISMATCH
            e.cancelledAmountMinor != record.recordAmountMinor -> AMOUNT_MISMATCH
            consumedCancelMinor + record.recordAmountMinor > authorizationCancelSumMinor -> NETTED_CANCEL_MISMATCH
            else -> null
        }
        return reason?.let { rejected(it, e.entryId) } ?: RecordJudgement(RecordResult.ACCEPTED, null, e.entryId, sale.authorizationEntryId)
    }

    private fun common(scenario: AcquirerScenarioRule?, transactionAccepted: Boolean): RecordJudgement? = when {
        scenario?.action == AcquirerScenarioAction.REJECT -> rejected(checkNotNull(scenario.reason), null)
        transactionAccepted -> rejected(DUPLICATE_RECORD, null)
        else -> null
    }

    private fun rejected(reason: String, entryId: String?) = RecordJudgement(RecordResult.REJECTED, reason, entryId, null)

    const val DUPLICATE_RECORD = "DUPLICATE_RECORD"
    const val NO_MATCHING_AUTHORIZATION = "NO_MATCHING_AUTHORIZATION"
    const val AUTHORIZATION_MISMATCH = "AUTHORIZATION_MISMATCH"
    const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
    const val AMOUNT_MISMATCH = "AMOUNT_MISMATCH"
    const val NETTED_CANCEL_MISMATCH = "NETTED_CANCEL_MISMATCH"
    const val ORIGINAL_NOT_ACQUIRED = "ORIGINAL_NOT_ACQUIRED"
    const val NO_MATCHING_CANCELLATION = "NO_MATCHING_CANCELLATION"
    const val CANCELLATION_MISMATCH = "CANCELLATION_MISMATCH"
}
