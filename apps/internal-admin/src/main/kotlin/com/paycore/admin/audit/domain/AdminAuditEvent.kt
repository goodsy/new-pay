package com.paycore.admin.audit.domain

import java.time.Instant

/**
 * append-only 관리자 감사 행(§4.6). 행위자 ID + 기록 시점 표시명 스냅샷, 실행자(ADMIN 소유 명령은 승인자 — D-ADMIN1-006), 행위·대상·결과 code·승인 ID·상관 ID·원격 IP.
 * 미인증 시도의 login_name은 비밀 키 HMAC(32바이트)으로만(n-2). 비밀번호·코드·시크릿·세션 ID·CSRF 토큰을 담지 않는다 — [detail]은 고정 형식 요약만(자유 문구 없음).
 */
data class AdminAuditEvent(
    val occurredAt: Instant,
    val actorKind: AuditActorKind,
    val actorId: String?,
    val actorDisplayName: String?,
    val executorActorId: String?,
    val action: String,
    val targetActorId: String?,
    val resultCode: String,
    val approvalId: String?,
    val correlationId: String,
    val remoteIp: String?,
    val loginNameHmac: ByteArray?,
    val detail: String?,
    val reason: String? = null,
    /** 계정이 아닌 대상 식별자(ADMIN-PAYMENT-OPS-001 §4.10 — Payment 대상 승인의 paymentId, 형식 고정). 계정 대상과 동시에 두지 않는다. */
    val targetRef: String? = null,
) {
    init {
        require(targetRef == null || (targetActorId == null && TARGET_REF.matches(targetRef))) { "a target reference is a fixed-format id and excludes an account target" }
        require(CODE.matches(action) && CODE.matches(resultCode)) { "audit action and result are fixed upper-case codes" }
        require(correlationId.isNotBlank() && correlationId.length <= 64) { "correlation id must be 1-64 characters" }
        require((actorKind == AuditActorKind.ADMIN) == (actorId != null)) { "an ADMIN audit row has an actor id, other kinds do not" }
        require(loginNameHmac == null || loginNameHmac.size == HMAC_BYTES) { "login name HMAC is 32 bytes" }
        require(detail == null || (detail.length <= DETAIL_MAX && DETAIL.matches(detail))) { "audit detail is a fixed key=value summary" }
        require(remoteIp == null || remoteIp.length <= IP_MAX) { "remote ip is at most 45 characters" }
        require(reason == null || (action == UNMASK_ACTION && reason.isNotBlank() && reason.length <= DETAIL_MAX && reason.none { it.isISOControl() })) {
            "a free-text reason exists only on UNMASK rows (1-500 characters, no control characters)"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AdminAuditEvent && occurredAt == other.occurredAt && actorKind == other.actorKind && actorId == other.actorId && action == other.action &&
            resultCode == other.resultCode && correlationId == other.correlationId && targetActorId == other.targetActorId && targetRef == other.targetRef &&
            detail == other.detail &&
            (loginNameHmac?.contentEquals(other.loginNameHmac ?: ByteArray(0)) ?: (other.loginNameHmac == null))

    override fun hashCode(): Int = listOf(occurredAt, actorKind, actorId, action, resultCode, correlationId).hashCode()

    private companion object {
        val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        val TARGET_REF = Regex("^[a-z]{3}_[0-9A-HJKMNP-TV-Z]{26}$")
        val DETAIL = Regex("^[a-z][a-zA-Z0-9]*=[A-Za-z0-9_:.,*-]*(;[a-z][a-zA-Z0-9]*=[A-Za-z0-9_:.,*-]*)*$")
        const val HMAC_BYTES = 32
        const val DETAIL_MAX = 500
        const val IP_MAX = 45
        const val UNMASK_ACTION = "UNMASK_REQUESTED"
    }
}

/** 감사 행위자 종류(DB CHECK와 같다). */
enum class AuditActorKind { ADMIN, ANONYMOUS, SYSTEM_BOOTSTRAP, SYSTEM_RECOVERY, SYSTEM_JOB }
