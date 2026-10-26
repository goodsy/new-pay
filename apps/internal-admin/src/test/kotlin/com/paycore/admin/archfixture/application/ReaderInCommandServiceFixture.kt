package com.paycore.admin.archfixture.application

import com.paycore.admin.approval.application.required.ApprovalListFilter
import com.paycore.admin.approval.application.required.ApprovalRequestListReader

/**
 * ArchUnit 위반 fixture(S5 — §5 "Reader 사용처는 조회 서비스뿐", 시험 소스 전용, bean 아님): 명령 서비스가 jOOQ 조회 Port를 판정에 쓴다 —
 * 명령 판정은 잠근 Aggregate(Repository)로만 한다.
 */
@Suppress("unused")
class ReaderInCommandServiceFixture(private val reader: ApprovalRequestListReader) {
    fun decideFromQueryModel(): Boolean = reader.list(ApprovalListFilter(pendingOnly = true, requestedBy = null, limit = 1)).isEmpty()
}
