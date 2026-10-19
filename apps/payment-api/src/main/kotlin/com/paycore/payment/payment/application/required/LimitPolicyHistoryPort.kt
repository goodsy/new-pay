package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.TransactionLimitPolicy

/**
 * 원 Authorization에 고정된 한도 정책 값(AUTH-001 AU-29, MS2 §6.4 도출·AC 15): 늦은 승인의 FORCED 초과 표시는 **지금 정책이 아니라** 그 승인이 시작될 때 고정된
 * `limitPolicyVersion`의 값을 쓴다. revision별 권리 Snapshot(불변 행)에서 읽는다. 읽을 수 없으면 null(초과 여부 UNDETERMINED — 기존 domain).
 */
fun interface LimitPolicyHistoryPort {
    fun policyOf(authorization: PaymentAuthorization): TransactionLimitPolicy?
}
