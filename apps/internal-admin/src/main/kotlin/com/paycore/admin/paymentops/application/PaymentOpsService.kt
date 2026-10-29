package com.paycore.admin.paymentops.application

import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.DecisionResult
import com.paycore.admin.paymentops.application.provided.ExecutionBodyInput
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.FingerprintInput
import com.paycore.admin.paymentops.application.provided.OperatorCancelContract
import com.paycore.admin.paymentops.application.provided.PaymentDecisionListQuery
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.paymentops.application.required.PaymentOpsGateway
import com.paycore.admin.paymentops.application.required.PaymentOpsMetrics
import com.paycore.admin.paymentops.application.required.PaymentOpsTimings
import com.paycore.admin.paymentops.domain.OperatorCancelExecutionBody
import com.paycore.admin.paymentops.domain.OperatorCancelTargetFingerprint
import com.paycore.admin.paymentops.domain.OwnerResponseClassifier
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * paymentops 공개 API 구현(ADMIN-PAYMENT-OPS-001 §4.1): 클라이언트는 Gateway에 위임(TX 밖 검사·permit·분류는 adapter), 계약 계산은 domain(oct1·실행 본문),
 * 시간 정책은 기동 때 검사된 설정 값. 형식이 아닌 paymentId는 PAYMENT를 부르지 않고 NotFound(경로에 넣지 않는다 — §4.2).
 */
@Service
class PaymentOpsService(private val gateway: PaymentOpsGateway, private val timings: PaymentOpsTimings, private val metrics: PaymentOpsMetrics) :
    PaymentOpsClient, PaymentDecisionListQuery, OperatorCancelContract, PaymentOpsPolicy {

    override fun target(paymentId: String, purpose: CallPurpose): TargetResult {
        if (!PAYMENT_ID.matches(paymentId)) return TargetResult.NotFound
        val result = gateway.target(paymentId, purpose)
        val label = when (result) {
            is TargetResult.Found -> "FOUND"
            TargetResult.NotFound -> "NOT_FOUND"
            is TargetResult.Unavailable -> "UNAVAILABLE"
        }
        metrics.call(TARGET, label)
        return result
    }

    override fun execute(approvalId: String, body: ByteArray): ExecutionResult {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        val result = gateway.execute(approvalId, body)
        val label = when (result) {
            is ExecutionResult.Started -> "STARTED"
            is ExecutionResult.OwnerRejected -> "OWNER_REJECTED"
            is ExecutionResult.Unknown -> "UNKNOWN"
        }
        metrics.call(EXECUTE, label)
        return result
    }

    override fun fingerprint(input: FingerprintInput): String = OperatorCancelTargetFingerprint.of(
        OperatorCancelTargetFingerprint.Input(
            input.paymentId, input.amountMinor, input.currency, input.balanceRevision, input.approvalId, input.requestedBy, input.approvedBy, input.purposeKind,
            input.purposeReference,
        ),
    )

    override fun executionBody(input: ExecutionBodyInput): ByteArray = OperatorCancelExecutionBody(
        input.approvalId, input.paymentId, input.amountMinor, input.currency, input.requestedBy, input.approvedBy, input.reason, input.purposeKind,
        input.purposeReference, input.approvedTargetFingerprint, input.approvedAt, input.executeBefore, input.correlationId,
    ).toBytes()

    override fun decision(approvalId: String): DecisionResult {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        return gateway.decision(approvalId).also { metrics.call(DECISION, decisionLabel(it)) }
    }

    override fun confirmDeadline(approvalId: String, body: ByteArray): DecisionResult {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        return gateway.confirmDeadline(approvalId, body).also { metrics.call(DEADLINE, decisionLabel(it)) }
    }

    private fun decisionLabel(r: DecisionResult): String = when (r) {
        is DecisionResult.Recorded -> r.decision
        DecisionResult.None -> "NONE"
        is DecisionResult.Unavailable -> "UNAVAILABLE"
    }

    override val deadlineSkew: Duration get() = timings.deadlineSkew

    override val recoveryBatchSize: Int get() = timings.recoveryBatchSize

    override val decisionlessRejectionCodes: Set<String> get() = OwnerResponseClassifier.PAYMENT_BAD_REQUEST_CODES

    override fun decisionsAfter(watermark: Long): com.paycore.admin.paymentops.application.provided.DecisionPage {
        require(watermark >= 0) { "watermark" }
        return gateway.decisionsAfter(watermark).also {
            metrics.call(LIST, if (it is com.paycore.admin.paymentops.application.provided.DecisionPage.Page) "PAGE" else "UNAVAILABLE")
        }
    }

    override val backoffMax: Duration get() = timings.backoffMax

    override val decisionStableDelay: Duration get() = timings.decisionStableDelay

    override val reconciliationMaxPages: Int get() = timings.reconciliationMaxPages

    override val reconciliationFullScanInterval: Duration get() = timings.reconciliationFullScanInterval

    override val executionWindow: Duration get() = timings.executionWindow

    override val staleAfter: Duration get() = timings.staleAfter

    /** n번째 명령 뒤 대기: initial × 2^(n−1), 상한 [PaymentOpsTimings.backoffMax](시도 상한 없음 — D-OPS-003). */
    override fun backoff(attemptCount: Int): Duration {
        val shift = (attemptCount - 1).coerceIn(0, MAX_SHIFT)
        val candidate = timings.backoffInitial.multipliedBy(1L shl shift)
        return if (candidate > timings.backoffMax) timings.backoffMax else candidate
    }

    override fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private companion object {
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        const val MAX_SHIFT = 30
        const val TARGET = "TARGET"
        const val EXECUTE = "EXECUTE"
        const val DECISION = "DECISION"
        const val DEADLINE = "DEADLINE"
        const val LIST = "LIST"
    }
}
