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
        require(ownerReference == null || referenceFormat(ownerReference)) { "owner reference format (cancellation id, file id or contract revision)" }
        require(ownerResultCode == null || ownerReference == null) { "an owner result is a reference (STARTED) or a code (REJECTED), not both" }
    }

    /** 저장 본문 사본(바깥에서 바꿀 수 없다). */
    fun requestBody(): ByteArray = body.copyOf()

    /**
     * 종류별 문법(ACQ-ADMIN-001 B1-A 리뷰 m-4): 지문 접두와 이미 기록된 소유 참조가 [kind]의 것인지(운영자 보정 취소 = `oct1`·`pcn_`, 재매입 = `acd1`·파일 ID).
     * 행의 형식 검사([FINGERPRINT]·[REFERENCE])는 두 종류의 합집합이라, 종류와의 대조는 승인 행을 아는 쪽(Factory·결과 반영·회복 job)이 이 함수로 한다.
     */
    fun conformsTo(kind: ApprovalKind): Boolean =
        fingerprint.substringBefore(':') == fingerprintPrefix(kind) && (ownerReference == null || referenceFits(kind, ownerReference))

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
        /**
         * 운영자 보정 취소 `oct1:`(대상 지문), 재매입 `acd1:`(명령 내용 hash — ACQ-ADMIN-001 B1-A), 가맹점 승인 `mop1:`(MASTER 근거 지문 — MERCHANT-OPS-002 C-23). V9 CHECK와 같다.
         */
        private val FINGERPRINT = Regex("^(oct1|acd1|mop1):[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")

        /** 1..2147483647(Int 범위, 앞자리 0 없음 — MASTER Revision 번호, C-30). */
        private const val REVISION_NO =
            "([1-9][0-9]{0,8}|1[0-9]{9}|20[0-9]{8}|21[0-3][0-9]{7}|214[0-6][0-9]{6}|2147[0-3][0-9]{5}|21474[0-7][0-9]{4}|214748[0-2][0-9]{3}|2147483[0-5][0-9]{2}|21474836[0-3][0-9]|214748364[0-7])"

        private val CANCELLATION_REFERENCE = Regex("^pcn_[0-9A-HJKMNP-TV-Z]{26}$")
        private val FILE_REFERENCE = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")
        private val CONTRACT_REVISION_REFERENCE = Regex("^ctr_[0-9A-HJKMNP-TV-Z]{26}#$REVISION_NO$")
        private val MERCHANT_ACCOUNT_REFERENCE = Regex("^mid_[0-9A-HJKMNP-TV-Z]{26}$")
        private val ACTIVATION_REFERENCE = Regex("^act_[0-9A-HJKMNP-TV-Z]{26}$")

        /** B3-1 Site 심사 결정 `site_<ULID>#<seq>`(seq 1..10자리 — MASTER 결정 행 순번). */
        private val SITE_REVIEW_REFERENCE = Regex("^site_[0-9A-HJKMNP-TV-Z]{26}#[1-9][0-9]{0,9}$")

        /** B3-2 통화 활성화 기록 `cur_<ULID>`. */
        private val CURRENCY_REFERENCE = Regex("^cur_[0-9A-HJKMNP-TV-Z]{26}$")

        /** MERCHANT-CODE-001 MC-1b 코드 변경 성공 reference = 그 가맹점 `mer_<ULID>`(§3.7.2). */
        private val MERCHANT_REFERENCE = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")

        /** 소유 참조: PAYMENT cancellationId, FINANCIAL 재매입 새 파일 ID(파일 계약 v1), MASTER 승인 Revision `ctr_…#n`(MERCHANT-OPS-002 B2-2). */
        private fun referenceFormat(reference: String): Boolean =
            CANCELLATION_REFERENCE.matches(reference) || FILE_REFERENCE.matches(reference) || CONTRACT_REVISION_REFERENCE.matches(reference) ||
                MERCHANT_ACCOUNT_REFERENCE.matches(reference) || ACTIVATION_REFERENCE.matches(reference) || SITE_REVIEW_REFERENCE.matches(reference) ||
                CURRENCY_REFERENCE.matches(reference) || MERCHANT_REFERENCE.matches(reference)

        /** 종류별 지문 접두(m-4). 소유 서비스 종류가 아니면 실행 행이 없다(fail-closed). */
        fun fingerprintPrefix(kind: ApprovalKind): String = when (kind) {
            ApprovalKind.OPERATOR_CORRECTION_CANCEL -> "oct1"
            ApprovalKind.ACQ_REACQUISITION -> "acd1"
            ApprovalKind.MERCHANT_CONTRACT_APPROVAL, ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE, ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION,
            ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL, ApprovalKind.MERCHANT_CURRENCY_ENABLEMENT, ApprovalKind.MERCHANT_CODE_CHANGE,
            -> "mop1"
            else -> throw IllegalArgumentException("no owner execution for $kind")
        }

        /** 소유 참조가 [kind]의 문법인지(m-4): 운영자 보정 취소 = PAYMENT cancellationId, 재매입 = FINANCIAL 새 파일 ID, 계약 승인 = `ctr_…#n`. */
        fun referenceFits(kind: ApprovalKind, reference: String): Boolean = when (kind) {
            ApprovalKind.OPERATOR_CORRECTION_CANCEL -> CANCELLATION_REFERENCE.matches(reference)
            ApprovalKind.ACQ_REACQUISITION -> FILE_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_CONTRACT_APPROVAL -> CONTRACT_REVISION_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE -> MERCHANT_ACCOUNT_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION -> ACTIVATION_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL -> SITE_REVIEW_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_CURRENCY_ENABLEMENT -> CURRENCY_REFERENCE.matches(reference)
            ApprovalKind.MERCHANT_CODE_CHANGE -> MERCHANT_REFERENCE.matches(reference)
            else -> false
        }

        /** 종류를 아는 Factory(m-4 — 운영 경로는 이것만 쓴다): 지문 접두가 [kind]의 것이 아니면 [IllegalArgumentException]. */
        @Suppress("LongParameterList")
        fun start(
            kind: ApprovalKind, approvalId: String, requestBody: ByteArray, fingerprint: String, approvedAt: Instant, executeBefore: Instant, executorActorId: String,
            nextAttemptAt: Instant,
        ): OwnerExecution {
            require(fingerprint.substringBefore(':') == fingerprintPrefix(kind)) { "fingerprint prefix of $kind" }
            return start(approvalId, requestBody, fingerprint, approvedAt, executeBefore, executorActorId, nextAttemptAt)
        }

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
