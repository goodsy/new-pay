package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED

/**
 * Payment 고객 시도 상태(확정 문서 §2). 활성 = REQUESTED·AUTHORIZING·UNKNOWN(REQUESTED 포함 주문당 하나).
 * 취소/환불 상태는 별도 요약이며 이 생명주기를 바꾸지 않는다(AUTHORIZED는 반환 후에도 성공 이력).
 */
enum class PaymentStatus { REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED, EXPIRED }

/**
 * FAILED/EXPIRED Payment의 종료 근거 종류(D-CPD-002, 사용자 확정 2026-10-06).
 *
 * 새 Payment를 허용하는 충분 근거:
 * - FAILED: [INSTITUTION_CONFIRMED_DECLINE] | [PRE_TRANSMISSION_FAILURE_CONFIRMED] | [INSTITUTION_CONFIRMED_NO_EFFECT]
 * - EXPIRED: [NOT_TRANSMITTED_AND_SESSION_DISCARDED] | [INSTITUTION_CONFIRMED_NO_EFFECT]
 *
 * 불충분(새 시도 차단): [TIMEOUT], [INQUIRY_NOT_FOUND], [LEASE_EXPIRED], 근거 없음(빈 목록).
 * 위 목록에 없는 상태·근거 교차 조합(예: FAILED + 미전송·세션 폐기, EXPIRED + 전송 전 실패 확정)은 불충분으로 본다
 * (D-CPD-003 교차 근거 불인정, 사용자 확정 2026-10-06). 근거가 실제로 성립하는지(기관 응답 해석 등)는 AUTH/연동에서 판정한다.
 */
enum class TerminationEvidence(private val sufficientFor: Set<PaymentStatus>) {
    /** 기관이 승인을 확정 거절했다. */
    INSTITUTION_CONFIRMED_DECLINE(setOf(FAILED)),

    /** 기관 전송 전에 실패했음이 확정됐다(미전송). */
    PRE_TRANSMISSION_FAILURE_CONFIRMED(setOf(FAILED)),

    /** 기관이 원 시도의 무효과 종결을 확인했다. */
    INSTITUTION_CONFIRMED_NO_EFFECT(setOf(FAILED, EXPIRED)),

    /** 기관에 전송하지 않았고 재사용 가능한 인증/세션이 폐기됐다. */
    NOT_TRANSMITTED_AND_SESSION_DISCARDED(setOf(EXPIRED)),

    /** 응답 시간 초과. 금융효과 없음의 근거가 아니다. */
    TIMEOUT(emptySet()),

    /** 원거래 조회 결과 NOT_FOUND. 기관 조회 지연·최종성 규약 확인 전에는 무효과 근거가 아니다. */
    INQUIRY_NOT_FOUND(emptySet()),

    /** 처리 lease 만료. 금융효과 없음의 근거가 아니다. */
    LEASE_EXPIRED(emptySet()),
    ;

    fun isSufficientFor(status: PaymentStatus): Boolean = status in sufficientFor
}
