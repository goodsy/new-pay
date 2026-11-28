package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.required.MasterCallResult

/**
 * 사업자 상세 advisory(BIZ-CRIT-APPROVAL-001 §3.6·§5.1 — 화면 판정용, **비권위**: 권위는 MASTER가 사업자 행 잠금 뒤 계약·가맹점 이력으로 다시 판정한다). §1.1 판정표의 화면 복제.
 * - `criticalChangeMode`: `SINGLE`(이력 없는 DRAFT — 1인 + 사유) | `APPROVAL`(그 밖 — 중요정보는 2인).
 * - `subjectCorrection`: `ALLOWED_SINGLE`(이력 없는 DRAFT) | `ALLOWED_WITH_APPROVAL`(이력 없는 REGISTERED — 2인 정정) | `BLOCKED`(이력 있음).
 * 계약 이력 = '계약 진행' 섹션의 최신 사업자 계약 있음(상태 무관 — 접수 건만이면 없음), 가맹점 이력 = 상세 `merchantCount`(종료 포함) > 0. 섹션을 못 읽으면 둘 다 null.
 */
internal object BusinessChangeAdvisory {
    @Suppress("UNCHECKED_CAST")
    fun of(detail: Map<String, Any?>, section: MasterCallResult): Pair<String?, String?> {
        val business = detail["business"] as? Map<String, Any?> ?: return null to null
        if (section !is MasterCallResult.Found) return null to null
        val history = section.document["businessContract"] != null || ((business["merchantCount"] as? Long) ?: 0L) > 0
        val draft = business["registrationStatus"] == "DRAFT"
        val mode = if (draft && !history) "SINGLE" else "APPROVAL"
        val subject = when {
            history -> "BLOCKED"
            draft -> "ALLOWED_SINGLE"
            else -> "ALLOWED_WITH_APPROVAL"
        }
        return mode to subject
    }
}
