package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 전송 후 취소(membership 확정 뒤 받은 취소 — D-ACQ-012, 상계 기록 없음)의 봉인 판정(2-F — D-ACQ-009를 봉인 TX에 연결). 결제별 직렬화 행 잠금 아래에서 읽은 값으로 부른다.
 *
 * 사용자 결정 D-ACQ-009: 원 SALE이 ACCEPTED일 때만 CANCEL 레코드. 결과 대기 → 자동 대기, UNKNOWN → 대사·경보(보내지 않음), REJECTED → "취소 매입 불요" 기록([CancelRecordRule]).
 *
 * Implementer 해석(사용자 결정 아님):
 * - I-47 (리뷰 NIT-3): SALE 후보와 같은 거래 단위 대기를 CANCEL에도 적용한다 — LIVE·마감 뒤·보류(취소 자신·같은 Payment)·KRW 외 통화·원거래와 어긋난 값(통화·원 기관
 *   거래번호·MID·누적 금액)·파일 v1로 표현할 수 없는 값(UNREPRESENTABLE)은 그 취소만 대기(기록 없음), lane은 계속한다. 보류·비정합은 원 SALE 결과보다 먼저 본다(fail-closed).
 * - I-48: CANCEL 레코드의 승인번호 칸 = 원승인 승인번호(취소 승인번호는 VAN이 주지 않을 수 있다 — PAYMENT 투영 cancel_approval_number NULL 허용, golden vector 3과 같은 값).
 *   승인액 칸 = 원승인액, 기관 거래일·시각 = 취소의 값, 할부·카드종류·발급사 = 원승인 값.
 * - 누적 한도 방어: 원 SALE 상계액 + 이미 봉인한 CANCEL 금액 + 이 취소 ≤ 원승인액(투영 규칙이 이미 지키지만 매입 쪽에서 다시 확인 — 넘으면 INCONSISTENT 대기).
 */
object CancelCandidateRule {

    fun decide(candidate: CancelCandidate, originalResult: OriginalSaleResult, cutoff: Instant): CancelDecision {
        val o = candidate.original
        if (o.environment == AcquisitionEnvironment.LIVE) return CancelDecision.Wait(WaitReason.LIVE_BLOCKED)
        if (candidate.cancelledAt.isAfter(cutoff)) return CancelDecision.Wait(WaitReason.AFTER_CUTOFF)
        if (candidate.held) return CancelDecision.Wait(WaitReason.CANCELLATION_HELD)
        if (o.paymentHeld) return CancelDecision.Wait(WaitReason.PAYMENT_HELD)
        if (candidate.currency != AcquisitionRecord.KRW) return CancelDecision.Wait(WaitReason.UNSUPPORTED_CURRENCY)
        if (!consistent(candidate)) return CancelDecision.Wait(WaitReason.INCONSISTENT)
        return when (CancelRecordRule.decide(originalResult)) {
            CancelRecordDecision.WAIT -> CancelDecision.Wait(WaitReason.ORIGINAL_AWAITING_RESULT)
            CancelRecordDecision.RECONCILE -> CancelDecision.Reconcile(candidate, originalResult)
            CancelRecordDecision.NOT_REQUIRED -> CancelDecision.NotRequired(candidate)
            CancelRecordDecision.INCLUDE -> {
                val include = CancelDecision.Include(candidate)
                try {
                    include.toRecord(1)
                    include
                } catch (e: AcquisitionContractViolation) {
                    CancelDecision.Wait(WaitReason.UNREPRESENTABLE)
                }
            }
        }
    }

    private fun consistent(c: CancelCandidate): Boolean {
        val o = c.original
        val sale = c.originalSale
        if (c.currency != o.currency || c.originalProviderTransactionId != o.providerTransactionId || c.merchantAccountId != o.merchantAccountId) return false
        if (sale.type != AcquisitionRecordType.SALE || sale.generation != 0 || sale.transactionId != o.transactionId || sale.approvedAmountMinor != o.amountMinor) return false
        if (c.alreadyCancelledMinor < 0) return false
        val used = runCatching { Math.addExact(Math.addExact(sale.nettedCancelAmountMinor, c.alreadyCancelledMinor), c.amountMinor) }.getOrNull() ?: return false
        return used <= o.amountMinor
    }
}

/**
 * 전송 후 취소 후보. [original] = 원승인 투영(원승인 고정값 — [SaleCandidate.paymentHeld]는 같은 Payment의 미해제 보류), [originalSale] = 원승인의 첫 매입 SALE 레코드
 * (membership — 이것이 없으면 후보가 아니다)와 그 파일 [originalSaleFileId], [alreadyCancelledMinor] = 그 원승인으로 이미 봉인한 CANCEL 레코드 금액 합, [held] = 이 취소 자신의 미해제 보류.
 */
data class CancelCandidate(
    val transactionId: String,
    val original: SaleCandidate,
    val originalSaleFileId: AcquisitionFileId,
    val originalSale: AcquisitionRecord,
    val alreadyCancelledMinor: Long,
    val merchantAccountId: String,
    val amountMinor: Long,
    val currency: String,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val cancelledAt: Instant,
    val held: Boolean,
) {
    init {
        if (amountMinor < 1) throw AcquisitionContractViolation("a cancellation amount is a positive minor-unit integer")
        if (held && !original.paymentHeld) throw AcquisitionContractViolation("a held cancellation makes its payment held")
    }
}

/** 판정 결과. Include = 이 봉인의 CANCEL 레코드, NotRequired = "취소 매입 불요" 기록(종결), Reconcile = 대사·경보(기록 없음, 다음 봉인에서 다시 판정), Wait = 대기. */
sealed interface CancelDecision {
    data class Include(val candidate: CancelCandidate) : CancelDecision {
        /** 파일 순번 [seq]의 CANCEL 레코드(첫 매입 — generation 0, I-48). */
        fun toRecord(seq: Int): AcquisitionRecord {
            val o = candidate.original
            return AcquisitionRecord(
                seq = seq, type = AcquisitionRecordType.CANCEL, transactionId = candidate.transactionId, originalTransactionId = o.transactionId,
                merchantRef = o.merchantAccountId, approvalNumber = o.approvalNumber, providerTransactionId = candidate.providerTransactionId,
                originalProviderTransactionId = candidate.originalProviderTransactionId, institutionTransactionDate = candidate.institutionTransactionDate,
                eventAt = candidate.cancelledAt, currency = candidate.currency, approvedAmountMinor = o.amountMinor, nettedCancelAmountMinor = 0,
                recordAmountMinor = candidate.amountMinor, planKind = o.planKind, installmentMonths = o.installmentMonths, cardType = o.cardType,
                issuerCode = o.issuerCode, generation = 0, reacquisitionOf = null,
            )
        }
    }

    data class NotRequired(val candidate: CancelCandidate) : CancelDecision

    data class Reconcile(val candidate: CancelCandidate, val originalResult: OriginalSaleResult) : CancelDecision

    data class Wait(val reason: WaitReason) : CancelDecision
}
