package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmPaymentCommand
import com.paycore.payment.payment.application.provided.ConfirmPaymentFailure
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthenticationProfiles
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.AuthorizationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.AuthorizationTimings
import com.paycore.payment.payment.application.required.RecoverySchedule
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.EntitlementHeadLockPort
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.IdempotentOperation
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthorizationStart
import com.paycore.payment.payment.domain.AuthorizationStartCommand
import com.paycore.payment.payment.domain.AuthorizationStartRejection
import com.paycore.payment.payment.domain.AuthorizationStartResult
import com.paycore.payment.payment.domain.LimitBusinessDate
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.ProviderRequestRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * confirm TX1(AUTH-001 §4.2·§4.3 판정 순서 — 앞 단계가 결정하면 멈춤). 한 PAYMENT 로컬 TX, 잠금 순서 §4.7:
 * 멱등 기록 → head(S)(m-9) → [비잠금 Payment 소유 확인(n-3)] → control → payment → (인라인 만료: 세션 → 결제창 세션 → 토큰) / (재사용: payment_authorization 읽기) /
 * (새 시작: 인증 세션 → 일 한도 사용량). 외부 호출 없음(허용 판정·Route 등록부는 로컬). 결과는 같은 TX에 멱등 기록으로 남고 커밋 뒤 돌려준다.
 *
 * - 저장 없는 결과(404·503)는 TX를 rollback한다: Payment·세션·한도·멱등 예약 어느 것도 남지 않는다(D-AUTH1-001 — 정책 등록 뒤 같은 키 재시도로 시작).
 * - `at`은 사용량 잠금 **전에** 정하고(rev 0.3 m-1·AU-32), 잠금 뒤 Asia/Seoul 업무일이 바뀌었으면 TX를 처음부터 다시 한다. 같은 `at`을 허용 판정과 `AuthorizationStart`에 넘긴다
 *   (MS2 §6.4 — 정책 선택과 일 한도 날짜가 같은 시각).
 * - Route Capability 사전 검사는 통화를 `PaymentPlanRule`보다 먼저 본다(n-7): KRW 외 → PAYMENT_NOT_ELIGIBLE(D-AUTH1-002), 미지원 cardType·Route 할부 범위 밖 → PAYMENT_PLAN_NOT_ALLOWED.
 * - 교착·version 충돌·업무일 변경은 새 TX로 같은 의도를 최대 [MAX_ATTEMPTS]회 다시 판정한다.
 */
@Service
class ConfirmStartService(
    transactionManager: PlatformTransactionManager,
    private val idempotency: IdempotencyRecordStore,
    private val headLock: EntitlementHeadLockPort,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val sessions: AuthenticationSessionRepository,
    private val usages: DailyLimitUsageRepository,
    private val admission: AuthorizationAdmissionPort,
    private val routes: InstitutionRouteResolver,
    private val profiles: AuthenticationProfiles,
    private val closing: RequestedPaymentClosing,
    private val ids: AuthorizationIdentifierGenerator,
    private val schedules: AuthorizationRecoveryScheduleStore,
    private val timings: AuthorizationTimings,
    private val clock: PaymentClock,
) {
    private val log = LoggerFactory.getLogger(ConfirmStartService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    fun start(command: ConfirmPaymentCommand): ConfirmStartOutcome =
        try {
            transactions.execute(::isRetryable) { inTransaction(command) }
        } catch (e: RolledBack) {
            e.outcome ?: throw ConfirmPaymentFailure.TemporarilyUnavailable(e.reason)
        } catch (e: ConfirmPaymentFailure) {
            throw e
        } catch (e: CommitOutcomeUnknownException) {
            throw ConfirmPaymentFailure.OutcomeUnknown(e)
        } catch (e: CannotCreateTransactionException) {
            throw ConfirmPaymentFailure.TemporarilyUnavailable("TRANSACTION_UNAVAILABLE", e)
        } catch (e: UnexpectedRollbackException) {
            throw ConfirmPaymentFailure.TemporarilyUnavailable("ROLLED_BACK", e)
        } catch (e: BusinessDateChanged) {
            throw ConfirmPaymentFailure.TemporarilyUnavailable("BUSINESS_DATE_CHANGED", e)
        } catch (e: PersistenceFailure) {
            throw ConfirmPaymentFailure.TemporarilyUnavailable("STORE_" + e::class.simpleName?.uppercase(), e)
        }

    private fun inTransaction(command: ConfirmPaymentCommand): ConfirmStartOutcome {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val scope = IdempotencyScope(command.environment, command.merchantAccountId, IdempotentOperation.CONFIRM_PAYMENT, command.idempotencyKey)
        val fingerprint = ConfirmFingerprints.current(command)
        val reservation = IdempotencyReservation(scope, ids.idempotencyRecordId(), fingerprint, now, now.plus(IDEMPOTENCY_RETENTION))
        val reserved = try {
            idempotency.reserveOrLock(reservation)
        } catch (e: PersistenceFailure.LockTimeout) {
            throw ConfirmPaymentFailure.RequestInProgress(e)
        }
        if (reserved is IdempotencyReservationOutcome.Existing) {
            val record = reserved.record
            when {
                record.isExpiredAt(now) -> idempotency.renewExpired(reservation)
                !ConfirmFingerprints.isComparable(record.requestFingerprint) -> throw RolledBack(null, "UNSUPPORTED_FINGERPRINT_VERSION")
                record.requestFingerprint != fingerprint -> return ConfirmStartOutcome.IdempotencyKeyReused
                else -> return replay(checkNotNull(record.result) { "a committed idempotency record has a result" })
            }
        }
        val result = decide(command, reservation, now)
        idempotency.complete(scope, result, now)
        return when (result) {
            is StoredRequestResult.ConfirmAccepted -> ConfirmStartOutcome.Accepted(result.paymentId, result.authorizationId, result.outcome, replayed = false)
            is StoredRequestResult.ConfirmRejected -> ConfirmStartOutcome.Rejected(result.rejection, result.paymentId, result.detail, replayed = false)
            else -> error("unexpected confirm result")
        }
    }

    private fun replay(stored: StoredRequestResult): ConfirmStartOutcome = when (stored) {
        is StoredRequestResult.ConfirmAccepted -> ConfirmStartOutcome.Accepted(stored.paymentId, stored.authorizationId, stored.outcome, replayed = true)
        is StoredRequestResult.ConfirmRejected -> ConfirmStartOutcome.Rejected(stored.rejection, stored.paymentId, stored.detail, replayed = true)
        else -> error("a ConfirmPayment record holds a confirm result")
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private fun decide(command: ConfirmPaymentCommand, reservation: IdempotencyReservation, now: Instant): StoredRequestResult {
        headLock.lockForShare(command.merchantAccountId)
        // n-3: 비잠금 소유 확인을 control 잠금 전에 — 다른 MID·환경·없는 Payment는 아무 업무 행도 잠그지 않고 404.
        val creation = payments.findCreationRecord(PaymentId(command.paymentId))
            ?.takeIf { it.orderKey.environment == command.environment && it.orderKey.merchantAccountId == command.merchantAccountId }
            ?: throw RolledBack(ConfirmStartOutcome.NotFound, "PAYMENT_NOT_FOUND")
        val control = controls.lock(creation.orderKey) ?: error("a stored payment has its order control")
        val payment = payments.lock(creation.paymentId) ?: error("a stored payment can be locked")
        val p = payment.value

        // §4.3-4: 결제창 시각 먼저(REQUESTED에는 Authorization이 없다 — payment_authorization을 잠그기 전).
        val closed = closing.closeIfElapsed(control, payment, now, ExpiryCausation(command.correlationId, reservation.recordId.value))
        if (closed is ClosingOutcome.Closed) return rejected(ConfirmRejection.PAYMENT_TERMINATED, p.id)

        val restatementMatches = command.merchantOrderId == creation.orderKey.merchantOrderId.value && command.money == creation.attributes.money
        when (p.status) {
            PaymentStatus.AUTHORIZING, PaymentStatus.UNKNOWN, PaymentStatus.AUTHORIZED -> return reuseOrConflict(command, p, restatementMatches)
            PaymentStatus.FAILED, PaymentStatus.EXPIRED -> return rejected(ConfirmRejection.PAYMENT_TERMINATED, p.id)
            PaymentStatus.REQUESTED -> {}
        }
        if (!restatementMatches) return rejected(ConfirmRejection.CONFIRM_CONTENT_CONFLICT, p.id)
        val c = control.value
        if (c.currentPaymentId != p.id) error("a REQUESTED payment is the order's current payment")
        if (c.isOnIncidentHold()) return rejected(ConfirmRejection.INCIDENT_HOLD, p.id)
        if (c.authorizedPaymentId != null) return rejected(ConfirmRejection.AUTHORIZED_NEW_ORDER_REQUIRED, p.id)

        val session = sessionOf(command.authenticationRef)
        val money = creation.attributes.money
        if (session == null || session.value.binding.paymentId != p.id ||
            AuthorizationStart.preflight(c, p, session.value, money, now) != null
        ) {
            return rejected(ConfirmRejection.AUTHENTICATION_NOT_VERIFIED, p.id)
        }

        // m-1(AU-32): at을 사용량 잠금 전에 정하고, 잠금 뒤 업무일이 바뀌었으면 TX 재시작.
        val at = now
        val usage = usages.lockOrCreate(LimitUsageKey(p.orderKey.environment, p.orderKey.merchantAccountId, money.currency, LimitBusinessDate.of(at)), at)
        if (LimitBusinessDate.of(clock.now()) != usage.value.key.businessDate) throw BusinessDateChanged()

        val verified = checkNotNull(session.value.verified) { "a bindable session is VERIFIED" }
        val admitted = when (
            val a = admission.evaluate(p.orderKey.merchantAccountId, creation.attributes.merchantSiteId, money.currency, PaymentMethod.CARD, verified.acquirerCode, at)
        ) {
            is AdmissionResult.Admitted -> a
            is AdmissionResult.Refused -> return rejected(ConfirmRejection.PAYMENT_NOT_ELIGIBLE, p.id, a.reason.name)
            is AdmissionResult.Unavailable -> throw RolledBack(null, "ADMISSION_" + a.reason.name)
            AdmissionResult.NotFoundOrForbidden -> throw RolledBack(ConfirmStartOutcome.NotFound, "ADMISSION_NOT_FOUND")
        }
        val route = routes.resolve(admitted.route.routeRef) ?: run {
            log.warn("operation=ConfirmPayment correlationId={} result=TEMPORARILY_UNAVAILABLE failure=ROUTE_PROFILE_MISSING", command.correlationId)
            throw RolledBack(null, "ROUTE_PROFILE_MISSING")
        }
        // 검토 #3 n-4(§4.3-9): Route 환경 ≠ Payment 환경(예: LIVE Payment → TEST Simulator Route) → 503 fail-closed, 저장 없음(Capability 대조보다 먼저).
        if (route.environment != p.orderKey.environment) {
            log.warn("operation=ConfirmPayment correlationId={} result=TEMPORARILY_UNAVAILABLE failure=ROUTE_ENVIRONMENT_MISMATCH", command.correlationId)
            throw RolledBack(null, "ROUTE_ENVIRONMENT_MISMATCH")
        }
        val caps = route.capabilities
        if (money.currency !in caps.supportedCurrencies) return rejected(ConfirmRejection.PAYMENT_NOT_ELIGIBLE, p.id, CURRENCY_NOT_SUPPORTED)
        val plan = session.value.binding.plan
        val cardType = verified.cardType
        val planOutsideRoute = cardType !in caps.supportedCardTypes ||
            (plan is PaymentPlan.Installment && (cardType !in caps.installmentCardTypes || plan.months !in caps.installmentMonths))
        if (planOutsideRoute) return rejected(ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED, p.id)
        val planPolicy = profiles.profileFor(p.orderKey.environment)?.planPolicy ?: throw RolledBack(null, "AUTHENTICATION_PROFILE_MISSING")

        val generated = ids.next(at)
        val started = AuthorizationStart.start(
            AuthorizationStartCommand(
                control = c,
                payment = p,
                intent = OrderIntent(creation.attributes.merchantSiteId, money),
                session = session.value,
                admission = admitted.admission(route.protocolVersion),
                planPolicy = planPolicy,
                limitPolicy = admitted.limitPolicy,
                usage = usage.value,
                authorizationId = generated.authorizationId,
                attemptId = generated.attemptId,
                providerRequest = ProviderRequestRef(route.institution, p.orderKey.environment, admitted.route.institutionMerchantRef, OPERATION, generated.requestId),
                at = at,
            ),
        )
        return when (started) {
            is AuthorizationStartResult.Rejected -> when (val r = started.reason) {
                is AuthorizationStartRejection.AuthenticationNotVerified -> rejected(ConfirmRejection.AUTHENTICATION_NOT_VERIFIED, p.id)
                is AuthorizationStartRejection.PlanNotAllowed -> rejected(ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED, p.id)
                is AuthorizationStartRejection.LimitExceeded -> rejected(ConfirmRejection.LIMIT_EXCEEDED, p.id)
                is AuthorizationStartRejection.LimitPolicyUnavailable -> throw RolledBack(null, "LIMIT_POLICY_" + r.resolution::class.simpleName?.uppercase())
            }
            is AuthorizationStartResult.Started -> {
                authorizations.insertNew(started.authorization)
                controls.update(c, control.version, at)
                payments.update(p, payment.version)
                sessions.update(session.value, session.version)
                usages.update(usage.value, usage.version, at)
                // AU-16: 회복 일정(같은 TX) — 전송되지 않은 채 preparedDeadline이 지나면 회복이 fencing한다.
                schedules.insert(
                    RecoverySchedule(
                        generated.attemptId, started.authorization.id, p.id, p.orderKey, null, at.plus(timings.preparedDeadline), 0, null, null, timings.recoveryConfigVersion,
                    ),
                    at,
                )
                StoredRequestResult.ConfirmAccepted(ConfirmOutcome.ACCEPTED, p.id, started.authorization.id)
            }
        }
    }

    /** §4.3-5a: 승인이 이미 시작된 Payment — 요청 인증 참조가 그 승인에 귀속된 세션의 것이고 재진술이 같으면 재사용(새 Attempt·귀속 없음), 아니면 내용 충돌. */
    private fun reuseOrConflict(command: ConfirmPaymentCommand, p: Payment, restatementMatches: Boolean): StoredRequestResult {
        val authorization = authorizations.lockByPaymentId(p.id)?.value ?: return rejected(ConfirmRejection.CONFIRM_CONTENT_CONFLICT, p.id)
        val session = sessionOf(command.authenticationRef)?.value
        val sameRequest = restatementMatches && session != null && session.id == authorization.authenticationSessionId && session.boundAuthorizationId == authorization.id
        return if (sameRequest) {
            StoredRequestResult.ConfirmAccepted(ConfirmOutcome.REUSED_ACTIVE_AUTHORIZATION, p.id, authorization.id)
        } else {
            rejected(ConfirmRejection.CONFIRM_CONTENT_CONFLICT, p.id)
        }
    }

    private fun sessionOf(raw: String): Versioned<AuthenticationSession>? {
        val ref = runCatching { AuthenticationRef(raw) }.getOrNull() ?: return null
        val id = sessions.findIdByAuthenticationRef(ref) ?: return null
        return sessions.lock(id)
    }

    private fun rejected(rejection: ConfirmRejection, paymentId: PaymentId, detail: String? = null) = StoredRequestResult.ConfirmRejected(rejection, paymentId, detail)

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict || e is BusinessDateChanged

    /** 저장 없는 결과로 TX를 rollback하는 내부 신호. [outcome]이 null이면 일시 불가(503, [reason]). */
    private class RolledBack(val outcome: ConfirmStartOutcome?, val reason: String) : RuntimeException(reason, null, false, false)

    /** 사용량 잠금 대기 중 Asia/Seoul 업무일이 바뀌었다(AU-32) — 새 TX로 처음부터. */
    private class BusinessDateChanged : RuntimeException("limit business date changed while waiting", null, false, false)

    private companion object {
        val IDEMPOTENCY_RETENTION: Duration = Duration.ofDays(30)
        const val MAX_ATTEMPTS = 3
        const val OPERATION = "AUTHORIZE"
        const val CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED"
    }
}
