package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole

/** 요청마다 세션 유효성을 판정하는 계정 현재값(PK 단건 비잠금 읽기, jOOQ — §4.4.2). */
interface AdminSessionReader {
    fun find(actorId: AdminActorId): SessionAccountRow?
}

data class SessionAccountRow(
    val actorId: AdminActorId,
    val displayName: String,
    val status: AccountStatus,
    val sessionGeneration: Long,
    val securityVersion: Long,
    val activeRoles: Set<AdminRole>,
)
