package com.paycore.admin.approval.domain

/**
 * 재매입 요청 시점에 고정한 대상 레코드(ACQ-ADMIN-001 B1-A §3.1·§3.2 "대상 버전(pinned)" — 승인 TX1이 FINANCIAL 재조회 값과 비교하는 **권위** 기준). FINANCIAL 미리 보기
 * (`ReacquisitionExecutionRule.preview` — 비권위)에서 온다. [generation]·[status]가 대상 버전이고, 금액 칸은 표시용(예상 순액 — 실행 TX 값이 권위, I-10). 카드사 값 없음(D-ACQV-002).
 */
data class PinnedReacquisitionItem(
    val seq: Int,
    val transactionId: String,
    val recordType: String,
    val generation: Int,
    val status: String,
    val approvedAmountMinor: Long,
    val cancelledAmountMinor: Long,
    val expectedNetMinor: Long,
) {
    init {
        require(seq >= 1) { "seq" }
        require(REF.matches(transactionId)) { "transaction id format" }
        require(recordType in RECORD_TYPES) { "record type" }
        require(generation >= 0) { "generation" }
        require(status in STATUSES) { "pinned status (REJECTED / FILE_REJECTED)" }
        require(approvedAmountMinor >= 0 && cancelledAmountMinor >= 0 && expectedNetMinor >= 0) { "amounts" }
    }

    private companion object {
        val REF = Regex("^[A-Za-z0-9._:#-]{1,64}$")
        val RECORD_TYPES = setOf("SALE", "CANCEL")
        val STATUSES = setOf("REJECTED", "FILE_REJECTED")
    }
}
