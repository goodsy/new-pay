package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.CancellationIdentifierGenerator
import com.paycore.payment.payment.application.required.CancellationRecoverySchedule
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.CancellationTimings
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.IdempotentOperation
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.OperatorCancelRejection
import com.paycore.payment.payment.application.required.OperatorCancelRejectionStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.application.required.StoredCancelAccepted
import com.paycore.payment.payment.application.required.StoredCancelRejected
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelCapabilityResolution
import com.paycore.payment.payment.domain.CancelInitiator
import com.paycore.payment.payment.domain.CancelRequestKey
import com.paycore.payment.payment.domain.CancelRequestMatch
import com.paycore.payment.payment.domain.CancelStart
import com.paycore.payment.payment.domain.CancelStartCommand
import com.paycore.payment.payment.domain.CancelStartRejection
import com.paycore.payment.payment.domain.CancelStartResult
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ProviderRequestRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** TX1 결과(가맹점 — CANCEL-001 §4.3). */
sealed interface CancelStartOutcome {
    data class Accepted(val paymentId: PaymentId, val cancellationId: CancellationId, val outcome: CancelOutcome, val replayed: Boolean) : CancelStartOutcome

    data class Rejected(val rejection: CancelRejection, val paymentId: PaymentId, val replayed: Boolean) : CancelStartOutcome

    data object NotFound : CancelStartOutcome

    data object IdempotencyKeyReused : CancelStartOutcome
}

/** TX1 결과(운영자 보정 — CANCEL-001 §5.3). 거절·없음은 같은 TX에 감사 행이 커밋됐다(D-CANCEL1-006). */
sealed interface OperatorCancelStartOutcome {
    data class Started(val cancellationId: CancellationId, val existing: Boolean) : OperatorCancelStartOutcome

    data class Rejected(val code: OperatorCancelRejectionCode) : OperatorCancelStartOutcome

    data object NotFound : OperatorCancelStartOutcome
}

/**
 * 취소 TX1(CANCEL-001 §4.3 판정 순서 — 앞 단계가 결정하면 멈춤, 가맹점·운영자 공용 단계 서비스). 한 PAYMENT 로컬 TX, 잠금 순서 §4.7:
 * (가맹점) 멱등 기록 → control → payment → (업무 키로 찾은 취소) → payment_authorization → return_balance → INSERT들. 신규 승인 권리·허용 판정·일 한도는 보지 않는다(K-3 — ArchUnit).
 *
 * - 저장 없는 결과(404·503)는 TX를 rollback한다(취소·예약·일정·멱등 예약 어느 것도 남지 않는다). 503: 원 경로 Route가 없음·환경/기관이 원 승인과 다름(rev 0.3 m-4)·취소 Capability 없음
 *   (sim-van-1 Route) — 값으로 넘겨 `CancelStart`가 앞 검사 뒤에 판정한다(§4.3-6·8).
 * - 운영자 명령은 멱등 기록 대신 approvalId 업무 키로 재요청을 판정하고(CN-10), 업무 거절은 같은 TX에 감사 행 하나를 남긴다(D-CANCEL1-006·008 — 개인정보·사유 문구 없음).
 * - 교착·version 충돌·UNIQUE 충돌(같은 approvalId가 다른 Payment에서 동시에 — rev 0.3 m-3)은 새 TX로 같은 의도를 최대 [MAX_ATTEMPTS]회 다시 판정한다.
 */
@Service
class CancelStartService(
    transactionManager: PlatformTransactionManager,
    private val idempotency: IdempotencyRecordStore,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val balances: ReturnBalanceRepository,
    private val cancellations: PaymentCancellationRepository,
    private val schedules: CancellationRecoveryScheduleStore,
    private val rejections: OperatorCancelRejectionStore,
    private val routes: InstitutionRouteResolver,
    private val ids: CancellationIdentifierGenerator,
    private val recordIds: AuthorizationIdentifierGenerator,
    private val timings: CancellationTimings,
    private val clock: PaymentClock,
) {
    private val log = LoggerFactory.getLogger(CancelStartService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    fun start(command: CancelPaymentCommand): CancelStartOutcome = inTransaction { merchantTx(command) }

    fun startOperator(command: OperatorCorrectionCancelCommand): OperatorCancelStartOutcome = inTransaction { operatorTx(command) }

    /** 공통 실패 분류(confirm TX1과 같다): 저장 없는 결과(rollback 신호의 결과 또는 503)·commit 불명확·TX 불가·저장소 일시 실패. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> inTransaction(block: () -> T): T =
        try {
            transactions.execute(::isRetryable) { block() }
        } catch (e: RolledBack) {
            (e.outcome as T?) ?: throw CancelPaymentFailure.TemporarilyUnavailable(e.reason)
        } catch (e: CancelPaymentFailure) {
            throw e
        } catch (e: CommitOutcomeUnknownException) {
            throw CancelPaymentFailure.OutcomeUnknown(e)
        } catch (e: CannotCreateTransactionException) {
            throw CancelPaymentFailure.TemporarilyUnavailable("TRANSACTION_UNAVAILABLE", e)
        } catch (e: UnexpectedRollbackException) {
            throw CancelPaymentFailure.TemporarilyUnavailable("ROLLED_BACK", e)
        } catch (e: PersistenceFailure.LockTimeout) {
            throw CancelPaymentFailure.TemporarilyUnavailable("STORE_LOCKTIMEOUT", e)
        } catch (e: PersistenceFailure) {
            throw CancelPaymentFailure.TemporarilyUnavailable("STORE_" + e::class.simpleName?.uppercase(), e)
        }

    // --- 가맹점 -------------------------------------------------------------------------------------------------------------------------------------

    private fun merchantTx(command: CancelPaymentCommand): CancelStartOutcome {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val scope = IdempotencyScope(command.environment, command.merchantAccountId, IdempotentOperation.CANCEL_PAYMENT, command.idempotencyKey)
        val fingerprint = CancelFingerprints.current(command)
        val reservation = IdempotencyReservation(scope, recordIds.idempotencyRecordId(), fingerprint, now, now.plus(IDEMPOTENCY_RETENTION))
        val reserved = try {
            idempotency.reserveOrLock(reservation)
        } catch (e: PersistenceFailure.LockTimeout) {
            throw CancelPaymentFailure.RequestInProgress(e)
        }
        if (reserved is IdempotencyReservationOutcome.Existing) {
            val record = reserved.record
            when {
                record.isExpiredAt(now) -> idempotency.renewExpired(reservation)
                !CancelFingerprints.isComparable(record.requestFingerprint) -> throw RolledBack(null, "UNSUPPORTED_FINGERPRINT_VERSION")
                record.requestFingerprint != fingerprint -> return CancelStartOutcome.IdempotencyKeyReused
                else -> return present(checkNotNull(record.result) { "a committed idempotency record has a result" }, replayed = true)
            }
        }
        val result = decideMerchant(command, now)
        idempotency.complete(scope, result, now)
        return present(result, replayed = false)
    }

    private fun present(stored: StoredRequestResult, replayed: Boolean): CancelStartOutcome = when (stored) {
        is StoredCancelAccepted -> CancelStartOutcome.Accepted(stored.paymentId, stored.cancellationId, stored.outcome, replayed)
        is StoredCancelRejected -> CancelStartOutcome.Rejected(stored.rejection, stored.paymentId, replayed)
        else -> error("a CancelPayment record holds a cancel result")
    }

    private fun decideMerchant(command: CancelPaymentCommand, now: Instant): StoredRequestResult {
        val paymentId = runCatching { PaymentId(command.paymentId) }.getOrNull() ?: throw RolledBack(CancelStartOutcome.NotFound, "PAYMENT_NOT_FOUND")
        // 비잠금 소유 확인을 control 잠금 전에 — 다른 MID·환경·없는 Payment는 아무 업무 행도 잠그지 않고 404(confirm n-3과 같다).
        val creation = payments.findCreationRecord(paymentId)
            ?.takeIf { it.orderKey.environment == command.environment && it.orderKey.merchantAccountId == command.merchantAccountId }
            ?: throw RolledBack(CancelStartOutcome.NotFound, "PAYMENT_NOT_FOUND")
        val target = lockTarget(creation.orderKey, paymentId)
        // §4.3-3: 업무 키 조회가 먼저(CC §4.4-4) — 같은 내용이면 기존 operation, 다른 내용이면 충돌(진행 중 검사보다 앞).
        cancellations.findIdByMerchantKey(paymentId, command.merchantCancelId)?.let { existingId ->
            val existing = checkNotNull(cancellations.lock(existingId)) { "a found cancellation can be locked" }.value
            return when (existing.compareRequest(paymentId, CancelRequestKey.Merchant(command.merchantCancelId), command.money)) {
                CancelRequestMatch.SAME_REQUEST -> StoredCancelAccepted(CancelOutcome.EXISTING_CANCELLATION, paymentId, existingId)
                else -> StoredCancelRejected(CancelRejection.CANCEL_REQUEST_CONFLICT, paymentId)
            }
        }
        val started = startCancel(target, command.money, CancelInitiator.Merchant(command.merchantCancelId), now, command.correlationId)
        return when (started) {
            is StartDecision.Started -> StoredCancelAccepted(CancelOutcome.ACCEPTED, paymentId, started.cancellationId)
            is StartDecision.Rejected -> StoredCancelRejected(merchantRejection(started.reason), paymentId)
        }
    }

    private fun merchantRejection(r: CancelStartRejection): CancelRejection = when (r) {
        CancelStartRejection.PAYMENT_NOT_CANCELLABLE -> CancelRejection.PAYMENT_NOT_CANCELLABLE
        CancelStartRejection.ORDER_ON_HOLD -> CancelRejection.ORDER_ON_HOLD
        CancelStartRejection.CANCEL_IN_PROGRESS -> CancelRejection.CANCEL_IN_PROGRESS
        CancelStartRejection.CURRENCY_MISMATCH -> CancelRejection.CURRENCY_MISMATCH
        CancelStartRejection.ALREADY_FULLY_CANCELLED -> CancelRejection.ALREADY_FULLY_CANCELLED
        CancelStartRejection.AMOUNT_EXCEEDS_BALANCE -> CancelRejection.CANCEL_AMOUNT_EXCEEDS_BALANCE
        CancelStartRejection.PARTIAL_CANCEL_NOT_SUPPORTED -> CancelRejection.PARTIAL_CANCEL_NOT_SUPPORTED
        else -> error("a merchant cancel cannot be rejected with $r")
    }

    // --- 운영자 보정 ---------------------------------------------------------------------------------------------------------------------------------

    private fun operatorTx(command: OperatorCorrectionCancelCommand): OperatorCancelStartOutcome {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        // 검토 #3 m-3(D-CANCEL1-006·008): 형식이 아닌 paymentId도 감사 행 하나(자리표시 payment_id — 원문은 저장하지 않는다)를 남기고 NotFound.
        val paymentId = runCatching { PaymentId(command.paymentId) }.getOrNull() ?: run {
            audit(command, MALFORMED_PAYMENT_ID, OperatorCancelRejectionCode.PAYMENT_ID_MALFORMED, now)
            return OperatorCancelStartOutcome.NotFound
        }
        val initiator = CancelInitiator.Operator(
            command.requestedBy, command.approvedBy, command.approvalId, command.reason, command.purpose, command.approvedTargetFingerprint,
        )
        val creation = payments.findCreationRecord(paymentId) ?: run {
            audit(command, paymentId, OperatorCancelRejectionCode.PAYMENT_NOT_FOUND, now)
            return OperatorCancelStartOutcome.NotFound
        }
        val target = lockTarget(creation.orderKey, paymentId)
        // CN-10·CC-12: approvalId는 전역 영구 업무 키 — 같은 Payment·같은 금액이면 기존 취소, 다르면 충돌.
        // 검토 #3 n-3: 비교만 하므로 비잠금 읽기. 같은 approvalId·Payment·금액이어도 요청자·승인자가 다르면 같은 요청이 아니다(CN-32 — 충돌·감사, fail-closed).
        cancellations.findIdByApprovalId(command.approvalId)?.let { existingId ->
            val existing = checkNotNull(cancellations.find(existingId)) { "a found cancellation is stored" }
            return when (existing.compareRequest(paymentId, CancelRequestKey.Operator(command.approvalId), command.money)) {
                CancelRequestMatch.SAME_REQUEST -> {
                    val stored = existing.initiator as? CancelInitiator.Operator ?: error("an approvalId belongs to an operator cancellation")
                    if (stored.requestedBy == command.requestedBy && stored.approvedBy == command.approvedBy) {
                        OperatorCancelStartOutcome.Started(existingId, existing = true)
                    } else {
                        rejectOperator(command, paymentId, OperatorCancelRejectionCode.APPROVAL_IDENTITY_MISMATCH, now)
                    }
                }
                else -> rejectOperator(command, paymentId, OperatorCancelRejectionCode.APPROVAL_CONFLICT, now)
            }
        }
        return when (val started = startCancel(target, command.money, initiator, now, command.correlationId)) {
            is StartDecision.Started -> OperatorCancelStartOutcome.Started(started.cancellationId, existing = false)
            is StartDecision.Rejected -> rejectOperator(command, paymentId, OperatorCancelRejectionCode.valueOf(operatorCode(started.reason)), now)
        }
    }

    private fun operatorCode(r: CancelStartRejection): String = when (r) {
        CancelStartRejection.ORDER_ON_HOLD, CancelStartRejection.CAPABILITY_UNAVAILABLE -> error("an operator correction cannot be rejected with $r")
        else -> r.name
    }

    private fun rejectOperator(command: OperatorCorrectionCancelCommand, paymentId: PaymentId, code: OperatorCancelRejectionCode, now: Instant): OperatorCancelStartOutcome {
        audit(command, paymentId, code, now)
        return OperatorCancelStartOutcome.Rejected(code)
    }

    /** D-CANCEL1-006·008: 거절 판정과 같은 로컬 TX의 감사 행 하나(요청자·승인자 내부 ID·고정 사유 code·시각 — 사유 문구 없음). */
    private fun audit(command: OperatorCorrectionCancelCommand, paymentId: PaymentId, code: OperatorCancelRejectionCode, now: Instant) {
        rejections.append(OperatorCancelRejection(command.approvalId, paymentId, command.requestedBy, command.approvedBy, code.name, now))
        log.info("operation=OperatorCorrectionCancel correlationId={} result=REJECTED reason={}", command.correlationId, code.name)
    }

    // --- 공용 단계 ---------------------------------------------------------------------------------------------------------------------------------

    private class LockedTarget(
        val control: com.paycore.payment.payment.application.required.Versioned<com.paycore.payment.payment.domain.OrderPaymentControl>,
        val payment: com.paycore.payment.payment.application.required.Versioned<com.paycore.payment.payment.domain.Payment>,
    )

    private fun lockTarget(orderKey: OrderKey, paymentId: PaymentId): LockedTarget {
        val control = controls.lock(orderKey) ?: error("a stored payment has its order control")
        val payment = payments.lock(paymentId) ?: error("a stored payment can be locked")
        return LockedTarget(control, payment)
    }

    private sealed interface StartDecision {
        data class Started(val cancellationId: CancellationId) : StartDecision

        data class Rejected(val reason: CancelStartRejection) : StartDecision
    }

    /** §4.3-4~9: Authorization(X) → 잔액(X) → 원 경로 Route·Capability(값) → 식별자 → CancelStart → 저장. Capability 판정 불가는 rollback(503, 저장 없음). */
    private fun startCancel(target: LockedTarget, money: Money, initiator: CancelInitiator, at: Instant, correlationId: String): StartDecision {
        val p = target.payment.value
        val authorization = authorizations.lockByPaymentId(p.id)?.value ?: return StartDecision.Rejected(CancelStartRejection.PAYMENT_NOT_CANCELLABLE)
        val balance = balances.lockByPaymentId(p.id)
        val original = originalRequest(authorization)
        val generated = ids.next(at)
        val capability = capabilityOf(authorization, original, p.orderKey, correlationId)
        val result = CancelStart.start(
            CancelStartCommand(
                control = target.control.value,
                payment = p,
                authorization = authorization,
                balance = balance?.value,
                money = money,
                initiator = initiator,
                capability = capability,
                cancellationId = generated.cancellationId,
                attemptId = generated.attemptId,
                providerRequest = ProviderRequestRef(original.institution, original.environment, original.institutionAccount, OPERATION, generated.requestId),
                at = at,
            ),
        )
        return when (result) {
            is CancelStartResult.Rejected -> {
                if (result.reason == CancelStartRejection.CAPABILITY_UNAVAILABLE) throw RolledBack(null, "CANCEL_CAPABILITY_UNAVAILABLE")
                StartDecision.Rejected(result.reason)
            }
            is CancelStartResult.Started -> {
                // 반환 예약 행이 취소 행을 참조하므로(FK) 취소를 먼저 INSERT한다(Port 계약).
                cancellations.insertNew(result.cancellation)
                if (result.balanceOpened) balances.insertNew(result.balance, at) else balances.update(result.balance, checkNotNull(balance).version, at)
                // CN-18: 회복 일정(같은 TX) — 전송되지 않은 채 preparedDeadline이 지나면 회복이 fencing한다.
                schedules.insert(
                    CancellationRecoverySchedule(
                        generated.attemptId, result.cancellation.id, p.id, p.orderKey, null, at.plus(timings.preparedDeadline), 0, null, null, timings.recoveryConfigVersion,
                    ),
                    at,
                )
                StartDecision.Started(result.cancellation.id)
            }
        }
    }

    /** 원 승인 Attempt의 기관 요청 namespace(확정 승인 거래가 있으면 RESOLVED Attempt — 없으면 거래가 없어 `CancelStart`가 먼저 거절한다). */
    private fun originalRequest(authorization: PaymentAuthorization): ProviderRequestRef =
        (authorization.attempts.firstOrNull { it.state == AttemptState.RESOLVED } ?: authorization.attempts.first()).providerRequest

    /**
     * §4.3-6: Authorization의 Route → 현재 등록부 프로파일 → 취소 Capability. 없음·환경 불일치·원 승인 기관과 다름(rev 0.3 m-4)·sim-van-2 아님 → Unavailable(값 — 판정은 CancelStart 순서대로).
     */
    private fun capabilityOf(authorization: PaymentAuthorization, original: ProviderRequestRef, orderKey: OrderKey, correlationId: String): CancelCapabilityResolution {
        val route = routes.resolve(authorization.admission.routeProfile)
        val failure = when {
            route == null -> "ROUTE_PROFILE_MISSING"
            route.environment != orderKey.environment || !route.servesOriginal(original) -> "ROUTE_NOT_ORIGINAL"
            else -> null
        }
        val resolution = if (failure == null) checkNotNull(route).cancelCapability() else CancelCapabilityResolution.Unavailable
        val reason = failure ?: if (resolution is CancelCapabilityResolution.Unavailable) "CANCEL_CAPABILITY_UNAVAILABLE" else null
        if (reason != null) log.warn("operation=CancelPayment correlationId={} capability=UNAVAILABLE failure={}", correlationId, reason)
        return resolution
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict || e is PersistenceFailure.UniqueConflict

    /** 저장 없는 결과로 TX를 rollback하는 내부 신호. [outcome]이 null이면 일시 불가(503, [reason]). */
    private class RolledBack(val outcome: Any?, val reason: String) : RuntimeException(reason, null, false, false)

    companion object {
        /** 형식이 아닌 paymentId 거절의 감사 행 자리표시(검토 #3 m-3 — 원문은 저장하지 않는다). */
        val MALFORMED_PAYMENT_ID = PaymentId("MALFORMED")
        private val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(30)
        private const val MAX_ATTEMPTS = 3
        private const val OPERATION = "CANCEL"
    }
}
