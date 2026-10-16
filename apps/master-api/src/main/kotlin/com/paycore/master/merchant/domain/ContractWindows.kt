package com.paycore.master.merchant.domain

/**
 * 계약 적용 구간 계산(명세 §3.1 contractWindows, D-MD-011·015, n-5): 시각 t의 적용 Revision = t를 포함하는 승인 Revision 중 번호 최대.
 * 적용 여부는 승인 Revision 기간 경계에서만 바뀌므로 경계로 나눈 각 구간의 시작 시각에서 판정하고, Scope가 [site]를 포함하는 구간을 이어 붙인다.
 * 철회 기록(MASTER-LIFECYCLE-001)이 생기면 그 Revision을 제외하도록 이 함수를 확장한다.
 */
object ContractWindows {
    fun of(contract: Contract, site: MerchantSite): List<ContractWindow> {
        val approved = contract.revisions.filter { it.isApproved }
        val boundaries = approved.flatMap { listOfNotNull(it.effectivePeriod.from, it.effectivePeriod.to) }.distinct().sorted()
        val windows = mutableListOf<ContractWindow>()
        boundaries.forEachIndexed { i, start ->
            val end = boundaries.getOrNull(i + 1)
            val applicable = contract.applicableRevisionAt(start)
            if (applicable != null && applicable.scope.includes(site)) {
                val last = windows.lastOrNull()
                if (last != null && last.to == start) {
                    windows[windows.size - 1] = ContractWindow(site.id, last.from, end)
                } else {
                    windows += ContractWindow(site.id, start, end)
                }
            }
        }
        return windows
    }
}
