package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.IdempotentOperation
import com.paycore.payment.payment.application.required.StoredCancelAccepted
import com.paycore.payment.payment.application.required.StoredCancelRejected
import com.paycore.payment.payment.application.required.StoredIdempotencyRecord
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaIdempotencyRecordStore(
    private val requests: IdempotencyRequestJpaRepository,
    private val entityManager: EntityManager,
) : IdempotencyRecordStore {

    override fun reserveOrLock(reservation: IdempotencyReservation): IdempotencyReservationOutcome = translate(ENTITY) {
        val s = reservation.scope
        requests.insertPendingIfAbsent(
            s.environment.name, s.merchantAccountId.value, s.operation.code, s.key.value, reservation.recordId.value,
            reservation.requestFingerprint.value, reservation.createdAt, reservation.expiresAt,
        )
        val row = lockedRow(s)
        if (row.requestRecordId == reservation.recordId.value && row.resultKind == PENDING) {
            IdempotencyReservationOutcome.Reserved
        } else {
            IdempotencyReservationOutcome.Existing(toStored(row))
        }
    }

    override fun renewExpired(reservation: IdempotencyReservation): Unit = translate(ENTITY) {
        val row = lockedRow(reservation.scope)
        // 삭제 전 만료 행만 새 요청으로 갱신한다. 보존 기간 안의 기록은 덮어쓰지 않는다(D-CPC-004).
        check(!reservation.createdAt.isBefore(row.expiresAt)) { "a live idempotency record cannot be renewed" }
        row.requestRecordId = reservation.recordId.value
        row.requestFingerprint = reservation.requestFingerprint.value
        row.resultKind = PENDING
        row.resultCode = null
        row.resultPaymentId = null
        row.resultAuthorizationId = null
        row.resultCancellationId = null
        row.resultDetail = null
        row.resultMerchantCode = null
        row.createdAt = reservation.createdAt
        row.expiresAt = reservation.expiresAt
        row.updatedAt = reservation.createdAt
        entityManager.flush()
    }

    override fun complete(scope: IdempotencyScope, result: StoredRequestResult, completedAt: Instant): Unit = translate(ENTITY) {
        val row = lockedRow(scope)
        check(row.resultKind == PENDING) { "idempotency record already has a result" }
        when (result) {
            is StoredRequestResult.Succeeded -> {
                row.resultKind = SUCCESS
                row.resultCode = result.outcome.name
                row.resultPaymentId = result.paymentId.value
                // C-37(MC-4): 생성 결과와 같은 TX·같은 행에 그 요청 때의 가맹점 코드를 남긴다(재생은 이 값만 — 응답 시점 조회 없음).
                row.resultMerchantCode = result.merchantCode
            }
            is StoredRequestResult.Rejected -> {
                row.resultKind = REJECTED
                row.resultCode = result.rejection.name
                row.resultPaymentId = result.referencePaymentId.value
            }
            is StoredRequestResult.ConfirmAccepted -> {
                check(row.operation == IdempotentOperation.CONFIRM_PAYMENT.code) { "a confirm result belongs to a ConfirmPayment record" }
                row.resultKind = SUCCESS
                row.resultCode = result.outcome.name
                row.resultPaymentId = result.paymentId.value
                row.resultAuthorizationId = result.authorizationId.value
            }
            is StoredRequestResult.ConfirmRejected -> {
                check(row.operation == IdempotentOperation.CONFIRM_PAYMENT.code) { "a confirm result belongs to a ConfirmPayment record" }
                row.resultKind = REJECTED
                row.resultCode = result.rejection.name
                row.resultPaymentId = result.paymentId.value
                row.resultDetail = result.detail
            }
            is StoredCancelAccepted -> {
                check(row.operation == IdempotentOperation.CANCEL_PAYMENT.code) { "a cancel result belongs to a CancelPayment record" }
                row.resultKind = SUCCESS
                row.resultCode = result.outcome.name
                row.resultPaymentId = result.paymentId.value
                row.resultCancellationId = result.cancellationId.value
            }
            is StoredCancelRejected -> {
                check(row.operation == IdempotentOperation.CANCEL_PAYMENT.code) { "a cancel result belongs to a CancelPayment record" }
                row.resultKind = REJECTED
                row.resultCode = result.rejection.name
                row.resultPaymentId = result.paymentId.value
            }
        }
        row.updatedAt = completedAt
        entityManager.flush()
    }

    /** 같은 TX에서 이미 잠갔으면 영속성 컨텍스트의 같은 Entity가 돌아온다(추가 SQL 잠금은 재진입). */
    private fun lockedRow(s: IdempotencyScope): JpaIdempotencyRequestEntity =
        requests.lockByScope(s.environment.name, s.merchantAccountId.value, s.operation.code, s.key.value)
            ?: error("idempotency record must exist after insert-if-absent")

    private fun toStored(row: JpaIdempotencyRequestEntity): StoredIdempotencyRecord =
        StoredIdempotencyRecord(
            recordId = IdempotencyRecordId(row.requestRecordId),
            requestFingerprint = RequestFingerprint(row.requestFingerprint),
            result = when {
                row.resultKind == PENDING -> null
                row.operation == IdempotentOperation.CANCEL_PAYMENT.code -> when (row.resultKind) {
                    SUCCESS -> StoredCancelAccepted(
                        CancelOutcome.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)), CancellationId(checkNotNull(row.resultCancellationId)),
                    )
                    REJECTED -> StoredCancelRejected(CancelRejection.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)))
                    else -> error("unknown idempotency result kind")
                }
                row.operation == IdempotentOperation.CONFIRM_PAYMENT.code -> when (row.resultKind) {
                    SUCCESS -> StoredRequestResult.ConfirmAccepted(
                        ConfirmOutcome.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)), AuthorizationId(checkNotNull(row.resultAuthorizationId)),
                    )
                    REJECTED -> StoredRequestResult.ConfirmRejected(ConfirmRejection.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)), row.resultDetail)
                    else -> error("unknown idempotency result kind")
                }
                row.resultKind == SUCCESS -> StoredRequestResult.Succeeded(
                    CreatePaymentOutcome.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)), row.resultMerchantCode,
                )
                row.resultKind == REJECTED -> StoredRequestResult.Rejected(CreatePaymentRejection.valueOf(checkNotNull(row.resultCode)), PaymentId(checkNotNull(row.resultPaymentId)))
                else -> error("unknown idempotency result kind")
            },
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
        )

    private companion object {
        const val ENTITY = "idempotency_request"
        const val PENDING = "PENDING"
        const val SUCCESS = "SUCCESS"
        const val REJECTED = "REJECTED"
    }
}
