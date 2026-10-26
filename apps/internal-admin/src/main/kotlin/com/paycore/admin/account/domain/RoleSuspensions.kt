package com.paycore.admin.account.domain

/** 역할 이벤트 이력의 한 변화(역할·종류·정지 원인)만 — 명령 쪽 [RoleEvent]와 조회 쪽 행이 같은 판정을 쓰게 한다. */
interface RoleChange {
    val role: AdminRole
    val type: RoleEventType
    val cause: String?
}

/**
 * 원인별 "아직 그 원인으로 정지된" 역할(§4.7.7·§4.9.2): 역할마다 **마지막** 이벤트가 그 원인의 SUSPEND일 때만(그 뒤 REVOKE·REACTIVATE가 있으면 아님).
 * `ROLE_REACTIVATE` 실행(정지 역할 결정)과 원인 후보 조회(Q-S7-2 — 화면이 원인을 자유 입력받지 않게)가 같은 판정을 쓴다.
 */
object RoleSuspensions {
    /** [history]는 발생 순서(event_id 오름차순). 결과는 원인 → 역할 집합(원인 이름 순). */
    fun byCause(history: List<RoleChange>): Map<String, Set<AdminRole>> =
        history.groupBy { it.role }.values.mapNotNull { events -> events.last().takeIf { it.type == RoleEventType.SUSPEND && it.cause != null } }
            .groupBy({ requireNotNull(it.cause) }, { it.role }).mapValues { (_, roles) -> roles.toSortedSet() }.toSortedMap()
}
