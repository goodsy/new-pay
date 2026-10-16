package com.paycore.payment.entitlement.application.required

import com.paycore.payment.entitlement.domain.InboxOutcome

/** PAYMENT 권리 지표(명세 §11). label은 분류값만(outcome·결과 종류·reason), MID·merchantId 금지. */
interface EntitlementMetrics {
    fun inboxOutcome(outcome: InboxOutcome)

    fun inboxRejected(kind: String)

    fun eligibility(result: String, reason: String?)

    /** 승인 시작 허용 판정 결과(MASTER-SNAPSHOT-002). label은 결과·reason 분류만. */
    fun authorizationTerms(result: String, reason: String?)
}
