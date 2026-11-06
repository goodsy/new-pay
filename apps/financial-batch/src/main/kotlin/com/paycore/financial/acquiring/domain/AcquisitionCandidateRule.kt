package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 매입 후보 판정(봉인 TX 안 — 결제별 직렬화 행 잠금 아래에서 읽은 값으로).
 *
 * 사용자 결정:
 * - D-ACQ-002: 승인 확정(AUTHORIZED 투영) 건만 매입. 전송 전 전액 취소 → 제외, 부분 취소 → 순액(상계). 원거래를 모르는 취소는 보류.
 * - D-ACQ-012: 상계 경계 = membership 확정 시점. 이 판정 시점(봉인 TX)에 FINANCIAL이 이미 받은 취소만 상계한다. 이후 취소는 별도 CANCEL 레코드(D-ACQ-009).
 * - D-ACQ-008: LIVE 매입 차단(기관 계약번호 전달 전).
 *
 * Coordinator 결정(리뷰 MJ-2, 가역 — 사용자 결정 아님): 같은 Payment에 미해제 보류가 하나라도 있으면 그 Payment의 모든 후보는 대기(PAYMENT_HELD).
 * Implementer 해석(사용자 결정 아님): 승인·딸린 취소 자신의 보류는 더 구체적인 사유로 표시(I-4), KRW 이외 통화는 대기(I-5), 순액 음수·통화 불일치는 대기(방어),
 * 파일 v1 문법으로 표현할 수 없는 값(PAYMENT가 확정한 사실이라 수신은 거절하지 않는다)은 그 거래만 대기(UNREPRESENTABLE — 리뷰 MJ-1, lane 전체를 막지 않는다).
 */
object AcquisitionCandidateRule {

    fun decide(candidate: SaleCandidate, cutoff: Instant): CandidateDecision {
        if (candidate.environment == AcquisitionEnvironment.LIVE) return CandidateDecision.Wait(WaitReason.LIVE_BLOCKED)
        if (candidate.approvedAt.isAfter(cutoff)) return CandidateDecision.Wait(WaitReason.AFTER_CUTOFF)
        if (candidate.held) return CandidateDecision.Wait(WaitReason.AUTHORIZATION_HELD)
        if (candidate.cancellations.any { it.held }) return CandidateDecision.Wait(WaitReason.CANCELLATION_HELD)
        if (candidate.paymentHeld) return CandidateDecision.Wait(WaitReason.PAYMENT_HELD)
        if (candidate.currency != AcquisitionRecord.KRW) return CandidateDecision.Wait(WaitReason.UNSUPPORTED_CURRENCY)
        if (candidate.cancellations.any { it.currency != candidate.currency }) return CandidateDecision.Wait(WaitReason.INCONSISTENT)
        val netted = candidate.cancellations.fold(0L) { acc, c -> Math.addExact(acc, c.amountMinor) }
        val net = candidate.amountMinor - netted
        return when {
            net < 0 -> CandidateDecision.Wait(WaitReason.INCONSISTENT)
            net == 0L -> CandidateDecision.Exclude(candidate, ExclusionReason.FULLY_CANCELLED_BEFORE_SUBMISSION, candidate.cancellations, netted)
            else -> {
                val include = CandidateDecision.Include(candidate, candidate.cancellations, netted)
                // 파일 v1로 표현할 수 있는지 레코드를 실제로 만들어 확인한다(레코드 불변식 = 파일 문법). 순번은 봉인 때 다시 매긴다.
                try {
                    include.toRecord(1)
                    include
                } catch (e: AcquisitionContractViolation) {
                    CandidateDecision.Wait(WaitReason.UNREPRESENTABLE)
                }
            }
        }
    }
}

/** 판정 결과. Include·Exclude는 봉인 TX에서 기록되어 종결(membership/제외), Wait는 기록하지 않고 다음 봉인에서 다시 본다. */
sealed interface CandidateDecision {
    data class Include(val sale: SaleCandidate, val netted: List<KnownCancellation>, val nettedAmountMinor: Long) : CandidateDecision {
        /** 파일 순번 [seq]의 SALE 레코드(첫 매입 — generation 0). */
        fun toRecord(seq: Int): AcquisitionRecord = AcquisitionRecord(
            seq = seq, type = AcquisitionRecordType.SALE, transactionId = sale.transactionId, originalTransactionId = null, merchantRef = sale.merchantAccountId,
            approvalNumber = sale.approvalNumber, providerTransactionId = sale.providerTransactionId, originalProviderTransactionId = null,
            institutionTransactionDate = sale.institutionTransactionDate, eventAt = sale.approvedAt, currency = sale.currency,
            approvedAmountMinor = sale.amountMinor, nettedCancelAmountMinor = nettedAmountMinor, recordAmountMinor = sale.amountMinor - nettedAmountMinor,
            planKind = sale.planKind, installmentMonths = sale.installmentMonths, cardType = sale.cardType, issuerCode = sale.issuerCode,
            generation = 0, reacquisitionOf = null,
        )
    }

    data class Exclude(val sale: SaleCandidate, val reason: ExclusionReason, val netted: List<KnownCancellation>, val nettedAmountMinor: Long) : CandidateDecision

    data class Wait(val reason: WaitReason) : CandidateDecision
}

/** 제외 사유(DB CHECK와 같은 집합). */
enum class ExclusionReason { FULLY_CANCELLED_BEFORE_SUBMISSION }

/** 대기 사유(기록하지 않음 — 로그·지표). UNREPRESENTABLE은 error 로그(거래 ID만) 대상이다. */
enum class WaitReason {
    LIVE_BLOCKED,
    AFTER_CUTOFF,
    AUTHORIZATION_HELD,
    CANCELLATION_HELD,
    PAYMENT_HELD,
    UNSUPPORTED_CURRENCY,
    INCONSISTENT,
    UNREPRESENTABLE,

    /** (2-F, CANCEL만) 원 SALE 결과를 기다린다(D-ACQ-009 "대기면 자동 대기"). */
    ORIGINAL_AWAITING_RESULT,
}
