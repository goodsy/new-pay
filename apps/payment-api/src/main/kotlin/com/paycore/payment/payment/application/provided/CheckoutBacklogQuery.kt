package com.paycore.payment.payment.application.provided

/** 결제창·인증 적체 관찰(AUTHN-001 §15 — gauge 전용, 비권위). 지금 시각 기준 수다. */
interface CheckoutBacklogQuery {
    fun backlog(): CheckoutBacklog
}
