package com.paycore.admin.approval.domain

import java.time.Duration
import java.time.Instant

/**
 * 소유 서비스 승인(`OPERATOR_CORRECTION_CANCEL`) Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture). 모두 domain 공식 경로
 * (`ApprovalRequest.create` → `beginExecution` …, `OwnerExecution.start`, `OperatorCancelRequestSnapshot.of`)로 만들고 DB 직접 INSERT를 하지 않는다.
 * 값은 Demo(운영 ID 아님). Payment ID는 시험마다 [paymentId]로 새로 만든다(미해결 승인 UNIQUE가 Payment 단위라 시험끼리 겹치지 않게).
 */
object OwnerServiceApprovalFixture {
    const val PAYMENT_ID = "pay_01JC0000000000000000000001"
    val PAYMENT_TARGET = ApprovalTarget.Payment(PAYMENT_ID, 3)
    const val FINGERPRINT = "oct1:88f6d2fe7ea4f26577e78f6753cb8bdfe4634162d904c83d0b44390f70025fb0"
    val VALIDITY: Duration = Duration.ofHours(24)
    val EXECUTION_WINDOW: Duration = Duration.ofHours(24)

    private const val ULID_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** `pay_` + ULID 형식(26자 — 순번을 Crockford base32로). */
    fun paymentId(seq: Long): String = "pay_" + base32(seq)

    /** `apr_` + ULID 형식. */
    fun approvalId(seq: Long): String = "apr_" + base32(seq)

    private fun base32(seq: Long): String {
        require(seq >= 0)
        val digits = StringBuilder()
        var n = seq
        do {
            digits.append(ULID_CHARS[(n % 32).toInt()])
            n /= 32
        } while (n > 0)
        return digits.reverse().toString().padStart(26, '0')
    }

    /** 고정 대상(요청 시점 PAYMENT 조회 결과 — 승인 TX1 비교 기준). */
    fun pinned(paymentId: String = PAYMENT_ID, revision: Long = 3, currency: String = "KRW", cancellableMinor: Long = 10_000) =
        PinnedPaymentTarget(paymentId, "AUTHORIZED", currency, authorizedMinor = 10_000, cancellableMinor = cancellableMinor, balanceRevision = revision, onHold = false)

    fun pending(
        id: String,
        requester: String,
        now: Instant,
        paymentId: String = PAYMENT_ID,
        revision: Long = 3,
    ): ApprovalRequest = ApprovalRequest.create(
        id, ApprovalKind.OPERATOR_CORRECTION_CANCEL, ApprovalTarget.Payment(paymentId, revision), pinned(paymentId, revision).toSnapshotJson(), null, null,
        requester, "Operator", now, VALIDITY,
    )

    /** 요청 고정 값 — [revision]은 같은 요청의 [pending]과 같게 준다(승인 행 target_snapshot = pinned_target 사본). */
    fun command(
        approvalId: String, paymentId: String = PAYMENT_ID, amountMinor: Long = 7_000, at: Instant, reason: String = "duplicate charge correction", revision: Long = 3,
    ) = OperatorCancelRequestSnapshot.of(
        approvalId, paymentId, amountMinor, "KRW", OperatorCancelPurposeKind.INCIDENT_OR_RECON_CORRECTION, "RECON-2026-0001", reason, pinned(paymentId, revision), at,
    )

    /** 승인 TX1이 만드는 실행 행(시도 1회, 다음 시도 = 승인 시각 + stale 기준). */
    fun execution(approvalId: String, approver: String, approvedAt: Instant, body: ByteArray = "{\"approvalId\":\"$approvalId\"}".toByteArray()) =
        OwnerExecution.start(approvalId, body, FINGERPRINT, approvedAt, approvedAt.plus(EXECUTION_WINDOW), approver, approvedAt.plusSeconds(90))
}
