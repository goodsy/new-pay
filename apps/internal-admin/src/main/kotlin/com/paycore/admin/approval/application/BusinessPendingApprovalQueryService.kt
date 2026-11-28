package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.required.BusinessPendingApprovalReader
import com.paycore.admin.merchantops.application.provided.BusinessPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingBusinessChange
import org.springframework.stereotype.Service
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [BusinessPendingApprovals](merchantops 공개 SPI — BIZ-CRIT-APPROVAL-001 §3.4·§5.2) 구현. 대기 표시는 **사업자 조회 권한(OPERATOR·APPROVER)** 보유 조회자에게만 {상태, 요청 시각}(m-2 —
 * VIEWER·SECURITY_ADMIN은 null), 내용·요청자 없음. [hasUnresolved]는 조회자 무관(1인 수정 차단·요청 사전 검사 — advisory, 권위는 UNIQUE·MASTER version). 의존 방향 approval → merchantops.
 */
@Service
class BusinessPendingApprovalQueryService(private val sessions: AdminSessionQuery, private val reader: BusinessPendingApprovalReader) : BusinessPendingApprovals {

    override fun pendingFor(viewerActorId: String, businessId: String): PendingBusinessChange? {
        val mayView = sessions.current(viewerActorId)?.let { it.isActive && it.roles.any { r -> r in VIEW_ROLES } } == true
        if (!mayView || !BUSINESS_ID.matches(businessId)) return null
        return reader.unresolvedFor(businessId)?.let { PendingBusinessChange(it.status, TIME.format(it.requestedAt)) }
    }

    override fun hasUnresolved(businessId: String): Boolean = BUSINESS_ID.matches(businessId) && reader.unresolvedFor(businessId) != null

    private companion object {
        val VIEW_ROLES = setOf("OPERATOR", "APPROVER")
        val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
