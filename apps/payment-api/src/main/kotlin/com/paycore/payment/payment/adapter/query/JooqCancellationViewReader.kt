package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.CANCELLATION_RECOVERY_SCHEDULE
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD_RELEASE
import com.paycore.payment.jooq.generated.Tables.PAYMENT
import com.paycore.payment.jooq.generated.Tables.CANCEL_ATTEMPT
import com.paycore.payment.jooq.generated.Tables.PAYMENT_AUTHORIZATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_CANCELLATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TRANSACTION
import com.paycore.payment.jooq.generated.Tables.RETURN_BALANCE
import com.paycore.payment.payment.application.required.CancelAttemptViewRow
import com.paycore.payment.payment.application.required.CancellationBacklogCounts
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.CancellationViewRow
import com.paycore.payment.payment.application.required.OperatorCancelTargetRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 취소 조회(CQRS 조회 측, jOOQ — CANCEL-001 §8.5): payment_cancellation + (있으면) CANCEL 거래 한 줄 + 반환 잔액. 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 읽기 전용 TX 하나(한 스냅샷),
 * **비권위** — 취소 API 응답·재생 표시와 운영자 승인 대상 재료에만 쓰고, 상태 변경은 JPA 잠금 재판정이 한다. 시각은 [UtcWallClock] 문자열 읽기(시간대 해석 없음).
 */
@Component
class JooqCancellationViewReader(private val dsl: DSLContext) : CancellationViewReader {

    @Transactional(readOnly = true)
    override fun find(cancellationId: CancellationId): CancellationViewRow? = try {
        val c = PAYMENT_CANCELLATION
        val t = PAYMENT_TRANSACTION
        val b = RETURN_BALANCE
        val confirmedAt = UtcWallClock.iso(t.CONFIRMED_AT)
        dsl.select(
            c.CANCELLATION_ID, c.ENVIRONMENT, c.MERCHANT_ACCOUNT_ID, c.MERCHANT_ORDER_ID, c.PAYMENT_ID, c.STATUS, c.AMOUNT_MINOR, c.CURRENCY, c.FAILURE_EVIDENCE, t.TRANSACTION_ID, t.APPROVAL_NUMBER, confirmedAt,
            b.AUTHORIZED_MINOR, b.COMPLETED_MINOR, b.RESERVED_MINOR, b.FORCED_MINOR,
        )
            .from(c)
            .join(b).on(b.PAYMENT_ID.eq(c.PAYMENT_ID))
            .leftJoin(t).on(t.CANCELLATION_ID.eq(c.CANCELLATION_ID).and(t.TYPE.eq("CANCEL")))
            .where(c.CANCELLATION_ID.eq(cancellationId.value))
            .fetchOne { r ->
                CancellationViewRow(
                    orderKey = OrderKey(Environment.valueOf(r.get(c.ENVIRONMENT)), MerchantAccountId(r.get(c.MERCHANT_ACCOUNT_ID)), MerchantOrderId(r.get(c.MERCHANT_ORDER_ID))),
                    paymentId = PaymentId(r.get(c.PAYMENT_ID)),
                    cancellationId = CancellationId(r.get(c.CANCELLATION_ID)),
                    status = CancellationStatus.valueOf(r.get(c.STATUS)),
                    money = Money(r.get(c.AMOUNT_MINOR), CurrencyCode(r.get(c.CURRENCY))),
                    failureEvidence = r.get(c.FAILURE_EVIDENCE)?.let(TerminationEvidence::valueOf),
                    cancelTransactionId = r.get(t.TRANSACTION_ID),
                    cancelApprovalNumber = r.get(t.APPROVAL_NUMBER),
                    confirmedAt = UtcWallClock.parse(r.get(confirmedAt)),
                    authorizedMinor = r.get(b.AUTHORIZED_MINOR),
                    completedMinor = r.get(b.COMPLETED_MINOR),
                    reservedMinor = r.get(b.RESERVED_MINOR),
                    forcedMinor = r.get(b.FORCED_MINOR),
                )
            }
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    /** 회복 Worker의 Attempt 정보(cancel_attempt + 원 Authorization의 Route). */
    @Transactional(readOnly = true)
    override fun findAttempt(attemptId: CancelAttemptId): CancelAttemptViewRow? = try {
        val t = CANCEL_ATTEMPT
        val c = PAYMENT_CANCELLATION
        val a = PAYMENT_AUTHORIZATION
        val preparedAt = UtcWallClock.iso(t.PREPARED_AT)
        dsl.select(
            t.ATTEMPT_ID, t.CANCELLATION_ID, t.STATE, preparedAt, t.INSTITUTION, t.ENVIRONMENT, t.INSTITUTION_ACCOUNT, t.OPERATION, t.REQUEST_ID, a.ROUTE_PROFILE,
            t.ROUTE_PROFILE_VERSION,
        )
            .from(t).join(c).on(c.CANCELLATION_ID.eq(t.CANCELLATION_ID)).join(a).on(a.AUTHORIZATION_ID.eq(c.AUTHORIZATION_ID))
            .where(t.ATTEMPT_ID.eq(attemptId.value))
            .fetchOne { r ->
                CancelAttemptViewRow(
                    attemptId = CancelAttemptId(r.get(t.ATTEMPT_ID)),
                    cancellationId = CancellationId(r.get(t.CANCELLATION_ID)),
                    state = AttemptState.valueOf(r.get(t.STATE)),
                    preparedAt = checkNotNull(UtcWallClock.parse(r.get(preparedAt))),
                    providerRequest = ProviderRequestRef(
                        r.get(t.INSTITUTION), Environment.valueOf(r.get(t.ENVIRONMENT)), r.get(t.INSTITUTION_ACCOUNT), r.get(t.OPERATION), r.get(t.REQUEST_ID),
                    ),
                    routeRef = r.get(a.ROUTE_PROFILE),
                    routeProfileVersion = r.get(t.ROUTE_PROFILE_VERSION),
                )
            }
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    /** 취소 적체 수(gauge — 비권위): UNKNOWN 취소 수·그 Attempt 중 가장 이른 준비 시각·이관 뒤 닫히지 않은 일정 수(한 읽기 TX). */
    @Transactional(readOnly = true)
    override fun backlog(): CancellationBacklogCounts = try {
        val c = PAYMENT_CANCELLATION
        val t = CANCEL_ATTEMPT
        val s = CANCELLATION_RECOVERY_SCHEDULE
        val oldest = UtcWallClock.iso(DSL.min(t.PREPARED_AT))
        val unknown = dsl.select(DSL.count(), oldest).from(c).join(t).on(t.CANCELLATION_ID.eq(c.CANCELLATION_ID)).where(c.STATUS.eq("UNKNOWN")).fetchOne()
        val escalated = dsl.selectCount().from(s).where(s.ESCALATED_AT.isNotNull.and(s.CLOSED_AT.isNull)).fetchOne(0, Long::class.java) ?: 0L
        CancellationBacklogCounts(
            unknownCancellations = unknown?.get(0, Long::class.java) ?: 0L,
            oldestUnknownPreparedAt = unknown?.get(oldest)?.let(UtcWallClock::parse),
            escalatedOpenSchedules = escalated,
        )
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    /**
     * ADMIN-PAYMENT-OPS-001 F-2·F-3: `payment`에서 시작한다(승인 없는 Payment도 행 — cancellable=false). 상태·풀리지 않은 hold·진행 중 취소(생성 열 `in_flight_payment_id`)를 함께 읽는다.
     * 관리 조회라 질의 시간 상한 hint(`MAX_EXECUTION_TIME`, §4.7 — 서버 강제, 초과 3024 → [QueryTimedOut])를 붙인다.
     */
    @Transactional(readOnly = true)
    override fun findTarget(paymentId: PaymentId): OperatorCancelTargetRow? = AdminReadLimits.guard {
        val p = PAYMENT
        val a = PAYMENT_AUTHORIZATION
        val t = PAYMENT_TRANSACTION
        val b = RETURN_BALANCE
        val h = INCIDENT_HOLD
        val r = INCIDENT_HOLD_RELEASE
        val c = PAYMENT_CANCELLATION
        val onHold = DSL.field(DSL.exists(DSL.selectOne().from(h).where(h.PAYMENT_ID.eq(p.PAYMENT_ID)).andNotExists(DSL.selectOne().from(r).where(r.INCIDENT_HOLD_ID.eq(h.ID)))))
        val inFlight = DSL.field(DSL.exists(DSL.selectOne().from(c).where(c.IN_FLIGHT_PAYMENT_ID.eq(p.PAYMENT_ID))))
        dsl.select(p.PAYMENT_ID, p.STATUS, t.AMOUNT_MINOR, t.CURRENCY, b.PAYMENT_ID, b.COMPLETED_MINOR, b.RESERVED_MINOR, b.FORCED_MINOR, b.REVISION, onHold, inFlight)
            .hint(AdminReadLimits.HINT)
            .from(p)
            .leftJoin(a).on(a.PAYMENT_ID.eq(p.PAYMENT_ID))
            .leftJoin(t).on(t.AUTHORIZATION_ID.eq(a.AUTHORIZATION_ID).and(t.TYPE.eq("AUTHORIZATION")))
            .leftJoin(b).on(b.PAYMENT_ID.eq(p.PAYMENT_ID))
            .where(p.PAYMENT_ID.eq(paymentId.value))
            .fetchOne { row ->
                val hasBalance = row.get(b.PAYMENT_ID) != null
                OperatorCancelTargetRow(
                    paymentId = PaymentId(row.get(p.PAYMENT_ID)),
                    authorizedMoney = row.get(t.AMOUNT_MINOR)?.let { Money(it, CurrencyCode(row.get(t.CURRENCY))) },
                    hasBalance = hasBalance,
                    completedMinor = if (hasBalance) row.get(b.COMPLETED_MINOR) else 0,
                    reservedMinor = if (hasBalance) row.get(b.RESERVED_MINOR) else 0,
                    forcedMinor = if (hasBalance) row.get(b.FORCED_MINOR) else 0,
                    balanceRevision = if (hasBalance) row.get(b.REVISION) else 0,
                    paymentStatus = row.get(p.STATUS),
                    onHold = row.get(onHold) == true,
                    inFlightCancel = row.get(inFlight) == true,
                )
            }
    }
}
