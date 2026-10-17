package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionCommand
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionResult
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.application.required.CheckoutPolicy
import com.paycore.payment.payment.application.required.CheckoutSecretGenerator
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 결제창 토큰 교환 TX-X(AUTHN-001 §4.1·§6.1·§6.3, D-AUTHN-001). 외부 호출 없음. 토큰은 digest로 **비잠금** 조회해 payment를 얻은 뒤 잠금 순서
 * payment(X) → checkout_session(X) → checkout_token(X)로 잠그고 다시 판정한다(§10.3 — 잠금 읽기는 최신 커밋 값, 검토 #2 m-1). 판정:
 * 토큰 ISSUED·미만료가 아니면 [OpenCheckoutSessionResult.InvalidToken](사유 구분 없음 — 열거 방지, 사용된 토큰 재제시는 보안 로그·지표) → Payment가 REQUESTED가 아니거나
 * 결제창 시각이 지났으면 토큰 CLOSED·결제창 세션 없음·**Payment 불변**(control 미보유 경로, §7.5.3) + failUrl → 같은 Payment의 ACTIVE 결제창 세션 REVOKED(SUPERSEDED) →
 * 새 결제창 세션(handle 128bit·쿠키 비밀 256bit, DB에는 `cks1:` digest만, `expires_at = min(now + 세션 수명, 결제창 시각)`) → 토큰 CONSUMED.
 */
@Service
class CheckoutSessionOpeningService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val payments: PaymentRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val secrets: CheckoutSecretGenerator,
    private val policy: CheckoutPolicy,
) : OpenCheckoutSessionUseCase {

    private val log = LoggerFactory.getLogger(CheckoutSessionOpeningService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun open(command: OpenCheckoutSessionCommand): OpenCheckoutSessionResult {
        if (!TOKEN.matches(command.token)) return record(OpenCheckoutSessionResult.InvalidToken, "invalid_format")
        val digest = SecretDigest.of(SecretKind.CHECKOUT_TOKEN, command.token)
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return try {
            transactions.execute(::isRetryable) { openInTransaction(digest, now, command.correlationId) }
        } catch (e: CommitOutcomeUnknownException) {
            unavailable(command, "COMMIT_OUTCOME_UNKNOWN", e)
        } catch (e: CannotCreateTransactionException) {
            unavailable(command, "TRANSACTION_UNAVAILABLE", e)
        } catch (e: UnexpectedRollbackException) {
            unavailable(command, "ROLLED_BACK", e)
        } catch (e: PersistenceFailure) {
            unavailable(command, e.javaClass.simpleName, e)
        }
    }

    private fun openInTransaction(digest: SecretDigest, now: Instant, correlationId: String): OpenCheckoutSessionResult {
        val found = tokens.findByDigest(digest) ?: return record(OpenCheckoutSessionResult.InvalidToken, "unknown")
        val payment = payments.lock(found.paymentId) ?: error("a token references a stored payment")
        val active = checkoutSessions.lockActiveOfPayment(found.paymentId)
        val token = tokens.lock(found.id) ?: error("a found token can be locked")
        if (!token.value.isExchangeable(now)) {
            if (token.value.status == CheckoutTokenStatus.CONSUMED) {
                // 보안 이벤트(§12.6): 사용된 토큰 재제시 — 원문·digest 없이 분류와 내부 ID만.
                log.warn("operation=OpenCheckout correlationId={} security=TOKEN_REUSED checkoutTokenId={}", correlationId, token.value.id.value)
                return record(OpenCheckoutSessionResult.InvalidToken, "reused")
            }
            return record(OpenCheckoutSessionResult.InvalidToken, "not_exchangeable")
        }
        val p = payment.value
        check(token.value.paymentId == p.id && token.value.orderKey == p.orderKey) { "token binding must match its payment" }
        if (p.status != PaymentStatus.REQUESTED || p.isCheckoutWindowElapsed(now)) {
            tokens.update(token.value.closed(CheckoutTokenStatus.CLOSED, now), token.version)
            val code = if (p.status != PaymentStatus.REQUESTED) CheckoutFailCode.PAYMENT_NOT_AVAILABLE else CheckoutFailCode.CHECKOUT_EXPIRED
            val redirect = token.value.failUrl.withQueryParameters(listOf("paymentId" to p.id.value, "code" to code.name)).value
            return record(OpenCheckoutSessionResult.NotAvailable(code, redirect), "not_available")
        }
        active?.let { checkoutSessions.update(it.value.closed(CheckoutSessionCloseReason.SUPERSEDED, now), it.version) }
        val window = checkNotNull(p.checkoutWindow) { "a REQUESTED payment has a checkout window" }
        val expiresAt = minOf(now.plus(policy.sessionTtl), window.expiresAt)
        val secret = secrets.newSessionSecret()
        val handle = secrets.newHandle()
        checkoutSessions.insert(
            CheckoutSessionRecord(
                id = secrets.newCheckoutSessionId(),
                handle = handle,
                digest = SecretDigest.of(SecretKind.CHECKOUT_SESSION, secret),
                tokenId = token.value.id,
                paymentId = p.id,
                successUrl = token.value.successUrl,
                failUrl = token.value.failUrl,
                siteHost = token.value.siteHost,
                status = CheckoutSessionStatus.ACTIVE,
                closeReason = null,
                createdAt = now,
                expiresAt = expiresAt,
                closedAt = null,
                policyVersion = policy.policyVersion,
            ),
        )
        tokens.update(token.value.consumed(now), token.version)
        val maxAge = maxOf(1L, Duration.between(now, expiresAt).seconds)
        return record(OpenCheckoutSessionResult.Opened(handle.value, secret, expiresAt, maxAge), "opened")
    }

    /** 결과 분류는 로그 상세로만 둔다(지표 counter는 web adapter가 결과 타입으로 센다 — application은 Micrometer에 의존하지 않는다). */
    private fun record(result: OpenCheckoutSessionResult, label: String): OpenCheckoutSessionResult {
        log.debug("operation=OpenCheckout result={}", label)
        return result
    }

    private fun unavailable(command: OpenCheckoutSessionCommand, failure: String, e: Throwable): OpenCheckoutSessionResult {
        log.warn("operation=OpenCheckout correlationId={} result=UNAVAILABLE failure={} exception={}", command.correlationId, failure, e.javaClass.name)
        return record(OpenCheckoutSessionResult.Unavailable, "unavailable")
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        val TOKEN = Regex("^ckt_[A-Za-z0-9_-]{43}$")
    }
}
