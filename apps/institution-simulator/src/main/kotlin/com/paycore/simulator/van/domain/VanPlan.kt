package com.paycore.simulator.van.domain

enum class VanPlanKind { LUMP_SUM, INSTALLMENT }

/** 할부 계획(의미 필드 — 전문 표현 00/0 등은 PG Adapter 몫, AUTH-001 §5.2). 일시불은 개월 없음, 할부는 2..99개월. */
data class VanPlan(val kind: VanPlanKind, val months: Int?) {
    init {
        require((kind == VanPlanKind.LUMP_SUM && months == null) || (kind == VanPlanKind.INSTALLMENT && months != null && months in 2..99)) {
            "plan must be LUMP_SUM without months or INSTALLMENT with 2..99 months"
        }
    }

    companion object {
        val LUMP_SUM = VanPlan(VanPlanKind.LUMP_SUM, null)

        fun installment(months: Int) = VanPlan(VanPlanKind.INSTALLMENT, months)
    }
}
