package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import java.time.Instant

/**
 * account 명령의 감사 행 작성(호출자 TX 안 — 감사 실패 = 행위 실패). 행위자 = 요청자 actor ID + 기록 시점 표시명 스냅샷(§4.6).
 * 결과 code: 성공 `SUCCESS`, 권한 없음 `DENIED`, 업무 거절 `REJECTED`(detail `reason=<code>`). detail에는 비밀값을 넣지 않는다.
 */
internal class AccountAuditWriter(private val recorder: AdminAuditRecorder) {

    fun admin(
        at: Instant,
        requesterId: String,
        requester: AdminAccount?,
        action: String,
        result: String,
        context: AdminRequestContext,
        target: String? = null,
        detail: String? = null,
    ) = recorder.record(
        AuditRecord(
            occurredAt = at, actorKind = "ADMIN", actorId = requesterId, actorDisplayName = requester?.displayName?.value, action = action,
            resultCode = result, correlationId = context.correlationId, targetActorId = target, remoteIp = context.remoteIp, detail = detail,
        ),
    )

    companion object {
        const val SUCCESS = "SUCCESS"
        const val DENIED = "DENIED"
        const val REJECTED = "REJECTED"

        fun reason(code: String) = "reason=$code"
    }
}
