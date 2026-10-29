package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/**
 * 운영자 보정 취소(CANCEL-001 §5 — D-CANCEL-005, D-ADMIN-007). 진입점은 서명 인증된 ADMIN 채널 adapter 하나(ADMIN-PAYMENT-OPS-001 §4.3 `AdminOperatorCancellationController` — ArchUnit
 * 정확 FQN). PAYMENT는 요청자 ≠ 승인자·사유·근거·상한·승인 대상 지문·실행 기한(D-OPS-002)을 다시 검사하고(ADMIN 승인 상태를 동기 조회하지 않는다), approvalId별 **최초 확정 결정**을
 * 기록·재생한다(D-OPS-005 — 같은 approvalId에는 항상 같은 결정). 업무 거절은 같은 로컬 TX에 감사 행 하나(D-CANCEL1-006·008). TXd·TX2·회복은 가맹점 취소와 공용.
 */
interface OperatorCorrectionCancelUseCase {
    fun cancel(command: OperatorCorrectionCancelCommand): OperatorCorrectionCancelResult
}

/**
 * 운영자 보정 취소 명령. [approvedTargetFingerprint]는 ADMIN이 승인한 대상 `oct1:` 지문(D-ADMIN-007 — 실행 시점 값과 다르면 거절). [reason]은 취소 행 저장만(로그·거절 감사에 쓰지 않는다).
 * [approvedAt]·[executeBefore]는 승인 시각·실행 기한(D-OPS-002 — 서명된 본문 값, `oct1:`에는 넣지 않는다).
 */
data class OperatorCorrectionCancelCommand(
    val paymentId: String,
    val money: Money,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val approvalId: OperatorApprovalId,
    val reason: String,
    val purpose: OperatorCancelPurpose,
    val approvedTargetFingerprint: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val correlationId: String,
) {
    /**
     * 결정 내용 hash(§4.4.2): `"opd1:" + hex(SHA-256(길이 접두 canonical))` — 요소마다 `<UTF-8 바이트 수>:<값>`. correlationId는 넣지 않는다(추적용 — 재전송이 달라도 같은 명령).
     */
    fun contentHash(): String {
        val purposeKind = when (purpose) {
            is OperatorCancelPurpose.IncidentOrReconCorrection -> "INCIDENT_OR_RECON_CORRECTION"
            is OperatorCancelPurpose.MerchantTerminatedObligation -> "MERCHANT_TERMINATED_OBLIGATION"
        }
        val canonical = listOf(
            CONTENT_TAG, CONTENT_VERSION, approvalId.value, paymentId, money.amountMinor.toString(), money.currency.value, requestedBy.value, approvedBy.value,
            purposeKind, purpose.reference, reason, approvedTargetFingerprint, TIMESTAMP.format(approvedAt), TIMESTAMP.format(executeBefore),
        ).joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return CONTENT_HASH_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "OperatorCorrectionCancelCommand(paymentId=$paymentId, approvalId=${approvalId.value}, correlationId=$correlationId)"

    companion object {
        const val CONTENT_HASH_PREFIX = "opd1:"
        private const val CONTENT_TAG = "paycore.operator-decision-content"
        private const val CONTENT_VERSION = "1"
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/** 운영자 보정 취소 거절 분류(감사 행 `rejection_reason`·결정 행 `rejection_code` 값 — 고정 code, 개인정보·사유 문구 없음). */
enum class OperatorCancelRejectionCode {
    PAYMENT_NOT_FOUND,

    /** paymentId가 형식이 아니다(감사 행 payment_id는 자리표시 MALFORMED — 원문은 저장하지 않는다, 검토 #3 m-3). Use Case 직접 호출 호환(HTTP는 adapter가 먼저 400). 결정 행 없음. */
    PAYMENT_ID_MALFORMED,
    PAYMENT_NOT_CANCELLABLE,
    CANCEL_IN_PROGRESS,
    CURRENCY_MISMATCH,
    ALREADY_FULLY_CANCELLED,
    AMOUNT_EXCEEDS_BALANCE,
    PARTIAL_CANCEL_NOT_SUPPORTED,
    OPERATOR_SAME_PERSON,
    OPERATOR_REASON_MISSING,
    OPERATOR_BASIS_MISSING,
    OPERATOR_REASON_INVALID,
    OPERATOR_BASIS_INVALID,
    OPERATOR_TARGET_STALE,

    /** 같은 approvalId가 다른 내용으로 쓰였다(파생 결과 — 결정 자체가 아님, D-OPS-007: ADMIN 비종료). */
    APPROVAL_CONFLICT,

    /** 같은 approvalId·Payment·금액이지만 요청자·승인자가 다르다(CN-32 — 파생 결과). */
    APPROVAL_IDENTITY_MISMATCH,

    /** D-OPS-002: PAYMENT 시각 `now ≥ executeBefore`(결정 — 기한 확인 호출도 이 결정을 기록, D-OPS-009). */
    APPROVAL_EXECUTION_EXPIRED,

    /** D-OPS-002: `executeBefore − approvedAt`이 상한을 넘거나 0 이하, 또는 approvedAt이 미래(시계 차이 허용 밖). */
    APPROVAL_WINDOW_INVALID,

    /** §4.6 형식 오류 감사(결정 행 없음 — 400). */
    REQUEST_MALFORMED,
    APPROVAL_ID_MALFORMED,
    OPERATOR_ID_MALFORMED,
    ;

    /** 결정이 아니라 기존 결정에서 파생된 결과(§4.4.1 — 결정 행을 새로 쓰지 않는다). */
    val derived: Boolean get() = this == APPROVAL_CONFLICT || this == APPROVAL_IDENTITY_MISMATCH

    /** 형식 오류 감사 code(§4.6 — 결정 행 없음, Use Case 판정에는 나오지 않는다). */
    val malformed: Boolean get() = this == REQUEST_MALFORMED || this == APPROVAL_ID_MALFORMED || this == OPERATOR_ID_MALFORMED || this == PAYMENT_ID_MALFORMED
}

sealed interface OperatorCorrectionCancelResult {
    /** 시작됨([existing] = 같은 approvalId·같은 내용의 기존 결정 재생). [view]는 현재 상태. */
    data class Accepted(val view: CancellationView, val existing: Boolean) : OperatorCorrectionCancelResult

    /** 업무 거절(커밋된 결정 행 — 새로 썼거나 재생) 또는 파생 거절([OperatorCancelRejectionCode.derived]). 요청마다 감사 행 하나. */
    data class Rejected(val code: OperatorCancelRejectionCode) : OperatorCorrectionCancelResult {
        /** 같은 approvalId의 기록 결정을 재생한 거절인지(I-59 결정 지표 label — 동등성·응답에 들어가지 않는다). 파생 거절은 결정이 아니라 의미 없음. */
        var replayed: Boolean = false
            private set

        constructor(code: OperatorCancelRejectionCode, replayed: Boolean) : this(code) {
            this.replayed = replayed
        }
    }

    /** Payment가 없다(REJECTED(PAYMENT_NOT_FOUND) 결정 행 — 또는 Use Case 직접 호출의 형식 아닌 paymentId). */
    data object NotFound : OperatorCorrectionCancelResult
}
