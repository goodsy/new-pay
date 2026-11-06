package com.paycore.financial.acquiring.domain

/**
 * 전송 후 취소(membership 확정 뒤 받은 취소 — D-ACQ-012)의 CANCEL 레코드 판정(D-ACQ-009 — 사용자 결정 2026-11-05):
 * 원 SALE 레코드가 **ACCEPTED일 때만** 다음 정기 파일에 CANCEL을 넣는다. 결과 대기면 자동 대기, UNKNOWN이면 대사·경보, REJECTED면 "취소 매입 불요" 기록.
 * 상충(CONFLICT — 같은 레코드에 다른 결과, 격리 상태)은 Implementer 해석으로 대사(fail-closed — 자동으로 보내지 않는다).
 *
 * 배치1은 규칙만 두었다. 2-F부터 봉인 TX가 [CancelCandidateRule]을 통해 이 규칙을 부른다(원 SALE 결과 = [RecordResultRule.toOriginalSaleResult]).
 */
object CancelRecordRule {
    fun decide(originalSaleResult: OriginalSaleResult): CancelRecordDecision = when (originalSaleResult) {
        OriginalSaleResult.ACCEPTED -> CancelRecordDecision.INCLUDE
        OriginalSaleResult.AWAITING -> CancelRecordDecision.WAIT
        OriginalSaleResult.UNKNOWN, OriginalSaleResult.CONFLICT -> CancelRecordDecision.RECONCILE
        OriginalSaleResult.REJECTED -> CancelRecordDecision.NOT_REQUIRED
    }
}

/** 원 SALE 레코드의 현재 결과(배치2 결과 규칙이 계산한다). */
enum class OriginalSaleResult { AWAITING, ACCEPTED, REJECTED, UNKNOWN, CONFLICT }

/** INCLUDE = 다음 정기 파일 CANCEL 레코드, WAIT = 자동 대기(기록 없음), RECONCILE = 대사 입력·경보(보내지 않음), NOT_REQUIRED = "취소 매입 불요" 기록. */
enum class CancelRecordDecision { INCLUDE, WAIT, RECONCILE, NOT_REQUIRED }
