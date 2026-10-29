package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.ConfirmOperatorApprovalDeadlineUseCase
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.provided.OperatorDeadlineConfirmation
import com.paycore.payment.payment.application.provided.OperatorDecisionView
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.application.required.OperatorApprovalDecision
import com.paycore.payment.payment.application.required.OperatorApprovalDecisionStore
import com.paycore.payment.payment.application.required.OperatorCancelRejection
import com.paycore.payment.payment.application.required.OperatorCancelRejectionStore
import com.paycore.payment.payment.application.required.OperatorDecisionKind
import com.paycore.payment.payment.application.required.OperatorDecisionOrigin
import com.paycore.payment.payment.application.required.OperatorDecisionReader
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.PaymentId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 기한 확인 전용 호출(ADMIN-PAYMENT-OPS-001 §4.4.5, D-OPS-009). 자기 [LocalTransactions] 인스턴스(READ COMMITTED, 교착·version·UNIQUE 충돌 새 TX 재판정 최대 3회 — CancelStartService와 같은
 * 유틸리티, **CancelStartService를 부르지 않는다**)로: ① 결정 있음 → 그대로(hash 다르면 감사 APPROVAL_CONFLICT 1행) ② 옛 취소 행 → legacy STARTED(기록 없음) ③ PAYMENT 시각 `now <
 * executeBefore` → NONE(기록·감사 없음) ④ 그 밖 → REJECTED(APPROVAL_EXECUTION_EXPIRED) 결정(origin DEADLINE_CONFIRMATION) + 거절 감사 1행. 취소·잠금·예약을 만들지 않는다(ArchUnit).
 * 늦은 실행 명령과의 경합은 UNIQUE(approval_id)가 정한다(1062 → 재시도 ①에서 커밋된 결정 반환). 응답 재료는 커밋 뒤 비권위 조회로 만든다(같은 결정 — 결정 행은 바뀌지 않는다).
 */
@Service
class OperatorDeadlineConfirmationService(
    transactionManager: PlatformTransactionManager,
    private val decisions: OperatorApprovalDecisionStore,
    private val rejections: OperatorCancelRejectionStore,
    private val cancellations: PaymentCancellationRepository,
    private val reader: OperatorDecisionReader,
    private val clock: PaymentClock,
) : ConfirmOperatorApprovalDeadlineUseCase {
    private val log = LoggerFactory.getLogger(OperatorDeadlineConfirmationService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    private enum class Step { EXISTING, LEGACY, NONE, CONFIRMED }

    override fun confirm(command: OperatorCorrectionCancelCommand): OperatorDeadlineConfirmation {
        val step = try {
            transactions.execute(::isRetryable) { decide(command) }
        } catch (e: CommitOutcomeUnknownException) {
            throw CancelPaymentFailure.OutcomeUnknown(e)
        } catch (e: TransactionException) {
            throw CancelPaymentFailure.TemporarilyUnavailable("TRANSACTION_UNAVAILABLE", e)
        } catch (e: PersistenceFailure) {
            throw CancelPaymentFailure.TemporarilyUnavailable("STORE_" + e::class.simpleName?.uppercase(), e)
        }
        if (step == Step.NONE) return OperatorDeadlineConfirmation(OperatorDecisionView.NONE, confirmed = false)
        val view = try {
            OperatorDecisionViews.of(reader, command.approvalId)
        } catch (e: OperatorQueryFailure) {
            throw CancelPaymentFailure.TemporarilyUnavailable("DECISION_READ_UNAVAILABLE", e)
        }
        return OperatorDeadlineConfirmation(view, confirmed = step == Step.CONFIRMED)
    }

    private fun decide(command: OperatorCorrectionCancelCommand): Step {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val paymentId = PaymentId(command.paymentId)
        decisions.find(command.approvalId)?.let { stored ->
            // review n-1: 실행 경로와 같이 모르는 hash 버전은 비교하지 않고 fail-closed 503(TX rollback).
            if (!stored.contentHash.startsWith(OperatorCorrectionCancelCommand.CONTENT_HASH_PREFIX)) {
                throw CancelPaymentFailure.TemporarilyUnavailable("UNSUPPORTED_DECISION_HASH_VERSION")
            }
            if (stored.contentHash != command.contentHash()) audit(command, paymentId, OperatorCancelRejectionCode.APPROVAL_CONFLICT, now)
            return Step.EXISTING
        }
        if (cancellations.findIdByApprovalId(command.approvalId) != null) return Step.LEGACY
        if (now.isBefore(command.executeBefore)) return Step.NONE
        audit(command, paymentId, OperatorCancelRejectionCode.APPROVAL_EXECUTION_EXPIRED, now)
        decisions.append(
            OperatorApprovalDecision(
                command.approvalId, paymentId, OperatorDecisionKind.REJECTED, OperatorCancelRejectionCode.APPROVAL_EXECUTION_EXPIRED.name, null, command.contentHash(),
                command.money.amountMinor, command.money.currency, command.requestedBy, command.approvedBy, command.approvedTargetFingerprint, command.approvedAt,
                command.executeBefore, now, OperatorDecisionOrigin.DEADLINE_CONFIRMATION,
            ),
        )
        return Step.CONFIRMED
    }

    private fun audit(command: OperatorCorrectionCancelCommand, paymentId: PaymentId, code: OperatorCancelRejectionCode, now: Instant) {
        rejections.append(OperatorCancelRejection(command.approvalId, paymentId, command.requestedBy, command.approvedBy, code.name, now))
        log.info("operation=ConfirmOperatorApprovalDeadline correlationId={} result=REJECTED reason={}", command.correlationId, code.name)
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict || e is PersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
