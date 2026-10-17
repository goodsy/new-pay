package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationResult
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CardAuthenticationPort
import com.paycore.payment.payment.application.required.CheckoutSecretGenerator
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationFailure
import com.paycore.payment.payment.domain.AuthenticationResultCheck
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.PresentedResult
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.ResultCheckDecision
import com.paycore.payment.payment.domain.ResultVerdict
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
 * 인증 결과 반영(AUTHN-001 §7.3, D-AUTHN-003 fail-closed). TX를 열지 않는 조정자:
 * 1. query 고정 문법(`resultRef` = `srr_`+22, `state` = 43자, 각 하나, 다른 이름 없음) — 위반은 Rejected(조회하지 않음, m-3).
 * 2. 경로 handle + 쿠키 → 결제창 세션(digest 일치, 미만료, ACTIVE 또는 COMPLETED(VERIFIED_REDIRECT) — DUPLICATE 재표시, m-D). 아니면 SessionExpired.
 * 3. 세션 선택: 그 결제창 세션의 인증 세션 중 state digest 일치(UNIQUE). 없으면 Rejected + 보안 로그(행 없음 — n-4). 틀린 state로는 어떤 세션도 고르지 못한다(R10).
 * 4. 1단계 TX payment(X) → 세션(X) → 결제창 세션(X): `AuthenticationResultCheck.beforeLookup`(P1~P6) — 결정이면 그 결과를 커밋하고 응답.
 * 5. 조회(**TX 밖**, 잠금 없음 — C10): `CardAuthenticationPort.lookup`(읽기 전용·멱등).
 * 6. TX-C(같은 잠금 순서): `afterLookup`(1단계 재판정 → R3·R6·R5·R7~R11). VERIFIED면 결제창 세션 COMPLETED(VERIFIED_REDIRECT). 결과 참조 UNIQUE 1062(다른 세션이 이미 쓴
 *    결과 참조)는 rollback 뒤 새 TX에서 상태 불변 Rejected + REJECTED_RESULT(RESULT_REF_REUSED)(보안).
 * 결정 로그: 상태 전이 종류는 1회, 반복 종류는 (종류, 상세)당 1행(§7.6 — Store가 ALREADY_RECORDED로 거름).
 */
@Service
class CardAuthenticationCompletionService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val secrets: CheckoutSecretGenerator,
    private val institution: CardAuthenticationPort,
) : CompleteCardAuthenticationUseCase {

    private val log = LoggerFactory.getLogger(CardAuthenticationCompletionService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    private data class Selected(val checkoutSession: CheckoutSessionRecord, val sessionId: AuthenticationSessionId, val presented: PresentedResult, val resultRef: String)

    /** 1단계 결과: 끝난 결과이거나 조회로 진행. */
    private sealed interface FirstStep {
        data class Done(val result: CompleteCardAuthenticationResult) : FirstStep

        data object Proceed : FirstStep
    }

    override fun complete(command: CompleteCardAuthenticationCommand): CompleteCardAuthenticationResult {
        val resultRef = command.parameters.singleValue(RESULT_REF_PARAM)?.takeIf { RESULT_REF.matches(it) }
        val state = command.parameters.singleValue(STATE_PARAM)?.takeIf { STATE.matches(it) }
        if (command.parameters.keys != setOf(RESULT_REF_PARAM, STATE_PARAM) || resultRef == null || state == null) {
            return CompleteCardAuthenticationResult.Rejected
        }
        val selected = try {
            transactions.execute(::isRetryable) { select(command, resultRef, state) }
        } catch (e: RuntimeException) {
            return failure(command, e) ?: throw e
        } ?: return CompleteCardAuthenticationResult.SessionExpired
        if (selected is CompleteCardAuthenticationResult) return selected
        val s = selected as Selected

        when (val first = guarded(command) { firstStep(command, s) } ?: return CompleteCardAuthenticationResult.Unavailable) {
            is FirstStep.Done -> return first.result
            FirstStep.Proceed -> Unit
        }
        // 외부 조회: DB TX·잠금 없이(C10). 결과는 TX-C가 잠금 아래에서 다시 판정한다.
        val lookup = institution.lookup(s.resultRef)
        return try {
            transactions.execute(::isRetryable) { secondStep(command, s, lookup) }
        } catch (e: PersistenceFailure.UniqueConflict) {
            if (e.constraint != StoreConstraint.AUTHENTICATION_SESSION_RESULT_REF) throw e
            guarded(command) { resultRefReused(command, s) } ?: CompleteCardAuthenticationResult.Unavailable
        } catch (e: RuntimeException) {
            failure(command, e) ?: throw e
        }
    }

    /** 결제창 세션 확인 + state로 세션 선택(비잠금). null = SessionExpired, Rejected = 세션 없음. */
    private fun select(command: CompleteCardAuthenticationCommand, resultRef: String, state: String): Any? {
        val secret = command.cookieSecret?.takeIf { CheckoutSessionGate.SECRET.matches(it) } ?: return null
        if (!CheckoutSessionGate.HANDLE.matches(command.handle)) return null
        val cs = checkoutSessions.findByHandle(CheckoutHandle(command.handle)) ?: return null
        if (cs.digest != SecretDigest.of(SecretKind.CHECKOUT_SESSION, secret)) return null
        if (!usable(cs, now())) {
            val payment = payments.lock(cs.paymentId) ?: error("a checkout session references a stored payment")
            return closedResult(cs, payment.value.status)
        }
        val stateDigest = SecretDigest.of(SecretKind.AUTHENTICATION_STATE, state)
        val id = sessions.findIdByState(cs.id, stateDigest) ?: run {
            log.warn("operation=CompleteCardAuthentication correlationId={} security=STATE_MISMATCH checkoutSessionId={}", command.correlationId, cs.id.value)
            return CompleteCardAuthenticationResult.Rejected
        }
        return Selected(cs, id, PresentedResult(stateDigest, SecretDigest.of(SecretKind.RESULT_REFERENCE, resultRef)), resultRef)
    }

    private fun firstStep(command: CompleteCardAuthenticationCommand, s: Selected): FirstStep {
        val now = now()
        val payment = payments.lock(s.checkoutSession.paymentId) ?: error("a checkout session references a stored payment")
        val session = sessions.lock(s.sessionId) ?: error("a selected session can be locked")
        val cs = checkoutSessions.lock(s.checkoutSession.id) ?: error("a read checkout session can be locked")
        if (!usable(cs.value, now)) return FirstStep.Done(closedResult(cs.value, payment.value.status) ?: CompleteCardAuthenticationResult.SessionExpired)
        val decision = AuthenticationResultCheck.beforeLookup(session.value, payment.value, s.presented, now)
        if (decision.verdict == ResultVerdict.PROCEED) return FirstStep.Proceed
        return FirstStep.Done(apply(command, decision, session.value, session.version, cs, payment.value.status, now))
    }

    private fun secondStep(command: CompleteCardAuthenticationCommand, s: Selected, lookup: ProviderResultLookup): CompleteCardAuthenticationResult {
        val now = now()
        val payment = payments.lock(s.checkoutSession.paymentId) ?: error("a checkout session references a stored payment")
        val session = sessions.lock(s.sessionId) ?: error("a selected session can be locked")
        val cs = checkoutSessions.lock(s.checkoutSession.id) ?: error("a read checkout session can be locked")
        if (!usable(cs.value, now)) return closedResult(cs.value, payment.value.status) ?: CompleteCardAuthenticationResult.SessionExpired
        val decision = AuthenticationResultCheck.afterLookup(session.value, payment.value, s.presented, lookup, secrets.newAuthenticationRef(), now)
        return apply(command, decision, session.value, session.version, cs, payment.value.status, now)
    }

    /** 판정 결과를 같은 TX에 반영한다(세션 전이·결제창 세션 닫기·결정 로그) → 응답 값. */
    private fun apply(
        command: CompleteCardAuthenticationCommand,
        decision: ResultCheckDecision,
        session: com.paycore.payment.payment.domain.AuthenticationSession,
        version: Long,
        cs: com.paycore.payment.payment.application.required.Versioned<CheckoutSessionRecord>,
        paymentStatus: PaymentStatus,
        now: Instant,
    ): CompleteCardAuthenticationResult {
        val changesState = decision.verdict in STATE_CHANGING
        if (changesState) sessions.update(session, version)
        decision.event?.let { events.append(AuthenticationSessionEventRecord(session.id, it, decision.detail, now, command.correlationId)) }
        val record = cs.value
        return when (decision.verdict) {
            ResultVerdict.VERIFIED -> {
                if (record.status == CheckoutSessionStatus.ACTIVE) checkoutSessions.update(record.closed(CheckoutSessionCloseReason.VERIFIED_REDIRECT, now), cs.version)
                CompleteCardAuthenticationResult.Verified(successRedirect(record, session))
            }
            ResultVerdict.DUPLICATE -> CompleteCardAuthenticationResult.Verified(successRedirect(record, session))
            ResultVerdict.FAILED -> {
                val code = if (session.failure == AuthenticationFailure.USER_CANCELLED) CheckoutFailCode.USER_CANCELLED else CheckoutFailCode.AUTHENTICATION_FAILED
                CompleteCardAuthenticationResult.Failed(code, CheckoutSessionGate.failRedirect(record, code))
            }
            ResultVerdict.PAYMENT_CLOSED -> {
                if (record.status == CheckoutSessionStatus.ACTIVE) checkoutSessions.update(record.closed(CheckoutSessionCloseReason.PAYMENT_CLOSED, now), cs.version)
                val code = if (paymentStatus == PaymentStatus.REQUESTED) CheckoutFailCode.CHECKOUT_EXPIRED else CheckoutFailCode.PAYMENT_NOT_AVAILABLE
                CompleteCardAuthenticationResult.CheckoutClosed(code, CheckoutSessionGate.failRedirect(record, code))
            }
            ResultVerdict.IGNORED, ResultVerdict.SESSION_EXPIRED -> CompleteCardAuthenticationResult.Ignored
            ResultVerdict.REJECTED -> {
                if (decision.detail == AuthenticationEventDetail.EVIDENCE_CONFLICT) {
                    log.warn("operation=CompleteCardAuthentication correlationId={} security=EVIDENCE_CONFLICT authenticationSessionId={}", command.correlationId, session.id.value)
                }
                CompleteCardAuthenticationResult.Rejected
            }
            ResultVerdict.UNVERIFIABLE -> CompleteCardAuthenticationResult.Unverifiable
            ResultVerdict.PROCEED -> error("PROCEED is not a final decision")
        }
    }

    /** 다른 세션이 이미 쓴 결과 참조(1062 rollback 뒤 새 TX): 상태 불변 + REJECTED_RESULT(RESULT_REF_REUSED) 1회(보안). */
    private fun resultRefReused(command: CompleteCardAuthenticationCommand, s: Selected): CompleteCardAuthenticationResult {
        log.warn("operation=CompleteCardAuthentication correlationId={} security=RESULT_REF_REUSED authenticationSessionId={}", command.correlationId, s.sessionId.value)
        payments.lock(s.checkoutSession.paymentId)
        sessions.lock(s.sessionId) ?: error("a selected session can be locked")
        events.append(AuthenticationSessionEventRecord(s.sessionId, AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.RESULT_REF_REUSED, now(), command.correlationId))
        return CompleteCardAuthenticationResult.Rejected
    }

    private fun successRedirect(cs: CheckoutSessionRecord, session: com.paycore.payment.payment.domain.AuthenticationSession): String {
        val ref = checkNotNull(session.authenticationRef) { "a VERIFIED session has an authenticationRef" }
        return cs.successUrl.withQueryParameters(listOf("paymentId" to cs.paymentId.value, "authenticationRef" to ref.value)).value
    }

    /**
     * 쿠키가 맞는 결제창 세션이 Payment 쪽 사유로 닫혔으면(EXPIRED(PAYMENT_CLOSED) — control 보유 경로 만료, COMPLETED(PAYMENT_NOT_AVAILABLE)) 401이 아니라 가맹점으로 돌아갈
     * failUrl을 준다(§6.3·C7, 검토 #4 m-1). code: Payment가 아직 REQUESTED(시각 경과)이거나 결제창 시각으로 EXPIRED면 CHECKOUT_EXPIRED, 그 밖은 PAYMENT_NOT_AVAILABLE.
     * 그 밖의 닫힘(TTL·대체·취소)은 null(→ SessionExpired).
     */
    private fun closedResult(cs: CheckoutSessionRecord, paymentStatus: PaymentStatus): CompleteCardAuthenticationResult? {
        if (!CheckoutSessionGate.closedForPayment(cs)) return null
        val code = CheckoutSessionGate.closedCode(paymentStatus)
        return CompleteCardAuthenticationResult.CheckoutClosed(code, CheckoutSessionGate.failRedirect(cs, code))
    }

    private fun usable(cs: CheckoutSessionRecord, now: Instant): Boolean =
        now.isBefore(cs.expiresAt) &&
            (cs.status == CheckoutSessionStatus.ACTIVE || (cs.status == CheckoutSessionStatus.COMPLETED && cs.closeReason == CheckoutSessionCloseReason.VERIFIED_REDIRECT))

    private fun <T : Any> guarded(command: CompleteCardAuthenticationCommand, block: () -> T): T? = try {
        transactions.execute(::isRetryable) { block() }
    } catch (e: RuntimeException) {
        if (failure(command, e) != null) null else throw e
    }

    /** 저장소 일시 실패·commit 불명확 → Unavailable(같은 복귀 URL 재요청이 수렴). 그 밖은 결함(null → 호출자가 다시 던진다). */
    private fun failure(command: CompleteCardAuthenticationCommand, e: RuntimeException): CompleteCardAuthenticationResult? = when (e) {
        is CommitOutcomeUnknownException, is CannotCreateTransactionException, is UnexpectedRollbackException, is PersistenceFailure -> {
            log.warn("operation=CompleteCardAuthentication correlationId={} result=UNAVAILABLE exception={}", command.correlationId, e.javaClass.name)
            CompleteCardAuthenticationResult.Unavailable
        }
        else -> null
    }

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RESULT_REF_PARAM = "resultRef"
        const val STATE_PARAM = "state"
        val RESULT_REF = Regex("^srr_[A-Za-z0-9_-]{22}$")
        val STATE = Regex("^[A-Za-z0-9_-]{43}$")
        val STATE_CHANGING = setOf(ResultVerdict.VERIFIED, ResultVerdict.FAILED, ResultVerdict.PAYMENT_CLOSED, ResultVerdict.SESSION_EXPIRED)

        fun Map<String, List<String>>.singleValue(name: String): String? = this[name]?.singleOrNull()
    }
}
