package com.paycore.financial.acquiring.application.required

/**
 * 매입 적체·격리 지표용 읽기 전용 count Port(Observability·Reconciliation 입력). Coordinator 결정 C-2(financial-batch jOOQ codegen 미도입)에 따라 구현은
 * JPA native count(adapter.persistence — `JpaAcquisitionBacklogReader`)다. 이름은 CQRS 조회 Port 관례(`…Reader`)를 따른다 — 복잡 조회가 생겨 jOOQ로 바꿔도 Port는 그대로다.
 * 업무 판정에 쓰지 않는다(판정은 잠금 아래 권위 상태 — TECH §4). 호출자 TX 필수(읽기).
 */
interface AcquisitionBacklogReader {
    fun read(): AcquisitionBacklogCounts
}

/**
 * [transmissionUnknown]·[transmissionQuarantined] = 그 전송 상태 파일 수, [uploadFailedRepeated] = 아직 전송 완료가 아닌데 UPLOAD_FAILED 결과가 [UPLOAD_FAILED_REPEAT]번
 * 이상인 파일 수(임시 파일 상한 거절 반복 의심 — §16 m-6), [recordsUnknown]·[recordsConflict] = 현재 상태가 UNKNOWN·CONFLICT인 레코드 수, [responsesQuarantined] = 격리된
 * 응답 파일 수, [receiptConflicts] = 수신 축 RECEIPT_CONFLICT 파일 수, [nettingMismatches] = 상계 기록 합 ≠ 레코드 상계액(SALE) 또는 제외 승인액 ≠ 상계 합인 건수(대사 입력
 * 무결성 — 0이어야 한다), [awaitingResults] = 결과 대기 레코드 수.
 */
data class AcquisitionBacklogCounts(
    val transmissionUnknown: Long,
    val transmissionQuarantined: Long,
    val uploadFailedRepeated: Long,
    val recordsUnknown: Long,
    val recordsConflict: Long,
    val responsesQuarantined: Long,
    val receiptConflicts: Long,
    val nettingMismatches: Long,
    val awaitingResults: Long,
    /** (rev 1.1 — 리뷰 m-1) 원 SALE이 나중에 CONFLICT가 된 "취소 매입 불요" 기록 수(판정 근거가 흔들린 취소 — 대사 입력). */
    val cancelNotRequiredOriginalConflict: Long,
) {
    companion object {
        const val UPLOAD_FAILED_REPEAT = 3
    }
}
