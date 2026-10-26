package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.RoleChange
import com.paycore.admin.account.domain.RoleEventType

/** 역할 이벤트 이력 조회(jOOQ — CQRS 조회 Port, 조회 서비스만 쓴다). 쓰기·잠금 없음. */
interface AdminRoleEventReader {
    /** [target]의 역할 변화(event_id 오름차순)만 — 행위자·승인 ID 등은 읽지 않는다. */
    fun changes(target: AdminActorId): List<RoleChangeRow>
}

data class RoleChangeRow(override val role: AdminRole, override val type: RoleEventType, override val cause: String?) : RoleChange
