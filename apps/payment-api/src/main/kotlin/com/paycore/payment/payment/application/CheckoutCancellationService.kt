package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelCheckoutCommand
import com.paycore.payment.payment.application.provided.CancelCheckoutResult
import com.paycore.payment.payment.application.provided.CancelCheckoutUseCase
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.temporal.ChronoUnit

/**
 * 결제창 취소(AUTHN-001 §6.1·§7.4): 취소 TX payment(X) → authentication_session(X) → checkout_session(X). 결제창 세션 확인([CheckoutSessionGate] — Payment 불변) 뒤
 * 같은 Payment의 비종료 인증 세션 FAILED(USER_CANCELLED) + 결정 로그, 결제창 세션 COMPLETED(CANCELLED) → failUrl(code=USER_CANCELLED). 쿠키 삭제는 web이 한다.
 */
@Service
class CheckoutCancellationService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val gate: CheckoutSessionGate,
) : CancelCheckoutUseCase {

    private val log = LoggerFactory.getLogger(CheckoutCancellationService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun cancel(command: CancelCheckoutCommand): CancelCheckoutResult {
        if (!gate.wellFormed(command.handle, command.cookieSecret)) return CancelCheckoutResult.SessionExpired
        return try {
            transactions.execute({ it is PersistenceFailure.Deadlock || it is PersistenceFailure.OptimisticConflict }) {
                val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
                val found = gate.find(command.handle, command.cookieSecret) ?: return@execute closedOrExpired(gate.findMatching(command.handle, command.cookieSecret))
                val payment = payments.lock(found.paymentId) ?: error("a checkout session references a stored payment")
                val ofPayment = sessions.lockAllOfPayment(found.paymentId)
                when (val g = gate.gate(payment.value, ofPayment, found.id, now, command.correlationId)) {
                    // 식별과 잠금 사이에 control 보유 경로가 결제창을 닫았을 수 있다 → 잠금 아래 기록으로 같은 판정.
                    CheckoutGate.Expired -> closedOrExpired(gate.lockedRecord(found.id))
                    is CheckoutGate.PaymentNotAvailable -> CancelCheckoutResult.PaymentNotAvailable(g.failRedirect)
                    is CheckoutGate.TimeOver -> CancelCheckoutResult.CheckoutTimeOver(g.failRedirect)
                    is CheckoutGate.Open -> {
                        ofPayment.filter { it.value.isOpen() }.forEach { s ->
                            s.value.fail(AuthenticationFailure.USER_CANCELLED)
                            sessions.update(s.value, s.version)
                            events.append(
                                AuthenticationSessionEventRecord(s.value.id, AuthenticationEventKind.FAILED, AuthenticationEventDetail.USER_CANCELLED, now, command.correlationId),
                            )
                        }
                        checkoutSessions.update(g.checkoutSession.value.closed(CheckoutSessionCloseReason.CANCELLED, now), g.checkoutSession.version)
                        CancelCheckoutResult.Cancelled(CheckoutSessionGate.failRedirect(g.checkoutSession.value, CheckoutFailCode.USER_CANCELLED))
                    }
                }
            }
        } catch (e: RuntimeException) {
            when (e) {
                is CommitOutcomeUnknownException, is CannotCreateTransactionException, is UnexpectedRollbackException, is PersistenceFailure -> {
                    log.warn("operation=CancelCheckout correlationId={} result=UNAVAILABLE exception={}", command.correlationId, e.javaClass.name)
                    CancelCheckoutResult.Unavailable
                }
                else -> throw e
            }
        }
    }

    /**
     * 쿠키가 맞는 결제창이 Payment 쪽 사유로 이미 닫혔으면(EXPIRED(PAYMENT_CLOSED)·COMPLETED(PAYMENT_NOT_AVAILABLE)) 401이 아니라 failUrl 안내 — Payment REQUESTED·EXPIRED면
     * 410 CheckoutTimeOver(CHECKOUT_EXPIRED), 그 밖 409 PaymentNotAvailable(PAYMENT_NOT_AVAILABLE). 상태를 바꾸지 않고 취소 이벤트도 쓰지 않는다(검토 #4 m-1 후속,
     * Coordinator 판정 2026-10-17 — 화면·인증 시작·복귀와 같은 규칙). 그 밖(TTL·대체·이미 취소·쿠키 불일치)은 SessionExpired.
     */
    private fun closedOrExpired(cs: CheckoutSessionRecord?): CancelCheckoutResult {
        if (cs == null || !CheckoutSessionGate.closedForPayment(cs)) return CancelCheckoutResult.SessionExpired
        val payment = payments.lock(cs.paymentId) ?: error("a checkout session references a stored payment")
        val code = CheckoutSessionGate.closedCode(payment.value.status)
        val redirect = CheckoutSessionGate.failRedirect(cs, code)
        return if (code == CheckoutFailCode.CHECKOUT_EXPIRED) CancelCheckoutResult.CheckoutTimeOver(redirect) else CancelCheckoutResult.PaymentNotAvailable(redirect)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
