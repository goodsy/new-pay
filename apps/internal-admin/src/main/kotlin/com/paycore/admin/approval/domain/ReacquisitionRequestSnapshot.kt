package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * 재매입 요청 고정 값(ACQ-ADMIN-001 B1-A `admin_acquiring_ops_command` — INSERT-only). 승인 요청과 같은 TX에서 한 번 쓰고 바꾸지 않는다. [pinnedTarget]이 승인 TX1 비교의 권위 값.
 * 사유 문구([reason])는 ADMIN에만(I-4 — FINANCIAL에는 사유 코드·근거 참조만), 감사·로그·지표·목록에 넣지 않는다. toString에 근거·사유가 없다.
 */
data class ReacquisitionRequestSnapshot(
    val approvalId: String,
    val originalFileId: String,
    val reasonCode: String,
    val evidenceRef: String,
    val reason: String,
    val pinnedTarget: PinnedReacquisitionTarget,
    val createdAt: Instant,
) {
    init {
        require(ID.matches(approvalId)) { "approval id format" }
        require(pinnedTarget.fileId == originalFileId) { "the pinned target is the commanded file" }
        require(reasonCode in ReacquisitionInputRules.REASON_CODES) { "reason code" }
        require(ReacquisitionInputRules.EVIDENCE.matches(evidenceRef)) { "evidence format" }
        require(!OperatorCancelInputRules.containsCardNumber(evidenceRef)) { "evidence must not look like a card number" }
        require(reason.length in 1..OperatorCancelInputRules.MAX_REASON) { "reason length" }
    }

    override fun toString(): String = "ReacquisitionRequestSnapshot(approvalId=$approvalId, originalFileId=$originalFileId, items=${pinnedTarget.items.size})"

    private companion object {
        val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
