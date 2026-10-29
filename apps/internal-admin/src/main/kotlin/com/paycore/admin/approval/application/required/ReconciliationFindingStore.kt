package com.paycore.admin.approval.application.required

import com.paycore.admin.approval.domain.ReconciliationFindingKind
import java.time.Instant

/**
 * 대사 발견 기록 Port(INSERT-only, UNIQUE(kind, approval_id) — §4.17). 같은 발견이 이미 있으면 [ApprovalPersistenceFailure.DuplicateFinding]
 * (1062는 오류가 아니라 "이미 기록됨" — 호출자가 그 TX를 rollback하고 정상으로 센다). 호출자 TX 안에서만(발견마다 짧은 TX — 1062 뒤 TX를 계속 쓰지 않는다).
 */
interface ReconciliationFindingStore {
    fun record(finding: ReconciliationFindingRecord)

    /** 종류별 기록된 발견 수(지표 `recon.open_findings{kind}` — I-118). 비잠금. */
    fun openCounts(): Map<String, Long>
}

/** [detail]은 고정 분류만(열 이름·발견 종류 — 사유·근거 문구 없음, S8 비노출: 형식이 아니면 만들 수 없다). */
data class ReconciliationFindingRecord(val kind: ReconciliationFindingKind, val approvalId: String, val paymentRef: String?, val detail: String?, val foundAt: Instant) {
    init {
        require(detail == null || CODES.matches(detail)) { "finding detail is column names or codes only" }
    }

    private companion object {
        val CODES = Regex("^[A-Z_]{1,48}(,[A-Z_]{1,48}){0,7}$")
    }
}
