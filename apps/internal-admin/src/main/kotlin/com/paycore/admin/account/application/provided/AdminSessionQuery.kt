package com.paycore.admin.account.application.provided

import java.time.Duration

/**
 * 세션 유효성 판정용 계정 현재값(PK 단건 비잠금 읽기 — 요청마다, §4.4.2). 역할은 세션이 아니라 이 현재값으로 판정한다(D-08).
 */
interface AdminSessionQuery {
    fun current(actorId: String): AdminSessionView?

    fun policy(): AdminSessionPolicy
}

data class AdminSessionView(
    val actorId: String,
    val displayName: String,
    val status: String,
    val sessionGeneration: Long,
    val securityVersion: Long,
    val roles: Set<String>,
) {
    val isActive: Boolean get() = status == "ACTIVE"
}

/** 세션 한도(D-SV Demo 값, 운영값 아님): 익명·등록 진행 idle, 비밀번호 단계 idle, 로그인 idle, 로그인 절대 한도. */
data class AdminSessionPolicy(val anonymousIdle: Duration, val passwordStepIdle: Duration, val authenticatedIdle: Duration, val absolute: Duration)
