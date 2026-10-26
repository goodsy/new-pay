package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.RoleEvent

/**
 * append-only 역할 이벤트 기록(§14 Store ① — 결정 로그). 계정 행의 현재 역할 집합과 같은 TX에서 쓴다. 수정·삭제 연산 없음(runtime SELECT·INSERT만).
 */
interface AdminRoleEventStore {
    fun append(events: List<RoleEvent>)

    fun history(target: AdminActorId): List<RoleEvent>
}
