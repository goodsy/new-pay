package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.provided.ReconciliationFindingQuery
import com.paycore.admin.approval.application.provided.ReconciliationFindingView
import com.paycore.admin.approval.application.provided.ReconciliationOverview
import com.paycore.admin.approval.application.required.ReconciliationFindingListReader
import org.springframework.stereotype.Service

/** [ReconciliationFindingQuery] — 조회자 권한은 계정 현재 행(ACTIVE + SECURITY_ADMIN·APPROVER), 조회는 jOOQ Reader(TX 없음, 최근 [LIMIT]행). */
@Service
class ReconciliationFindingQueryService(
    private val sessions: AdminSessionQuery,
    private val reader: ReconciliationFindingListReader,
    private val memory: ReconciliationRunMemory,
) : ReconciliationFindingQuery {

    override fun overview(viewerActorId: String): ReconciliationOverview? {
        val viewer = sessions.current(viewerActorId)
        if (viewer == null || !viewer.isActive || viewer.roles.none { it in VIEW_ROLES }) return null
        val rows = reader.list(LIMIT + 1)
        val checkpoint = reader.checkpoint()
        return ReconciliationOverview(
            reader.openCounts(),
            rows.take(LIMIT).map { ReconciliationFindingView(it.findingId, it.kind, it.approvalId, it.paymentRef, it.detailCode, it.foundAt) },
            rows.size > LIMIT,
            checkpoint?.lastRunAt,
            checkpoint?.lastFullScanAt,
            memory.lastResult(),
        )
    }

    private companion object {
        const val LIMIT = 200
        val VIEW_ROLES = setOf("SECURITY_ADMIN", "APPROVER")
    }
}
