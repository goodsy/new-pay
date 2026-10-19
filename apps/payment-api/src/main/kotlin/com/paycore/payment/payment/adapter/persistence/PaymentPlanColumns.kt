package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.PaymentPlan

/** PaymentPlan ↔ (plan_kind, installment_months) 열(V7·V9 CHECK와 같은 값). */
internal object PaymentPlanColumns {
    fun of(plan: PaymentPlan): Pair<String, Int?> = when (plan) {
        PaymentPlan.LumpSum -> "LUMP_SUM" to null
        is PaymentPlan.Installment -> "INSTALLMENT" to plan.months
    }

    fun toPlan(kind: String, months: Int?): PaymentPlan = when (kind) {
        "LUMP_SUM" -> PaymentPlan.LumpSum.also { check(months == null) { "lump sum has no months" } }
        "INSTALLMENT" -> PaymentPlan.Installment(checkNotNull(months) { "installment needs months" })
        else -> error("unknown plan kind")
    }
}
