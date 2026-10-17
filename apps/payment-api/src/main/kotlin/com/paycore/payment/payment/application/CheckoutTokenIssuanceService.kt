package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutTokenRejection
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.required.AuthenticationProfiles
import com.paycore.payment.payment.application.required.CheckoutPolicy
import com.paycore.payment.payment.application.required.CheckoutSecretGenerator
import com.paycore.payment.payment.application.required.CheckoutTokenRecord
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.ReturnUrlParse
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
 * 결제창 토큰 발급 TX-I(AUTHN-001 §5.2). 외부 호출 없음. 판정 순서(앞 단계가 결정하면 멈춤):
 * 1. 반환 URL 문법(§8.1 — TX 전, INVALID_RETURN_URL + field). 경로 paymentId 형식 오류는 존재 비노출로 PAYMENT_NOT_FOUND(AN-01).
 * 2. TX-I: Payment 존재 + 환경·MID = 인증 주체(아니면 PAYMENT_NOT_FOUND) → 인증 backend(없으면 CHECKOUT_NOT_SUPPORTED, §13.6) → Site WEB host(Snapshot — 판정 불가
 *    CHECKOUT_UNAVAILABLE, host 없음 RETURN_URL_NOT_ALLOWED; head(S)) → 잠금 control(X) → payment(X) → **결제창 시각 경과면 inline 만료를 커밋하고 PAYMENT_TERMINATED**
 *    ([RequestedPaymentClosing], 판정 U-3·n-d) → hold(ORDER_ON_HOLD) → 상태(FAILED·EXPIRED → PAYMENT_TERMINATED, 승인 시작 → PAYMENT_AUTHORIZATION_STARTED) → URL host =
 *    Site host(§8.2) → 미교환 토큰 대체(만료 지났으면 EXPIRED, AN-04) → 새 토큰(256bit 원문, DB에는 `cth1:` digest만, `expires_at = min(now + TTL, 결제창 시각)`).
 * 3. 커밋 뒤 Issued(checkoutUrl = 설정 origin + `/checkout?token=<원문>`).
 * 교착은 새 TX로 같은 의도를 다시 실행한다(최대 3회). 그 밖의 저장소 일시 실패·commit 불명확은 CHECKOUT_UNAVAILABLE(원문이 나가지 않았으므로 재요청 안전).
 */
@Service
class CheckoutTokenIssuanceService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val payments: PaymentRepository,
    private val controls: OrderPaymentControlRepository,
    private val tokens: CheckoutTokenStore,
    private val siteHosts: SiteWebHostPort,
    private val profiles: AuthenticationProfiles,
    private val policy: CheckoutPolicy,
    private val secrets: CheckoutSecretGenerator,
    private val closing: RequestedPaymentClosing,
) : IssueCheckoutTokenUseCase {

    private val log = LoggerFactory.getLogger(CheckoutTokenIssuanceService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun issue(command: IssueCheckoutTokenCommand): IssueCheckoutTokenResult {
        val success = when (val parsed = MerchantReturnUrl.parse(command.successUrl)) {
            is ReturnUrlParse.Accepted -> parsed.url
            is ReturnUrlParse.Rejected -> return IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.INVALID_RETURN_URL, SUCCESS_URL_FIELD)
        }
        val failure = when (val parsed = MerchantReturnUrl.parse(command.failUrl)) {
            is ReturnUrlParse.Accepted -> parsed.url
            is ReturnUrlParse.Rejected -> return IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.INVALID_RETURN_URL, FAIL_URL_FIELD)
        }
        if (!PAYMENT_ID_PATH.matches(command.paymentId)) return rejected(CheckoutTokenRejection.PAYMENT_NOT_FOUND)
        val paymentId = PaymentId(command.paymentId)
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return try {
            transactions.execute(::isRetryable) { issueInTransaction(command, paymentId, success, failure, now) }
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

    private fun issueInTransaction(
        command: IssueCheckoutTokenCommand,
        paymentId: PaymentId,
        success: MerchantReturnUrl,
        failure: MerchantReturnUrl,
        now: Instant,
    ): IssueCheckoutTokenResult {
        val auth = command.authentication
        val record = payments.findCreationRecord(paymentId) ?: return rejected(CheckoutTokenRejection.PAYMENT_NOT_FOUND)
        if (record.orderKey.environment != auth.environment || record.orderKey.merchantAccountId != auth.merchantAccountId) {
            return rejected(CheckoutTokenRejection.PAYMENT_NOT_FOUND)
        }
        profiles.profileFor(record.orderKey.environment) ?: return rejected(CheckoutTokenRejection.CHECKOUT_NOT_SUPPORTED)
        val site = when (val host = siteHosts.resolve(auth.merchantAccountId, record.attributes.merchantSiteId, now)) {
            is SiteWebHostResult.WebHost -> host
            is SiteWebHostResult.NoWebHost -> return rejected(CheckoutTokenRejection.RETURN_URL_NOT_ALLOWED)
            is SiteWebHostResult.Unavailable -> {
                log.warn("operation=IssueCheckoutToken correlationId={} result=CHECKOUT_UNAVAILABLE failure=SITE_HOST_{}", command.correlationId, host.reason)
                return rejected(CheckoutTokenRejection.CHECKOUT_UNAVAILABLE)
            }
        }

        val control = controls.lock(record.orderKey) ?: error("a stored payment has an order control")
        val payment = payments.lock(paymentId) ?: error("a stored payment can be locked")
        if (closing.closeIfElapsed(control, payment, now, ExpiryCausation(command.correlationId, command.correlationId)) is ClosingOutcome.Closed) {
            log.info("operation=IssueCheckoutToken correlationId={} result=PAYMENT_TERMINATED cause=CHECKOUT_WINDOW_ELAPSED action=expired_inline", command.correlationId)
            return rejected(CheckoutTokenRejection.PAYMENT_TERMINATED)
        }
        if (control.value.isOnIncidentHold()) return rejected(CheckoutTokenRejection.ORDER_ON_HOLD)
        when (payment.value.status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.FAILED, PaymentStatus.EXPIRED -> return rejected(CheckoutTokenRejection.PAYMENT_TERMINATED)
            PaymentStatus.AUTHORIZING, PaymentStatus.UNKNOWN, PaymentStatus.AUTHORIZED -> return rejected(CheckoutTokenRejection.PAYMENT_AUTHORIZATION_STARTED)
        }
        if (!success.hasHost(site.host) || !failure.hasHost(site.host)) return rejected(CheckoutTokenRejection.RETURN_URL_NOT_ALLOWED)

        tokens.lockIssuedOfPayment(paymentId)?.let { previous ->
            val target = if (previous.value.isExchangeable(now)) CheckoutTokenStatus.SUPERSEDED else CheckoutTokenStatus.EXPIRED
            tokens.update(previous.value.closed(target, now), previous.version)
        }
        val window = checkNotNull(payment.value.checkoutWindow) { "a REQUESTED payment has a checkout window" }
        val raw = secrets.newTokenSecret()
        val expiresAt = minOf(now.plus(policy.tokenTtl), window.expiresAt)
        tokens.insert(
            CheckoutTokenRecord(
                id = secrets.newTokenId(),
                digest = SecretDigest.of(SecretKind.CHECKOUT_TOKEN, raw),
                paymentId = paymentId,
                orderKey = record.orderKey,
                merchantSiteId = record.attributes.merchantSiteId,
                money = record.attributes.money,
                successUrl = success,
                failUrl = failure,
                siteHost = site.host,
                siteSnapshotVersion = site.snapshotVersion,
                status = CheckoutTokenStatus.ISSUED,
                issuedAt = now,
                expiresAt = expiresAt,
                consumedAt = null,
                closedAt = null,
                issuedByCredentialId = auth.credentialKeyId,
                policyVersion = policy.policyVersion,
            ),
        )
        return IssueCheckoutTokenResult.Issued(paymentId.value, "${policy.publicBaseUrl}$CHECKOUT_PATH$raw", expiresAt)
    }

    private fun rejected(reason: CheckoutTokenRejection) = IssueCheckoutTokenResult.Rejected(reason)

    private fun unavailable(command: IssueCheckoutTokenCommand, failure: String, e: Throwable): IssueCheckoutTokenResult {
        log.warn("operation=IssueCheckoutToken correlationId={} result=CHECKOUT_UNAVAILABLE failure={} exception={}", command.correlationId, failure, e.javaClass.name)
        return rejected(CheckoutTokenRejection.CHECKOUT_UNAVAILABLE)
    }

    /** DB가 rollback을 확정한 교착·version 충돌만 같은 의도로 다시 판정한다. */
    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val SUCCESS_URL_FIELD = "successUrl"
        const val FAIL_URL_FIELD = "failUrl"
        const val CHECKOUT_PATH = "/checkout?token="

        /** 경로 paymentId: 1..64자 출력 가능 ASCII(공백 제외). 아니면 존재 비노출 404(AN-01). */
        val PAYMENT_ID_PATH = Regex("^[!-~]{1,64}$")
    }
}
