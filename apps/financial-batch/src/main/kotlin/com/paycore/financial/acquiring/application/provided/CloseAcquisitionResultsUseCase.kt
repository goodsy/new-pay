package com.paycore.financial.acquiring.application.provided

/**
 * 결과 마감(ACQUIRING-001 §4.1 — 2-F, D-ACQ-004): 전송 완료 뒤 결과 마감 시간(Demo 설정 — LIVE Gate)이 지나도록 결과가 없는 레코드에 UNKNOWN 행을 쓰고 파일 결과 축을
 * DEADLINE_PASSED로 둔다. UNKNOWN은 대사 입력이며 **자동 재전송하지 않는다**. 늦게 온 유효 결과는 응답 수신이 그 뒤에도 반영한다(D-ACQ-010 — UNKNOWN 행 보존).
 * 파일마다 로컬 TX 하나(파일 행 `FOR UPDATE` → 다시 확인 → 결과 행·결과 축). 마감 설정이 없으면 아무것도 하지 않는다(NOT_CONFIGURED).
 */
interface CloseAcquisitionResultsUseCase {
    fun closeExpired(acquirerCode: String, environment: String, limit: Int): CloseRunResult
}

/** [files] = DEADLINE_PASSED(또는 COMPLETE)로 바뀐 파일 수, [unknownRecords] = 새 UNKNOWN 행 수. */
data class CloseRunResult(val outcome: Outcome, val files: Int = 0, val unknownRecords: Int = 0, val errors: Int = 0) {
    enum class Outcome { COMPLETED, NOT_CONFIGURED, LIVE_BLOCKED }
}
