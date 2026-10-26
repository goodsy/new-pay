package com.paycore.admin.account.domain

import java.time.Instant

/**
 * append-only 역할 이벤트(§4.2.2 `admin_role_event` — 이력 원천). 계정 행의 현재 활성·정지 역할 집합은 같은 TX에서 이 이벤트와 함께 바뀐다.
 * [cause]는 정지의 원인(재설정 승인 ID 또는 `episode:<n>`) — 재활성은 같은 원인으로 정지된 역할만 되살린다(§4.7.7·§4.9.2).
 */
data class RoleEvent(
    val target: AdminActorId,
    override val role: AdminRole,
    override val type: RoleEventType,
    val actor: RoleEventActor,
    val approvalId: String?,
    override val cause: String?,
    val occurredAt: Instant,
) : RoleChange

enum class RoleEventType { GRANT, REVOKE, SUSPEND, REACTIVATE }

/** 역할 이벤트를 만든 주체. 시스템 주체는 1회성 비웹 명령(부트스트랩·복구 — §4.9)뿐이다. */
sealed interface RoleEventActor {
    data class Admin(val actorId: AdminActorId) : RoleEventActor
    data object SystemBootstrap : RoleEventActor
    data object SystemRecovery : RoleEventActor
}
