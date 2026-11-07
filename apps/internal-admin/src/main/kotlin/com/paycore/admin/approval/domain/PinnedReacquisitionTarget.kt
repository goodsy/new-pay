package com.paycore.admin.approval.domain

/**
 * 고정 대상(원 파일 하나 + 레코드 1..50 — D-ACQA-003). [toSnapshotJson]은 결정적 JSON(필드 순서 고정)이며 승인 행 `target_snapshot`(표시용 사본)에도 같은 값을 넣는다. 값은 형식이 제한돼
 * 이스케이프가 필요 없다. 역변환은 저장 adapter가 JSON 열을 필드로 엄격 해석해 한다(B1-A 리뷰 n-1 — 운영에서 쓰지 않던 문자열 정규식 parse를 지움).
 */
data class PinnedReacquisitionTarget(val fileId: String, val items: List<PinnedReacquisitionItem>) {
    init {
        require(ApprovalTarget.AcquisitionFile.FILE_ID.matches(fileId)) { "file id format" }
        require(items.size in 1..MAX_ITEMS) { "1..$MAX_ITEMS records" }
        require(items.map { it.seq }.zipWithNext().all { (a, b) -> a < b }) { "ascending seq without duplicates" }
    }

    val seqs: List<Int> get() = items.map { it.seq }

    fun toSnapshotJson(): String = items.joinToString(",", "{\"fileId\":\"$fileId\",\"items\":[", "]}") {
        "{\"seq\":${it.seq},\"transactionId\":\"${it.transactionId}\",\"recordType\":\"${it.recordType}\",\"generation\":${it.generation},\"status\":\"${it.status}\"," +
            "\"approvedAmountMinor\":${it.approvedAmountMinor},\"cancelledAmountMinor\":${it.cancelledAmountMinor},\"expectedNetMinor\":${it.expectedNetMinor}}"
    }

    companion object {
        const val MAX_ITEMS = 50
    }
}
