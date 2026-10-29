package com.paycore.payment.payment.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry

/**
 * 결정 지표 `paycore.payment.admin_channel.decision{decision,replayed}`(ADMIN-PAYMENT-OPS-001 §6, I-59 — S9). [decision] = STARTED·REJECTED, [replayed] = true(같은 approvalId의 기록 결정
 * 재생)·false(이번에 기록)·unknown(PAYMENT_NOT_FOUND — Use Case 결과가 재생 여부를 구분하지 않는다, I-134). 파생 거절·NONE은 결정이 아니라 세지 않는다. label은 고정 분류뿐.
 */
object AdminChannelDecisionMetric {
    const val NAME = "paycore.payment.admin_channel.decision"

    fun record(registry: MeterRegistry, decision: String, replayed: String) {
        registry.counter(NAME, "decision", decision, "replayed", replayed).increment()
    }
}
