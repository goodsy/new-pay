package com.paycore.admin.approval.application.required

import java.time.Duration
import java.time.Instant

/** 승인 모듈의 시계·ID·Demo 수치(D-SV — 승인 요청 유효 24시간, 운영값 아님). */
interface ApprovalEnvironment {
    fun now(): Instant

    /** `apr_` + ULID. */
    fun newApprovalId(): String

    val validity: Duration

    /** 근거·사유 권한 조회의 조회자별 분당 상한(D-OPS-010 — Demo 값, I-131). 초과는 429·감사 없음. */
    val justificationPerMinute: Int
}
