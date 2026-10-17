package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.CreatedPayment
import com.paycore.payment.payment.application.provided.EligibilityRefusal
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.GeneratedIdentifier
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.application.required.CheckoutValidityPolicy
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.IdempotentOperation
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.domain.BlockReason
import com.paycore.payment.payment.domain.OrderIntentFingerprint
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentIdReuseException
import com.paycore.payment.payment.domain.PaymentSlotDecision
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.StaleSlotException
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * CreatePayment 로컬 TX(CP-CONTRACT-001 §8–§11, 초안 §5). 한 PAYMENT TX 안에서 잠금 순서 **멱등 기록 → OrderPaymentControl → Payment**로:
 *
 * 1. 멱등 기록 예약/잠금(insert-first). 같은 키가 이미 있으면: 만료(30일, D-CPC-004)면 같은 행을 새 요청으로 갱신, 아니면 fingerprint가
 *    같을 때 저장 결과 재생(eligibility·판정 생략, D-CP-003) / 다르면 IDEMPOTENCY_KEY_REUSED.
 * 2. eligibility: required [PaymentEligibilityPort]를 command 값(environment·인증 MID·Site·통화·결제수단)과 요청 시각으로 같은 TX에서
 *    평가한다(로컬 Snapshot만, 원격 호출 없음). Eligible이 아니면 전체 rollback 후 [CreatePaymentResult.EligibilityRefused](저장 없음).
 * 3. control 확보(insert-first, 최초 insert 경쟁은 PK 대기로 직렬화). 없던 주문이면 첫 Payment(generation 1).
 * 4. 있던 주문이면 현재 Payment를 잠가 domain 판정(고정 입력 → hold → 과거 승인 → 상태·근거). 새 Payment면 control 점유(generation+1).
 *    판정 전에 결제창 시각이 지난 REQUESTED Payment는 같은 TX에서 inline 만료한다([RequestedPaymentClosing], AUTHN-001 §7.5.3 U-3 — PaymentExpired 포함).
 * 5. 새 Payment(REQUESTED) + Outbox PaymentRequested v1(CREATED일 때만) + 멱등 결과(성공/업무 거절)를 같은 TX에 쓰고 commit.
 *
 * 외부 호출 없음. 응답은 commit 뒤에 만들어진다. 교착·내부 경합(오래된 판정)은 DB가 rollback한 것을 확인한 뒤 새 TX로 같은 의도를
 * 최대 [MAX_ATTEMPTS]회 다시 실행한다. timeout·연결 오류는 결과를 바꾸지 않는 [CreatePaymentFailure]로 돌려준다.
 */
@Service
class CreatePaymentService(
    transactionManager: PlatformTransactionManager,
    private val idempotency: IdempotencyRecordStore,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val outbox: OutboxStore,
    private val eligibility: PaymentEligibilityPort,
    private val checkoutValidity: CheckoutValidityPolicy,
    private val closing: RequestedPaymentClosing,
) : CreatePaymentUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun create(command: CreatePaymentCommand): CreatePaymentResult =
        try {
            transactions.execute(::isRetryable) { createInTransaction(command) }
        } catch (e: EligibilityRefusedRollback) {
            // TX는 rollback됐다(commit 요청 전). 멱등 예약·만료 갱신 어느 것도 남지 않았다.
            CreatePaymentResult.EligibilityRefused(e.refusal)
        } catch (e: CreatePaymentFailure) {
            throw e
        } catch (e: CommitOutcomeUnknownException) {
            throw CreatePaymentFailure.OutcomeUnknown(e)
        } catch (e: CannotCreateTransactionException) {
            throw CreatePaymentFailure.TemporarilyUnavailable(e)
        } catch (e: UnexpectedRollbackException) {
            throw CreatePaymentFailure.TemporarilyUnavailable(e)
        } catch (e: RuntimeException) {
            throw toFailure(e) ?: e
        }

    private fun createInTransaction(command: CreatePaymentCommand): CreatePaymentResult {
        val now = command.requestedAt.truncatedTo(ChronoUnit.MILLIS)
        val scope = IdempotencyScope(
            command.orderKey.environment, command.orderKey.merchantAccountId, IdempotentOperation.CREATE_PAYMENT, command.idempotencyKey,
        )
        val reservation = IdempotencyReservation(
            scope, command.newIdentifiers.idempotencyRecordId, RequestFingerprints.current(command), now, now.plus(IDEMPOTENCY_RETENTION),
        )
        val reserved = try {
            idempotency.reserveOrLock(reservation)
        } catch (e: PersistenceFailure.LockTimeout) {
            throw CreatePaymentFailure.RequestInProgress(e)
        }
        if (reserved is IdempotencyReservationOutcome.Existing) {
            val record = reserved.record
            when {
                // 보존 기간이 지난 기록은 삭제 전이라도 재생하지 않는다(D-CPC-004). 잠근 같은 행을 이 요청으로 갱신한다.
                record.isExpiredAt(now) -> idempotency.renewExpired(reservation)
                // 저장된 기록의 버전으로 다시 계산해 비교한다(계약 §6.4). 지원하지 않는 버전은 비교하지 않고 503(UnsupportedFingerprintVersion).
                record.requestFingerprint != RequestFingerprints.compute(command, RequestFingerprints.versionOf(record.requestFingerprint)) ->
                    return CreatePaymentResult.IdempotencyKeyReused
                else -> return present(command, checkNotNull(record.result) { "committed idempotency record has no result" }, replayed = true)
            }
        }

        val snapshotVersion = admit(command, now)
        val result = decideAndApply(command, now, snapshotVersion)
        idempotency.complete(scope, result, now)
        return present(command, result, replayed = false)
    }

    /**
     * eligibility 평가(계약 §13). 인자는 모두 command에서 온다: 호출자가 다른 Site·통화로 평가한 결과를 끼워 넣을 수 없다.
     * Eligible이 아니면 [EligibilityRefusedRollback]으로 TX를 rollback한다. Payment에 남는 Snapshot 버전은 이 평가 결과에서만 온다.
     *
     * Use Case 방어(fail-closed, 관례): Port가 Eligible을 주더라도 `validUntil`이 평가 시각보다 뒤가 아니면 만료 Snapshot으로 보고
     * Unavailable(SNAPSHOT_EXPIRED)로 거절한다. `currencyUnit`이 요청 통화의 단위가 아니면 Port 결함이라 판정하지 않고 실패시킨다
     * (기준정보가 통화를 활성화한다는 §4 근거가 어긋남 — 저장 없음).
     */
    private fun admit(command: CreatePaymentCommand, now: Instant): EligibilitySnapshotVersion {
        val currency = command.orderIntent.money.currency
        val result = eligibility.evaluate(
            environment = command.orderKey.environment,
            authenticatedMid = command.orderKey.merchantAccountId,
            merchantSiteId = command.orderIntent.merchantSiteId,
            currency = currency,
            paymentMethod = command.paymentMethod,
            evaluatedAt = now,
        )
        val refusal = when (result) {
            is EligibilityResult.Eligible -> {
                check(result.currencyUnit.code == currency) { "eligibility currency unit does not match the requested currency" }
                if (result.validUntil.isAfter(now)) return result.snapshotVersion
                EligibilityRefusal.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED)
            }
            EligibilityResult.NotFoundOrForbidden -> EligibilityRefusal.NotFoundOrForbidden
            is EligibilityResult.NotEligible -> EligibilityRefusal.NotEligible(result.reason)
            is EligibilityResult.Unavailable -> EligibilityRefusal.Unavailable(result.reason)
        }
        throw EligibilityRefusedRollback(refusal)
    }

    private fun decideAndApply(command: CreatePaymentCommand, now: Instant, snapshotVersion: EligibilitySnapshotVersion): StoredRequestResult {
        val intent = command.orderIntent.fingerprint()
        // D-AUTHN-005: 새 Payment의 결제창 절대 만료 시각 = 생성 시각 + 결제 유효기간 설정(버전 함께 저장). 재사용(UseExistingPayment)은 연장하지 않는다.
        val window = checkoutValidity.windowFor(now)
        val first = Payment.request(command.newIdentifiers.paymentId, command.orderKey, intent, 1, window)
        return when (val acquired = controls.openOrLock(OrderPaymentControl.open(first), now)) {
            is ControlAcquisition.Opened -> createPayment(first, command, snapshotVersion, now)
            is ControlAcquisition.Existing -> {
                val control = acquired.control.value
                requireComparableIntentVersion(control.fixedIntent, intent)
                val current = payments.lock(control.currentPaymentId) ?: error("order control references a missing payment")
                // AUTHN-001 판정 U-3: 결제창 시각이 지난 REQUESTED는 같은 TX에서 만료(세션·결제창 기록 닫기·PaymentExpired)한 뒤 판정한다 → CreateNewPayment(generation + 1).
                // 만료는 커밋되는 결과다(n-d). PaymentExpired(이전)가 PaymentRequested(새)보다 먼저 append된다(n-e).
                closing.closeIfElapsed(
                    acquired.control, current, now, ExpiryCausation(command.correlationId, command.newIdentifiers.idempotencyRecordId.value),
                )
                when (val decision = control.decide(intent, current.value)) {
                    is PaymentSlotDecision.CreateNewPayment -> {
                        val next = Payment.request(command.newIdentifiers.paymentId, command.orderKey, intent, decision.nextSlotGeneration, window)
                        control.occupy(next, current.value)
                        controls.update(control, acquired.control.version, now)
                        createPayment(next, command, snapshotVersion, now)
                    }
                    is PaymentSlotDecision.UseExistingPayment ->
                        StoredRequestResult.Succeeded(CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT, decision.paymentId)
                    is PaymentSlotDecision.FixedIntentConflict ->
                        StoredRequestResult.Rejected(CreatePaymentRejection.FIXED_INTENT_CONFLICT, decision.existingPaymentId)
                    is PaymentSlotDecision.Blocked -> StoredRequestResult.Rejected(rejectionOf(decision.reason), decision.referencePaymentId)
                }
            }
        }
    }

    private fun createPayment(
        payment: Payment,
        command: CreatePaymentCommand,
        snapshotVersion: EligibilitySnapshotVersion,
        now: Instant,
    ): StoredRequestResult {
        val attributes = PaymentCreationAttributes(
            paymentKey = command.newIdentifiers.paymentKey,
            merchantSiteId = command.orderIntent.merchantSiteId,
            money = command.orderIntent.money,
            paymentMethod = command.paymentMethod,
            orderName = command.orderName,
            eligibilitySnapshotVersion = snapshotVersion,
            createdAt = now,
            createdByCredentialId = command.actor,
        )
        val version = payments.insertNew(payment, attributes)
        outbox.append(
            PaymentRequestedEvents.of(
                payment = payment,
                attributes = attributes,
                aggregateVersion = version,
                eventId = command.newIdentifiers.eventId,
                correlationId = command.correlationId,
                causationId = command.newIdentifiers.idempotencyRecordId.value,
            ),
        )
        return StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, payment.id)
    }

    /** 저장된(또는 방금 쓴) 결과를 응답 값으로 만든다. Payment 값은 생성 시 불변 컬럼에서 읽는다(현재 상태 미포함, D-CP-003). */
    private fun present(command: CreatePaymentCommand, result: StoredRequestResult, replayed: Boolean): CreatePaymentResult =
        when (result) {
            is StoredRequestResult.Succeeded -> {
                val record = payments.findCreationRecord(result.paymentId) ?: error("stored result references a missing payment")
                // 멱등 범위(environment, MID)가 같으므로 다른 MID·환경의 Payment가 나올 수 없다. 나오면 저장 결함이다(노출 금지).
                check(
                    record.orderKey.environment == command.orderKey.environment &&
                        record.orderKey.merchantAccountId == command.orderKey.merchantAccountId,
                ) { "stored result is outside the caller's merchant scope" }
                CreatePaymentResult.Accepted(
                    outcome = result.outcome,
                    payment = CreatedPayment(
                        paymentId = record.paymentId,
                        paymentKey = record.attributes.paymentKey,
                        lifecycleAtCreation = PaymentStatus.REQUESTED,
                        paymentCreatedAt = record.attributes.createdAt,
                        merchantOrderId = record.orderKey.merchantOrderId,
                        money = record.attributes.money,
                    ),
                    replayed = replayed,
                )
            }
            is StoredRequestResult.Rejected -> CreatePaymentResult.Rejected(result.rejection, result.referencePaymentId, replayed)
        }

    /**
     * 요청 fingerprint는 저장된 control의 버전으로 계산해 비교한다(계약 §6.4). 지금은 v1만 있으므로 다른 버전이 저장돼 있으면 서로 다른 버전
     * 값을 직접 비교해 ORDER_INTENT_CONFLICT로 오분류하지 않도록 판정을 멈춘다(fail-closed, 새 Payment 없음 → 503, CP-API-001 명세 §6.4).
     */
    private fun requireComparableIntentVersion(stored: OrderIntentFingerprint, requested: OrderIntentFingerprint) {
        if (stored.value.substringBefore(':') != requested.value.substringBefore(':')) throw UnsupportedFingerprintVersionException("order intent")
    }

    private fun rejectionOf(reason: BlockReason): CreatePaymentRejection = when (reason) {
        BlockReason.INCIDENT_HOLD -> CreatePaymentRejection.INCIDENT_HOLD
        BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED -> CreatePaymentRejection.AUTHORIZED_NEW_ORDER_REQUIRED
        BlockReason.TERMINATION_EVIDENCE_INSUFFICIENT -> CreatePaymentRejection.TERMINATION_EVIDENCE_INSUFFICIENT
    }

    /** DB가 rollback을 확정한 교착과 내부 경합(오래된 판정·version 충돌·같은 generation 선점)만 같은 의도로 다시 판정한다. */
    private fun isRetryable(e: RuntimeException): Boolean = when (e) {
        is PersistenceFailure.Deadlock, is PersistenceFailure.OptimisticConflict, is StaleSlotException -> true
        is PersistenceFailure.UniqueConflict -> e.constraint == StoreConstraint.PAYMENT_ORDER_GENERATION
        else -> false
    }

    private fun toFailure(e: RuntimeException): CreatePaymentFailure? = when (e) {
        is CreatePaymentFailure -> e
        is PersistenceFailure.UniqueConflict -> when (e.constraint) {
            StoreConstraint.PAYMENT_ID, StoreConstraint.OUTBOX_EFFECT_KEY -> CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.PAYMENT_ID)
            StoreConstraint.PAYMENT_KEY -> CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.PAYMENT_KEY)
            StoreConstraint.OUTBOX_EVENT_ID -> CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.EVENT_ID)
            else -> CreatePaymentFailure.TemporarilyUnavailable(e)
        }
        is PaymentIdReuseException -> CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.PAYMENT_ID)
        is PersistenceFailure, is StaleSlotException, is UnsupportedFingerprintVersionException -> CreatePaymentFailure.TemporarilyUnavailable(e)
        else -> null
    }

    /** eligibility 거절로 TX를 rollback하기 위한 내부 신호(재시도 대상 아님). [create]가 결과로 바꾼다. 호출자에게 나가지 않는다. */
    private class EligibilityRefusedRollback(val refusal: EligibilityRefusal) : RuntimeException(null, null, true, false)

    private companion object {
        /** 멱등 기록 보존 기간(D-CPC-004, 사용자 확정 운영값). DB CHECK(expires_at = created_at + 30일)와 같은 값이다. */
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(30)

        /** 교착·내부 경합 재시도 상한(기술 설정, 업무 정책 아님). */
        const val MAX_ATTEMPTS = 3
    }
}
