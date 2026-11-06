package com.paycore.simulator.acquirer.domain

enum class RecordResult { ACCEPTED, REJECTED }

/**
 * 가상 매입사의 레코드 판정(기관 내부 사실 — append-only 기록). ACCEPTED = 매입 확정(받는 쪽 인정 시각은 결과 수신 시각, D-ACQ-010), REJECTED = 그 파일에서 종결
 * (D-ACQ-004). [reason]은 REJECTED에만 있다(가상 코드 `[A-Z0-9_]{1,32}`).
 * - [evidenceEntryId] = 이 레코드를 대조한 가상 VAN 원장 행(SALE = 승인 행, CANCEL = 취소 행). 이중 매입 방어 키다(리뷰 MJ-1 — 같은 근거 행의 두 번째 ACCEPTED 금지).
 * - [authorizationEntryId] = 이 레코드가 속한 원승인 VAN 행(SALE = 자기 근거, CANCEL = 수락된 원 SALE의 근거). 원승인별 취소 소비(상계 + CANCEL) 누적 한도의 키다.
 * ACCEPTED는 두 값이 모두 있다.
 */
data class RecordJudgement(val result: RecordResult, val reason: String?, val evidenceEntryId: String?, val authorizationEntryId: String? = evidenceEntryId) {
    init {
        require((result == RecordResult.REJECTED) == (reason != null)) { "a reason exists exactly for REJECTED" }
        reason?.let { require(TOKEN.matches(it)) { "reason must be 1..32 of [A-Z0-9_]" } }
        if (result == RecordResult.ACCEPTED) require(evidenceEntryId != null && authorizationEntryId != null) { "an acceptance names its institution evidence" }
    }

    private companion object {
        val TOKEN = Regex("^[A-Z0-9_]{1,32}$")
    }
}
