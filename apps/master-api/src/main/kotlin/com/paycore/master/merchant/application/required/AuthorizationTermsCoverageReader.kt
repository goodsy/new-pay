package com.paycore.master.merchant.application.required

/** 승인 시작 조건 누락 점검 조회(jOOQ adapter.query, 읽기 전용). */
interface AuthorizationTermsCoverageReader {
    fun overview(now: java.time.Instant): com.paycore.master.merchant.application.provided.TermsCoverageOverview
}
