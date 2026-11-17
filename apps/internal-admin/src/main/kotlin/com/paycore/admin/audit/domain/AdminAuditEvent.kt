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
    /**
     * MERCHANT-CODE-001 MC-3a(§5.6.4 — C-32·C-36): 가맹점과 관련된 행의 내부 키(`mer_`). 대상 표기 [targetRef]는 같은 `mer_` 또는 그 시점의 가맹점 코드.
     * V15 CHECK ①~③과 같은 규칙 + ②가 막지 못하는 "내부 키는 있는데 표기 없음"을 여기서 막는다.
     */
    val targetMerchantId: String? = null,
) {
    init {
        require(targetRef == null || (targetActorId == null && TARGET_REF.matches(targetRef))) { "a target reference is a fixed-format id and excludes an account target" }
        require(targetMerchantId == null || MERCHANT_ID.matches(targetMerchantId)) { "a merchant key is mer_ + ULID" }
        require(targetMerchantId == null || (targetRef != null && (targetRef == targetMerchantId || MERCHANT_CODE.matches(targetRef)))) {
            "a merchant key comes with its target reference: the same mer_ or the merchant code"
        }
        require(targetRef == null || !MERCHANT_CODE.matches(targetRef) || targetMerchantId != null) { "a merchant-code target reference needs the merchant key" }
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
            targetMerchantId == other.targetMerchantId && detail == other.detail &&
            (loginNameHmac?.contentEquals(other.loginNameHmac ?: ByteArray(0)) ?: (other.loginNameHmac == null))

    override fun hashCode(): Int = listOf(occurredAt, actorKind, actorId, action, resultCode, correlationId).hashCode()

    private companion object {
        val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        /**
         * 대상 참조: 접두사 3~4자 + ULID(paymentId 등 — 4자는 MERCHANT-OPS-002 C-6 `site_`), 또는 (ACQ-VIEW-001 I-15) 매입 파일 ID(파일 계약 v1 `ACQ.{매입사}.{환경}.{yyyyMMdd}.{vvv}` — 54자 이하, 열 VARCHAR(64)).
         * 둘 다 고정 문법 식별자라 자유 문구가 감사 행에 들어가지 않는다. MERCHANT-CODE-001 MC-3a(§5.1): + 가맹점 코드(§3.1 `[a-z][a-z0-9]{5,13}` — `_`·`.`가 없어
         * 기존 두 형식과 겹치지 않는다, AdminAuditEventTargetRefSpec 표 시험).
         */
        val TARGET_REF = Regex("^([a-z]{3,4}_[0-9A-HJKMNP-TV-Z]{26}|ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}|[a-z][a-z0-9]{5,13})$")
        val MERCHANT_CODE = Regex("^[a-z][a-z0-9]{5,13}$")
        val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
        val DETAIL = Regex("^[a-z][a-zA-Z0-9]*=[A-Za-z0-9_:.,*-]*(;[a-z][a-zA-Z0-9]*=[A-Za-z0-9_:.,*-]*)*$")
        const val HMAC_BYTES = 32
        const val DETAIL_MAX = 500
        const val IP_MAX = 45
        const val UNMASK_ACTION = "UNMASK_REQUESTED"
    }
}

/** 감사 행위자 종류(DB CHECK와 같다). */
enum class AuditActorKind { ADMIN, ANONYMOUS, SYSTEM_BOOTSTRAP, SYSTEM_RECOVERY, SYSTEM_JOB }
