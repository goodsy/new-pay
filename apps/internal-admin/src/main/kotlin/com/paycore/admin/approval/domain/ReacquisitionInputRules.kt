package com.paycore.admin.approval.domain

/**
 * 재매입 요청 입력 규칙(ACQ-ADMIN-001 B1-A §17.5 2 — TX·FINANCIAL 호출 전, 서버는 자르거나 고치지 않는다). 첫 거절 필드와 고정 이유 code(값 원문 없음 — I-111과 같은 방식).
 * - 파일 ID: 파일 계약 v1. 순번: 1..50건, 양수·오름차순·중복 없음(D-ACQA-003, FINANCIAL 파서와 같다).
 * - 사유 코드: 고정 목록(C-9 권장안 — 가역 구현 선택, 의미는 근거와 함께 감사·FINANCIAL 기록). 근거 참조: FINANCIAL 저장 문법. 사유 문구: 운영자 보정 취소와 같은 규칙(카드 번호 패턴·앞뒤 공백 거부).
 */
object ReacquisitionInputRules {
    val REASON_CODES: List<String> = listOf("INSTITUTION_CONFIRMED_ERROR", "DATA_CORRECTED_AT_SOURCE", "INSTITUTION_REQUESTED")
    val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")

    fun problem(fileId: String, seqs: List<Int>, reasonCode: String, evidenceRef: String, reason: String): Pair<String, String>? = when {
        !ApprovalTarget.AcquisitionFile.FILE_ID.matches(fileId) -> "fileId" to "FORMAT"
        seqs.size !in 1..PinnedReacquisitionTarget.MAX_ITEMS -> "seqs" to "COUNT"
        seqs.any { it < 1 } || !seqs.zipWithNext().all { (a, b) -> a < b } -> "seqs" to "ORDER"
        reasonCode !in REASON_CODES -> "reasonCode" to "UNKNOWN_CODE"
        !EVIDENCE.matches(evidenceRef) -> "evidenceRef" to "FORMAT"
        // B1-A 리뷰 M-1: 형식 뒤 카드 번호 패턴 거부(운영자 보정 취소 referenceProblem 선례) — FINANCIAL append-only 기록에 카드 번호가 남지 않게.
        OperatorCancelInputRules.containsCardNumber(evidenceRef) -> "evidenceRef" to OperatorCancelInputRules.CARD_NUMBER_PATTERN
        else -> OperatorCancelInputRules.reasonProblem(reason)?.let { "reason" to it }
    }
}
