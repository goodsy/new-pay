package com.paycore.admin.archfixture.adapter.web

import com.paycore.admin.audit.application.provided.AdminAuditQuery
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.provided.AuditViewer

/**
 * ArchUnit 위반 fixture(D-80 — 시험 소스 전용, bean 아님): web adapter가 audit 조회 API를 직접 주입받아 범위(`canViewAll`)를 스스로 정한다 —
 * 감사 조회 범위는 account `AuditViewUseCase`(계정 현재 행)만 계산해야 한다.
 */
@Suppress("unused")
class AuditQueryInWebAdapterFixture(private val query: AdminAuditQuery) {
    fun everything(actorId: String) = query.search(AuditViewer(actorId, "x", canViewAll = true), AuditFilter(), "corr", null)
}
