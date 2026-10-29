package com.paycore.admin.approval.adapter.web

import com.paycore.admin.approval.application.provided.ReconciliationFindingQuery
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 대사 발견 화면 조회(ADMIN-PAYMENT-OPS-001 §4.20 — SECURITY_ADMIN·APPROVER, 그 밖 403). 발견 행은 code 열만, `no-store`. 해결·종료 endpoint는 없다(LIVE Gate 운영 절차). */
@RestController
class ReconciliationFindingController(private val query: ReconciliationFindingQuery) {

    @GetMapping("/api/reconciliation/findings")
    fun findings(authentication: Authentication): ResponseEntity<out Any> {
        val o = query.overview(authentication.name)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).body(mapOf("error" to "FORBIDDEN"))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            linkedMapOf(
                "openCounts" to o.openCounts, "openTotal" to o.openCounts.values.sum(),
                "findings" to o.findings.map {
                    linkedMapOf(
                        "findingId" to it.findingId, "kind" to it.kind, "approvalId" to it.approvalId, "paymentRef" to it.paymentRef, "detailCode" to it.detailCode,
                        "foundAt" to it.foundAt.toString(),
                    )
                },
                "truncated" to o.truncated, "lastRunAt" to o.lastRunAt?.toString(), "lastFullScanAt" to o.lastFullScanAt?.toString(), "lastResult" to o.lastResult,
            ),
        )
    }
}
