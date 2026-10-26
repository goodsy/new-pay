package com.paycore.admin.account.application.provided

import com.paycore.admin.audit.application.provided.AuditEntry
import com.paycore.admin.audit.application.provided.AuditFilter

/**
 * 감사 조회의 **단일 경로**(S3 리뷰 m-3): 조회자 범위는 세션 actor의 계정 현재 행으로만 계산한다(ACTIVE + `VIEW_ALL_AUDIT`이면 전체, `VIEW_OWN_AUDIT`이면 자기 기록,
 * 둘 다 없으면 거부). 호출자가 넘기는 범위·역할을 받지 않는다. audit 모듈의 조회 API는 이 Use Case만 부른다(ArchUnit).
 */
interface AuditViewUseCase {
    fun search(viewerActorId: String, filter: AuditFilter, context: AdminRequestContext): AuditViewResult
}

sealed interface AuditViewResult {
    data class Entries(val entries: List<AuditEntry>) : AuditViewResult

    data object Denied : AuditViewResult
}
