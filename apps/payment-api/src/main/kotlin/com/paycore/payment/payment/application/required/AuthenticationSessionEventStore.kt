package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationSessionId
import java.time.Instant

/** 인증 세션 결정 로그 한 건(원문 비밀·기관 메시지 없음 — 고정 분류만). */
data class AuthenticationSessionEventRecord(
    val sessionId: AuthenticationSessionId,
    val kind: AuthenticationEventKind,
    val detail: AuthenticationEventDetail?,
    val occurredAt: Instant,
    val correlationId: String,
) {
    init {
        require(correlationId.isNotEmpty() && correlationId.length <= 128) { "correlationId must be 1..128 characters" }
    }
}

enum class EventAppendResult {
    APPENDED,

    /** 반복 가능한 종류(DUPLICATE_RESULT·IGNORED_LATE_RESULT·REJECTED_RESULT·LOOKUP_UNVERIFIABLE)의 같은 (종류, 상세)가 이미 있다 — 행을 더 쓰지 않는다(§7.6). */
    ALREADY_RECORDED,
}

/**
 * 인증 세션 결정 로그 Store(append-only, CONVENTIONS §14 Store ①). 호출자 TX에서 payment → 세션 잠금을 쥔 채 부른다 — seq = max + 1을 그 잠금이 직렬화한다.
 */
interface AuthenticationSessionEventStore {
    fun append(event: AuthenticationSessionEventRecord): EventAppendResult

    fun findBySession(sessionId: AuthenticationSessionId): List<AuthenticationSessionEventRecord>

    companion object {
        val REPEATABLE_KINDS: Set<AuthenticationEventKind> = setOf(
            AuthenticationEventKind.DUPLICATE_RESULT,
            AuthenticationEventKind.IGNORED_LATE_RESULT,
            AuthenticationEventKind.REJECTED_RESULT,
            AuthenticationEventKind.LOOKUP_UNVERIFIABLE,
        )
    }
}
