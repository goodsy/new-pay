package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.TerminationEvidence.INQUIRY_NOT_FOUND
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT
import com.paycore.payment.payment.domain.TerminationEvidence.LEASE_EXPIRED
import com.paycore.payment.payment.domain.TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED
import com.paycore.payment.payment.domain.TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
import com.paycore.payment.payment.domain.TerminationEvidence.TIMEOUT

/**
 * 시험 oracle: D-CPD-002(사용자 확정 2026-10-06)의 안전 종료 근거 표를 시험 안에 독립적으로 적는다.
 * 운영 코드의 판정 함수로 기대값을 계산하지 않는다.
 *
 * - FAILED: 기관 확정 거절 | 전송 전 실패 확정(미전송) | 기관 무효과 종결 확인
 * - EXPIRED: (미전송 AND 인증/세션 폐기) | 기관 무효과 종결 확인
 * - timeout·조회 NOT_FOUND·lease 만료·근거 없음: 불충분
 * - 표에 없는 교차 조합(EXPIRED + 전송 전 실패 확정만, FAILED + 미전송·세션 폐기 등): 불충분(D-CPD-003 교차 근거 불인정, 사용자 확정 2026-10-06)
 */
object TerminationEvidenceOracle {
    val sufficient: Map<PaymentStatus, Set<TerminationEvidence>> = mapOf(
        FAILED to setOf(INSTITUTION_CONFIRMED_DECLINE, PRE_TRANSMISSION_FAILURE_CONFIRMED, INSTITUTION_CONFIRMED_NO_EFFECT),
        EXPIRED to setOf(NOT_TRANSMITTED_AND_SESSION_DISCARDED, INSTITUTION_CONFIRMED_NO_EFFECT),
    )

    val namedInsufficient: Set<TerminationEvidence> = setOf(TIMEOUT, INQUIRY_NOT_FOUND, LEASE_EXPIRED)

    /** 근거 없음(null)을 포함한 모든 근거 경우. */
    val evidenceOrNone: List<TerminationEvidence?> = listOf(null) + TerminationEvidence.entries

    fun isSufficient(status: PaymentStatus, evidence: TerminationEvidence?): Boolean =
        evidence != null && evidence in sufficient[status].orEmpty()

    /** 이 상태에는 불충분하지만 다른 종료 상태에는 충분한 근거(표 밖 교차 조합, D-CPD-003). */
    fun isSufficientForAnotherStatus(status: PaymentStatus, evidence: TerminationEvidence?): Boolean =
        evidence != null && sufficient.any { (other, set) -> other != status && evidence in set }

    fun label(evidence: TerminationEvidence?): String = evidence?.name ?: "NO_EVIDENCE"
}
