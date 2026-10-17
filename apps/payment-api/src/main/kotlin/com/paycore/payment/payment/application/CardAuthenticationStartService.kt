package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.StartCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.StartCardAuthenticationResult
import com.paycore.payment.payment.application.provided.StartCardAuthenticationUseCase
import com.paycore.payment.payment.application.required.AuthenticationProfiles
import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CardAuthenticationEndpoints
import com.paycore.payment.payment.application.required.CardAuthenticationPort
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.CheckoutPolicy
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSecretGenerator
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.RegistrationFailure
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.AuthenticationCorrelation
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationExpiryCause
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthenticationSessionStart
import com.paycore.payment.payment.domain.AuthenticationStatus
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentPlanRule
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 인증 시작(AUTHN-001 §4.1·§4.2·§7.2·§7.4.1). TX를 열지 않는 조정자:
 * - **TX-A** payment(X) → authentication_session(X, 같은 Payment 전부) → checkout_session(X): 결제창 세션 재확인([CheckoutSessionGate] — Payment 불변), 카드사 allowlist·
 *   Plan 사전 판정(`PaymentPlanRule.precheck` — 서버 값, A05), 새 세션 만료 = min(now + 인증 TTL, 결제창 세션 만료), `AuthenticationSessionStart`(미폐기·미귀속 세션
 *   SUPERSEDED/TTL_ELAPSED → 새 CREATED) + 결정 로그(EXPIRED·CREATED) → 커밋.
 * - **등록(TX 밖, 잠금 없음)** `CardAuthenticationPort.register`(바인딩, state 원문, returnUrl = 설정 origin + handle 경로 — m-9·m-1).
 * - **TX-B** payment(X) → 세션(X): CREATED면 IN_PROGRESS(기관 거래 참조) + STARTED. 다른 상태면 변경 없음 → Superseded(C3). Payment 종료·결제창 시각 경과면 세션
 *   EXPIRED(PAYMENT_CLOSED)·결제창 세션 닫음(Payment 불변).
 * - 등록 실패·timeout: 짧은 TX에서 세션이 아직 CREATED면 REGISTRATION_FAILED(분류) 1회 — 이 TX가 실패해도 로그만(이벤트는 감사 보조). 같은 state로 재등록하지 않는다.
 */
@Service
class CardAuthenticationStartService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
    private val gate: CheckoutSessionGate,
    private val profiles: AuthenticationProfiles,
    private val endpoints: CardAuthenticationEndpoints,
    private val policy: CheckoutPolicy,
    private val secrets: CheckoutSecretGenerator,
    private val institution: CardAuthenticationPort,
) : StartCardAuthenticationUseCase {

    private val log = LoggerFactory.getLogger(CardAuthenticationStartService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    /** TX-A 결과: 끝난 결과이거나, 등록할 새 세션. */
    private sealed interface Prepared {
        data class Done(val result: StartCardAuthenticationResult) : Prepared

        data class Created(val id: AuthenticationSessionId, val checkoutSessionId: CheckoutSessionId, val registration: CardAuthenticationRegistration) : Prepared
    }

    override fun start(command: StartCardAuthenticationCommand): StartCardAuthenticationResult {
        if (!gate.wellFormed(command.handle, command.cookieSecret)) return StartCardAuthenticationResult.SessionExpired
        val prepared = guarded(command) {
            // 비잠금 대상 식별(handle·쿠키 digest) → 같은 TX에서 순서대로 잠가 재판정(§10.3, 검토 #2 m-1).
            val found = gate.find(command.handle, command.cookieSecret)
            if (found == null) Prepared.Done(closedOrExpired(gate.findMatching(command.handle, command.cookieSecret))) else prepare(command, found.id, found.paymentId)
        } ?: return StartCardAuthenticationResult.Unavailable
        val created = when (prepared) {
            is Prepared.Done -> return prepared.result
            is Prepared.Created -> prepared
        }
        // 외부 호출: DB TX·잠금 없이(C10).
        return when (val registered = institution.register(created.registration)) {
            is RegistrationResult.Registered ->
                if (registered.customerUrl.startsWith(endpoints.customerOrigin + "/")) {
                    guarded(command) { recordStarted(created, registered, command.correlationId) } ?: StartCardAuthenticationResult.Unavailable
                } else {
                    recordRegistrationFailed(created, RegistrationFailure.MALFORMED, command.correlationId)
                }
            is RegistrationResult.Failed -> recordRegistrationFailed(created, registered.reason, command.correlationId)
        }
    }

    private fun prepare(command: StartCardAuthenticationCommand, checkoutSessionId: CheckoutSessionId, paymentId: PaymentId): Prepared {
        val now = now()
        val payment = payments.lock(paymentId) ?: error("a checkout session references a stored payment")
        val ofPayment = sessions.lockAllOfPayment(paymentId)
        val cs = when (val g = gate.gate(payment.value, ofPayment, checkoutSessionId, now, command.correlationId)) {
            is CheckoutGate.Open -> g.checkoutSession
            // 식별과 잠금 사이에 control 보유 경로가 결제창을 닫았을 수 있다 → 잠금 아래 기록으로 같은 판정(검토 #4 m-1 후속).
            CheckoutGate.Expired -> return Prepared.Done(closedOrExpired(gate.lockedRecord(checkoutSessionId)))
            is CheckoutGate.PaymentNotAvailable -> return Prepared.Done(StartCardAuthenticationResult.PaymentNotAvailable(g.failRedirect))
            is CheckoutGate.TimeOver -> return Prepared.Done(StartCardAuthenticationResult.CheckoutTimeOver(g.failRedirect))
        }
        val creation = payments.findCreationRecord(paymentId) ?: error("a locked payment has a creation record")
        val profile = profiles.profileFor(creation.orderKey.environment) ?: return Prepared.Done(StartCardAuthenticationResult.NotAllowed)
        val cardCompany = command.cardCompany?.takeIf(profile::allows) ?: return Prepared.Done(StartCardAuthenticationResult.NotAllowed)
        val plan = planOf(command.plan) ?: return Prepared.Done(StartCardAuthenticationResult.NotAllowed)
        val money = creation.attributes.money
        if (plan is PaymentPlan.Installment &&
            (profile.planPolicy.currency != money.currency || PaymentPlanRule.precheck(plan, money, profile.planPolicy) != null)
        ) {
            return Prepared.Done(StartCardAuthenticationResult.NotAllowed)
        }

        val expiresAt = minOf(now.plus(policy.authenticationTtl), cs.value.expiresAt)
        val state = secrets.newState()
        val binding = AuthenticationBinding(paymentId, creation.orderKey, money, plan, cardCompany)
        val started = AuthenticationSessionStart.start(
            payment.value, ofPayment.map { it.value }, secrets.newAuthenticationSessionId(), binding,
            AuthenticationCorrelation(SecretDigest.of(SecretKind.AUTHENTICATION_STATE, state)), expiresAt, now,
        )
        val supersededIds = started.superseded.map { it.id }.toSet()
        ofPayment.filter { it.value.id in supersededIds }.forEach { s ->
            sessions.update(s.value, s.version)
            val cause = checkNotNull(s.value.expiryCause) { "a superseded session has an expiry cause" }
            events.append(AuthenticationSessionEventRecord(s.value.id, AuthenticationEventKind.EXPIRED, detailOf(cause), now, command.correlationId))
        }
        sessions.insertNew(started.session, AuthenticationSessionAttributes(cs.value.id, profile.id, profile.planPolicy.version, policy.policyVersion))
        events.append(AuthenticationSessionEventRecord(started.session.id, AuthenticationEventKind.CREATED, null, now, command.correlationId))
        // handle은 base64url 22자로 검증된 값이라 경로 encode가 필요 없다(m-3 — 문자 집합이 경로 안전 문자뿐).
        val returnUrl = policy.publicBaseUrl + "/checkout/s/" + command.handle + "/authentication/return"
        return Prepared.Created(started.session.id, cs.value.id, CardAuthenticationRegistration(binding, state, returnUrl))
    }

    /**
     * 쿠키가 맞는 결제창이 Payment 쪽 사유로 이미 닫혔으면(EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE)) 401이 아니라 failUrl 안내 — Payment REQUESTED·EXPIRED면
     * 410 [StartCardAuthenticationResult.CheckoutTimeOver](CHECKOUT_EXPIRED), 그 밖 409 [StartCardAuthenticationResult.PaymentNotAvailable](PAYMENT_NOT_AVAILABLE).
     * 상태를 바꾸지 않고 기관 등록도 하지 않는다(검토 #4 m-1 후속, Coordinator 판정 2026-10-17 — 화면·복귀와 같은 규칙). 그 밖(TTL·대체·취소·쿠키 불일치)은 SessionExpired.
     */
    private fun closedOrExpired(cs: CheckoutSessionRecord?): StartCardAuthenticationResult {
        if (cs == null || !CheckoutSessionGate.closedForPayment(cs)) return StartCardAuthenticationResult.SessionExpired
        val payment = payments.lock(cs.paymentId) ?: error("a checkout session references a stored payment")
        val code = CheckoutSessionGate.closedCode(payment.value.status)
        val redirect = CheckoutSessionGate.failRedirect(cs, code)
        return if (code == CheckoutFailCode.CHECKOUT_EXPIRED) StartCardAuthenticationResult.CheckoutTimeOver(redirect) else StartCardAuthenticationResult.PaymentNotAvailable(redirect)
    }

    private fun recordStarted(created: Prepared.Created, registered: RegistrationResult.Registered, correlationId: String): StartCardAuthenticationResult {
        val now = now()
        val payment = payments.lock(created.registration.binding.paymentId) ?: error("a started session references a stored payment")
        val s = sessions.lock(created.id) ?: error("a created session can be locked")
        if (s.value.status != AuthenticationStatus.CREATED) {
            // 등록 중 control 보유 경로(만료 작업·inline 만료)가 Payment를 닫아 세션이 EXPIRED(PAYMENT_CLOSED)가 됐다 → "다른 창"이 아니라 결제 불가/시간 경과(§7.4.1, C15).
            if (s.value.expiryCause == AuthenticationExpiryCause.PAYMENT_CLOSED) {
                val notRequested = payment.value.status != PaymentStatus.REQUESTED
                val code = if (notRequested) CheckoutFailCode.PAYMENT_NOT_AVAILABLE else CheckoutFailCode.CHECKOUT_EXPIRED
                val redirect = gate.failRedirectOf(created.checkoutSessionId, code) ?: return StartCardAuthenticationResult.SessionExpired
                return if (notRequested) StartCardAuthenticationResult.PaymentNotAvailable(redirect) else StartCardAuthenticationResult.CheckoutTimeOver(redirect)
            }
            return StartCardAuthenticationResult.Superseded
        }
        when (val g = gate.gate(payment.value, listOf(s), created.checkoutSessionId, now, correlationId)) {
            is CheckoutGate.Open -> Unit
            CheckoutGate.Expired -> return StartCardAuthenticationResult.SessionExpired
            is CheckoutGate.PaymentNotAvailable -> {
                gate.expireOpen(listOf(s), AuthenticationExpiryCause.PAYMENT_CLOSED, now, correlationId)
                return StartCardAuthenticationResult.PaymentNotAvailable(g.failRedirect)
            }
            is CheckoutGate.TimeOver -> return StartCardAuthenticationResult.CheckoutTimeOver(g.failRedirect)
        }
        s.value.start(registered.providerTransactionRef)
        sessions.update(s.value, s.version)
        events.append(AuthenticationSessionEventRecord(s.value.id, AuthenticationEventKind.STARTED, null, now, correlationId))
        check(payment.value.status == PaymentStatus.REQUESTED) { "a started authentication belongs to a REQUESTED payment" }
        return StartCardAuthenticationResult.Redirect(registered.customerUrl)
    }

    private fun recordRegistrationFailed(created: Prepared.Created, reason: RegistrationFailure, correlationId: String): StartCardAuthenticationResult {
        log.warn("operation=StartCardAuthentication correlationId={} result=REGISTRATION_FAILED failure={}", correlationId, reason)
        try {
            transactions.execute(::isRetryable) {
                val now = now()
                // 잠금 순서 payment → authentication_session(§10.3).
                payments.lock(created.registration.binding.paymentId) ?: error("a started session references a stored payment")
                val s = sessions.lock(created.id) ?: error("a created session can be locked")
                if (s.value.status == AuthenticationStatus.CREATED) {
                    events.append(AuthenticationSessionEventRecord(created.id, AuthenticationEventKind.REGISTRATION_FAILED, detailOf(reason), now, correlationId))
                }
            }
        } catch (e: RuntimeException) {
            // 이벤트는 감사 보조다(상태 사실 아님) — 기록 실패는 로그만 남기고 같은 503 화면(§7.4.1).
            log.warn("operation=StartCardAuthentication correlationId={} result=REGISTRATION_FAILED_EVENT_NOT_RECORDED exception={}", correlationId, e.javaClass.name)
        }
        return StartCardAuthenticationResult.RegistrationFailed
    }

    private fun <T : Any> guarded(command: StartCardAuthenticationCommand, block: () -> T): T? = try {
        transactions.execute(::isRetryable) { block() }
    } catch (e: CommitOutcomeUnknownException) {
        unavailable(command, e)
    } catch (e: CannotCreateTransactionException) {
        unavailable(command, e)
    } catch (e: UnexpectedRollbackException) {
        unavailable(command, e)
    } catch (e: PersistenceFailure) {
        unavailable(command, e)
    }

    private fun <T> unavailable(command: StartCardAuthenticationCommand, e: Throwable): T? {
        log.warn("operation=StartCardAuthentication correlationId={} result=UNAVAILABLE exception={}", command.correlationId, e.javaClass.name)
        return null
    }

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        val INSTALLMENT = Regex("^INSTALLMENT_([1-9][0-9]?)$")

        fun planOf(raw: String?): PaymentPlan? = when {
            raw == "LUMP_SUM" -> PaymentPlan.LumpSum
            raw != null && INSTALLMENT.matches(raw) -> runCatching { PaymentPlan.Installment(INSTALLMENT.find(raw)!!.groupValues[1].toInt()) }.getOrNull()
            else -> null
        }

        fun detailOf(cause: AuthenticationExpiryCause): AuthenticationEventDetail = AuthenticationEventDetail.valueOf(cause.name)

        fun detailOf(reason: RegistrationFailure): AuthenticationEventDetail = when (reason) {
            RegistrationFailure.TIMEOUT -> AuthenticationEventDetail.TIMEOUT
            RegistrationFailure.CONNECTION -> AuthenticationEventDetail.CONNECTION
            RegistrationFailure.SERVER_ERROR -> AuthenticationEventDetail.SERVER_ERROR
            RegistrationFailure.MALFORMED -> AuthenticationEventDetail.MALFORMED
            RegistrationFailure.REJECTED -> AuthenticationEventDetail.REJECTED
        }
    }
}
