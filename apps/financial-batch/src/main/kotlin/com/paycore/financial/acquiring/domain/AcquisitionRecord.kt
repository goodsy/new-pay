package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 매입 파일 레코드 한 줄(파일 계약 v1 §5.2 `D|…`). 값 불변식은 생성 시 검사한다:
 * - SALE: 원거래 참조 없음, `recordAmount = approvedAmount − nettedCancelAmount ≥ 1`(D-ACQ-002 상계 — 순액 0이면 레코드가 아니라 제외 기록).
 * - CANCEL: 원거래·원 기관 거래번호 필수(D-ACQ-002 "원거래 참조"), 상계 없음, `1 ≤ recordAmount ≤ approvedAmount`(approvedAmount = 원승인액).
 * - 통화는 KRW만(사용자 지정 "원화 정수" — Implementer 해석 I-5), 금액은 minor unit 정수.
 * - [merchantRef] = PayCore MID(D-ACQ-008 — Demo. 실제 기관 계약번호는 후속 PaymentAuthorized v2, 그 전 LIVE 매입 차단).
 * - [generation] 0 = 첫 매입, 1 이상 = 재매입(원 레코드 참조 [reacquisitionOf] 필수 — D-ACQ-004·D-ACQ-011, 실행은 배치2).
 */
data class AcquisitionRecord(
    val seq: Int,
    val type: AcquisitionRecordType,
    val transactionId: String,
    val originalTransactionId: String?,
    val merchantRef: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val originalProviderTransactionId: String?,
    val institutionTransactionDate: LocalDate,
    val eventAt: Instant,
    val currency: String,
    val approvedAmountMinor: Long,
    val nettedCancelAmountMinor: Long,
    val recordAmountMinor: Long,
    val planKind: String,
    val installmentMonths: Int?,
    val cardType: String,
    val issuerCode: String,
    val generation: Int,
    val reacquisitionOf: String?,
) {
    init {
        if (seq < 1) throw AcquisitionContractViolation("record seq must be at least 1")
        AcquisitionText.id("transactionId", transactionId)
        AcquisitionText.id("merchantRef", merchantRef)
        AcquisitionText.id("approvalNumber", approvalNumber)
        AcquisitionText.id("providerTransactionId", providerTransactionId)
        AcquisitionText.token("planKind", planKind)
        AcquisitionText.token("cardType", cardType)
        // 리뷰 NIT-2(2-F): 토큰 문법(1..32자)이 DB 열 폭(card_type VARCHAR(8))보다 넓어 봉인 INSERT가 1406으로 lane 전체를 실패시킬 수 있었다. 투영 CHECK와 같은
        // 열거값만 받는다(그 밖 값은 레코드 불변식 위반 → 후보 판정에서 그 거래만 UNREPRESENTABLE 대기). planKind는 아래 할부 규칙이 열거값(LUMP_SUM·INSTALLMENT)을 지킨다.
        if (cardType !in CARD_TYPES) throw AcquisitionContractViolation("cardType must be one of the acquisition file v1 card types")
        AcquisitionText.code("issuerCode", issuerCode)
        if (currency != KRW) throw AcquisitionContractViolation("acquisition file v1 carries KRW only")
        if (eventAt.nano % NANOS_PER_MILLI != 0) throw AcquisitionContractViolation("eventAt must be truncated to milliseconds")
        if (approvedAmountMinor < 1 || nettedCancelAmountMinor < 0 || recordAmountMinor < 1) {
            throw AcquisitionContractViolation("amounts must be minor-unit integers (approved ≥ 1, netted ≥ 0, record ≥ 1)")
        }
        val planValid = (planKind == LUMP_SUM && installmentMonths == null) || (planKind == INSTALLMENT && installmentMonths != null && installmentMonths in 2..99)
        if (!planValid) throw AcquisitionContractViolation("plan must be LUMP_SUM without months or INSTALLMENT with 2..99 months")
        when (type) {
            AcquisitionRecordType.SALE -> {
                if (originalTransactionId != null || originalProviderTransactionId != null) {
                    throw AcquisitionContractViolation("a SALE record has no original reference")
                }
                if (recordAmountMinor != approvedAmountMinor - nettedCancelAmountMinor) {
                    throw AcquisitionContractViolation("a SALE record amount is the approved amount minus the netted cancels")
                }
            }
            AcquisitionRecordType.CANCEL -> {
                if (originalTransactionId == null || originalProviderTransactionId == null) {
                    throw AcquisitionContractViolation("a CANCEL record refers to its original transaction")
                }
                AcquisitionText.id("originalTransactionId", originalTransactionId)
                AcquisitionText.id("originalProviderTransactionId", originalProviderTransactionId)
                if (nettedCancelAmountMinor != 0L || recordAmountMinor > approvedAmountMinor) {
                    throw AcquisitionContractViolation("a CANCEL record is not netted and does not exceed the original amount")
                }
            }
        }
        if (generation < 0) throw AcquisitionContractViolation("generation must not be negative")
        if ((generation == 0) != (reacquisitionOf == null)) throw AcquisitionContractViolation("only a re-acquisition record (generation ≥ 1) refers to its original record")
        reacquisitionOf?.let { AcquisitionText.id("reacquisitionOf", it) }
    }

    companion object {
        const val KRW = "KRW"
        const val LUMP_SUM = "LUMP_SUM"
        const val INSTALLMENT = "INSTALLMENT"

        /** 파일 v1 카드 종류(결제 사실 투영 CHECK와 같은 집합 — 실제 통화/체크카드 범위 추가안은 사용자 채택 전). */
        val CARD_TYPES: Set<String> = setOf("CREDIT", "CHECK")
        private const val NANOS_PER_MILLI = 1_000_000
    }
}

/** 레코드 종류(DB CHECK와 같은 집합). */
enum class AcquisitionRecordType { SALE, CANCEL }
