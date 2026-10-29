package com.paycore.admin.approval.domain

import java.time.Duration
import java.time.Instant

/**
 * 소유 서비스 실행 행(ADMIN-PAYMENT-OPS-001 §4.9·§4.11 `admin_owner_execution`, §4.16 TX2 갱신 규칙 rev 0.3 L-e). 승인 TX1이 만든다.
 * - 불변: 저장 본문 바이트([requestBody] — 재전송은 같은 바이트, 재생성 없음 I-16)·지문·승인 시각·실행 기한·실행자(= 승인자).
 * - [attemptCount] = 보낸 명령(실행 POST·기한 확인) 수: 승인 TX1이 1, 회복 선점 [claimed]만 +1, TX2([unknownResponse]·[ownerResult])는 바꾸지 않는다.
 * - TX2: [lastResponseAt] 기록. UNKNOWN이면 [lastFailure] 갱신·[nextAttemptAt]은 앞으로만(웹 TX2가 job 예정을 당기지 않고 늦은 TX2가 되돌리지 않게). 종료면 둘 다 유지.
 * - [integrityFlag](D-OPS-007)·[resendHaltedReason](D-OPS-006, I-26)은 한 번 서면 바뀌거나 지워지지 않는다. [ownerResultCode]·[ownerReference]는 한 번만.
 * equals/hashCode는 승인 ID만, toString에 본문이 없다(§5 주의).
 */
class OwnerExecution private constructor(
    val approvalId: String,
    private val body: ByteArray,
    val fingerprint: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val executorActorId: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val lastAttemptAt: Instant?,
    val lastResponseAt: Instant?,
    val lastFailure: String?,
    val integrityFlag: String?,
    val resendHaltedReason: String?,
    val ownerResultCode: String?,
    val ownerReference: String?,
) {
    init {
        require(ID.matches(approvalId)) { "approval id format" }
        require(body.isNotEmpty() && body.size <= MAX_BODY_BYTES) { "stored request body size (1..$MAX_BODY_BYTES bytes)" }
        require(FINGERPRINT.matches(fingerprint)) { "oct1 fingerprint format" }
        require(executeBefore.isAfter(approvedAt)) { "the execution deadline is after the approval" }
        require(executorActorId.isNotBlank()) { "executor (= approver)" }
        require(attemptCount >= 1) { "the approval TX1 counts the first command" }
        listOf(lastFailure, integrityFlag, resendHaltedReason, ownerResultCode).forEach { require(it == null || CODE.matches(it)) { "code format" } }
        require(ownerReference == null || REFERENCE.matches(ownerReference)) { "owner reference format (cancellation id)" }
        require(ownerResultCode == null || ownerReference == null) { "an owner result is a reference (STARTED) or a code (REJECTED), not both" }
    }

    /** 저장 본문 사본(바깥에서 바꿀 수 없다). */
    fun requestBody(): ByteArray = body.copyOf()

    /** 회복 선점(§4.16 — S6): 보낸 명령 +1, 마지막 시도 = now, 다음 시도 = now + backoff. 재전송 본문은 저장 바이트 그대로. */
    fun claimed(now: Instant, backoff: Duration): OwnerExecution {
        require(!backoff.isNegative) { "backoff" }
        return copy(attemptCount = attemptCount + 1, lastAttemptAt = now, nextAttemptAt = now.plus(backoff))
    }

    /** TX2 결과 불명확(D-OPS-003): 마지막 응답·실패 분류 기록, 다음 시도는 `max(현재, retryAt)` — 앞으로만(L-e). 시도 수는 바꾸지 않는다. */
    fun unknownResponse(now: Instant, failure: String, retryAt: Instant): OwnerExecution {
        require(CODE.matches(failure)) { "failure code format" }
        return copy(lastResponseAt = now, lastFailure = failure, nextAttemptAt = maxOf(nextAttemptAt, retryAt))
    }

    /**
     * TX2 소유 결과(종료): STARTED면 [reference](cancellationId), 업무 거절이면 [code] — 정확히 하나, 한 번만. 다음 시도·마지막 실패는 유지(L-e — 선점 조건이 상태로 거른다).
     */
    fun ownerResult(now: Instant, code: String?, reference: String?): OwnerExecution {
        require((code == null) != (reference == null)) { "exactly one of owner code or reference" }
        check(ownerResultCode == null && ownerReference == null) { "the owner result is recorded once" }
        return copy(lastResponseAt = now, ownerResultCode = code, ownerReference = reference)
    }

    /** 무결성 플래그(D-OPS-007 — CONFLICT·IDENTITY_MISMATCH·DECISION_CONTENT_MISMATCH 등). 처음 값을 유지하고 지우지 않는다(이후 원인은 감사 행에). */
    fun flagIntegrity(code: String): OwnerExecution {
        require(CODE.matches(code)) { "integrity flag format" }
        return if (integrityFlag != null) this else copy(integrityFlag = code)
    }

    /** 재전송 중단(D-OPS-006): 처음 사유를 유지하고, 참여자가 자격을 되찾아도 지우지 않는다(I-26). */
    fun haltResend(reason: String): OwnerExecution {
        require(CODE.matches(reason)) { "halt reason format" }
        return if (resendHaltedReason != null) this else copy(resendHaltedReason = reason)
    }

    @Suppress("LongParameterList")
    private fun copy(
        attemptCount: Int = this.attemptCount,
        nextAttemptAt: Instant = this.nextAttemptAt,
        lastAttemptAt: Instant? = this.lastAttemptAt,
        lastResponseAt: Instant? = this.lastResponseAt,
        lastFailure: String? = this.lastFailure,
        integrityFlag: String? = this.integrityFlag,
        resendHaltedReason: String? = this.resendHaltedReason,
        ownerResultCode: String? = this.ownerResultCode,
        ownerReference: String? = this.ownerReference,
    ) = OwnerExecution(
        approvalId, body, fingerprint, approvedAt, executeBefore, executorActorId, attemptCount, nextAttemptAt, lastAttemptAt, lastResponseAt, lastFailure,
        integrityFlag, resendHaltedReason, ownerResultCode, ownerReference,
    )

    override fun equals(other: Any?): Boolean = other is OwnerExecution && other.approvalId == approvalId

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "OwnerExecution(approvalId=$approvalId, attempts=$attemptCount)"

    companion object {
        /** PAYMENT 본문 상한(I-03 — 16 KiB, 서명 전 거부)과 같다. */
        const val MAX_BODY_BYTES = 16 * 1024
        private val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        private val FINGERPRINT = Regex("^oct1:[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        private val REFERENCE = Regex("^pcn_[0-9A-HJKMNP-TV-Z]{26}$")

        @Suppress("LongParameterList")
        fun start(
            approvalId: String, requestBody: ByteArray, fingerprint: String, approvedAt: Instant, executeBefore: Instant, executorActorId: String, nextAttemptAt: Instant,
        ): OwnerExecution = OwnerExecution(
            approvalId, requestBody.copyOf(), fingerprint, approvedAt, executeBefore, executorActorId, 1, nextAttemptAt, approvedAt, null, null, null, null, null, null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            approvalId: String, requestBody: ByteArray, fingerprint: String, approvedAt: Instant, executeBefore: Instant, executorActorId: String, attemptCount: Int,
            nextAttemptAt: Instant, lastAttemptAt: Instant?, lastResponseAt: Instant?, lastFailure: String?, integrityFlag: String?, resendHaltedReason: String?,
            ownerResultCode: String?, ownerReference: String?,
        ): OwnerExecution = OwnerExecution(
            approvalId, requestBody.copyOf(), fingerprint, approvedAt, executeBefore, executorActorId, attemptCount, nextAttemptAt, lastAttemptAt, lastResponseAt,
            lastFailure, integrityFlag, resendHaltedReason, ownerResultCode, ownerReference,
        )
    }
}
