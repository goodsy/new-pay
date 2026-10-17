package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutChoice
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CheckoutPageQuery
import com.paycore.payment.payment.application.provided.CheckoutPageRequest
import com.paycore.payment.payment.application.provided.CheckoutPageResult
import com.paycore.payment.payment.application.provided.CheckoutPageView
import com.paycore.payment.payment.application.required.AuthenticationProfile
import com.paycore.payment.payment.application.required.AuthenticationProfiles
import com.paycore.payment.payment.application.required.CardAuthenticationEndpoints
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutViewReader
import com.paycore.payment.payment.application.required.CheckoutViewRow
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.CurrencyUnit
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentPlanRule
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.ReturnUrlParse
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 결제창 화면(AUTHN-001 §6.2·§6.3). 표시 값은 jOOQ [CheckoutViewReader](비권위 — 읽기 전용 TX)에서 온다. 판정:
 * handle 형식 → 쿠키 비밀 digest 대조(상수 시간 — [SecretDigest.equals]) → 결제창 세션 ACTIVE·미만료(아니면 401, 읽기 경로라 상태 불변) → Payment REQUESTED(아니면
 * 짧은 TX payment(X) → checkout_session(X)에서 다시 판정해 COMPLETED(PAYMENT_NOT_AVAILABLE) + failUrl). 결제창 시각 경과는 세션 만료 시각이 결제창 시각으로 잘려
 * 있어 401로 끝난다. 카드사 목록은 인증 프로파일 allowlist, Plan은 일시불 + 정책 개월 중 `PaymentPlanRule.precheck` 통과분(§6.2).
 */
@Service
class CheckoutPageService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val reader: CheckoutViewReader,
    private val payments: PaymentRepository,
    private val checkoutSessions: CheckoutSessionStore,
    private val profiles: AuthenticationProfiles,
    private val endpoints: CardAuthenticationEndpoints,
    private val eligibility: PaymentEligibilityPort,
) : CheckoutPageQuery {

    private val log = LoggerFactory.getLogger(CheckoutPageService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val reads = LocalTransactions(transactionManager, 1, readOnly = true)

    override fun defaultFormActionOrigins(): List<String> = listOf(endpoints.customerOrigin)

    override fun page(request: CheckoutPageRequest): CheckoutPageResult {
        if (!HANDLE.matches(request.handle)) return CheckoutPageResult.BadHandle
        val secret = request.cookieSecret?.takeIf { SECRET.matches(it) } ?: return CheckoutPageResult.SessionExpired
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val row = try {
            reader.findByHandle(CheckoutHandle(request.handle))
        } catch (e: PersistenceFailure) {
            log.warn("operation=CheckoutPage correlationId={} result=UNAVAILABLE exception={}", request.correlationId, e.javaClass.name)
            return CheckoutPageResult.Unavailable
        } ?: return CheckoutPageResult.SessionExpired
        if (SecretDigest.parse(row.sessionDigest) != SecretDigest.of(SecretKind.CHECKOUT_SESSION, secret)) return CheckoutPageResult.SessionExpired
        closedForPayment(row)?.let { return it }
        if (row.sessionStatus != CheckoutSessionStatus.ACTIVE.name || !now.isBefore(row.sessionExpiresAt)) return CheckoutPageResult.SessionExpired
        if (row.paymentStatus != PaymentStatus.REQUESTED.name) return closeNotAvailable(row, request)
        val profile = profiles.profileFor(row.environment) ?: return CheckoutPageResult.SessionExpired
        val unit = currencyUnit(row, now, request) ?: return CheckoutPageResult.Unavailable
        return CheckoutPageResult.Page(view(row, request.handle, profile, unit))
    }

    /**
     * 표시 금액의 통화 단위(검토 #3 n-5): PayCore 기준정보(권리 투영 Snapshot의 통화 minor unit — CreatePayment eligibility와 같은 원천). 판정할 수 없으면 null → 503
     * 화면(소수 자릿수를 추측하지 않는다). 표시 전용이다(금액 계산에 쓰지 않는다).
     */
    private fun currencyUnit(row: CheckoutViewRow, now: java.time.Instant, request: CheckoutPageRequest): CurrencyUnit? {
        val unit = try {
            reads.execute({ false }) {
                val result = eligibility.evaluate(
                    row.environment, MerchantAccountId(row.merchantAccountId), MerchantSiteId(row.merchantSiteId), CurrencyCode(row.currency), PaymentMethod.CARD, now,
                )
                (result as? EligibilityResult.Eligible)?.currencyUnit?.takeIf { it.code.value == row.currency }
            }
        } catch (e: RuntimeException) {
            log.warn("operation=CheckoutPage correlationId={} result=UNAVAILABLE cause=CURRENCY_UNIT exception={}", request.correlationId, e.javaClass.name)
            return null
        }
        if (unit == null) log.warn("operation=CheckoutPage correlationId={} result=UNAVAILABLE cause=CURRENCY_UNIT", request.correlationId)
        return unit
    }

    /** 쿠키가 맞는 결제창이 Payment 쪽 사유로 닫혀 있으면 failUrl 안내(410/409 — 검토 #4 m-1). 읽기 경로라 상태를 바꾸지 않는다. */
    private fun closedForPayment(row: CheckoutViewRow): CheckoutPageResult? {
        val closed = (row.sessionStatus == CheckoutSessionStatus.EXPIRED.name && row.sessionCloseReason == CheckoutSessionCloseReason.PAYMENT_CLOSED.name) ||
            (row.sessionStatus == CheckoutSessionStatus.COMPLETED.name && row.sessionCloseReason == CheckoutSessionCloseReason.PAYMENT_NOT_AVAILABLE.name)
        if (!closed) return null
        val code = CheckoutSessionGate.closedCode(PaymentStatus.valueOf(row.paymentStatus))
        val failUrl = (MerchantReturnUrl.parse(row.failUrl) as? ReturnUrlParse.Accepted)?.url ?: error("a stored fail URL is valid")
        return CheckoutPageResult.NotAvailable(code, failUrl.withQueryParameters(listOf("paymentId" to row.paymentId, "code" to code.name)).value)
    }

    private fun view(row: CheckoutViewRow, handle: String, profile: AuthenticationProfile, unit: CurrencyUnit): CheckoutPageView {
        val money = Money(row.amountMinor, CurrencyCode(row.currency))
        val plans = buildList {
            add(CheckoutChoice(LUMP_SUM, "일시불"))
            profile.planPolicy.installmentMonths.sorted().forEach { months ->
                val plan = PaymentPlan.Installment(months)
                if (profile.planPolicy.currency == money.currency && PaymentPlanRule.precheck(plan, money, profile.planPolicy) == null) {
                    add(CheckoutChoice("$INSTALLMENT_PREFIX$months", "${months}개월 할부"))
                }
            }
        }
        return CheckoutPageView(
            handle = handle,
            orderName = row.orderName,
            amountDisplay = displayAmount(row.amountMinor, unit.minorUnit),
            currency = row.currency,
            siteHost = row.siteHost,
            cardCompanies = profile.cardCompanies.map { CheckoutChoice(it.code, it.name) },
            plans = plans,
            formActionOrigins = listOf(endpoints.customerOrigin, "https://${row.siteHost}"),
        )
    }

    /** Payment가 REQUESTED가 아닌 결제창: 잠금 아래 다시 판정해 결제창 세션을 COMPLETED(PAYMENT_NOT_AVAILABLE)로 닫는다(Payment 불변). */
    private fun closeNotAvailable(row: CheckoutViewRow, request: CheckoutPageRequest): CheckoutPageResult {
        val failUrl = (MerchantReturnUrl.parse(row.failUrl) as? ReturnUrlParse.Accepted)?.url ?: error("a stored fail URL is valid")
        val redirect = failUrl.withQueryParameters(listOf("paymentId" to row.paymentId, "code" to CheckoutFailCode.PAYMENT_NOT_AVAILABLE.name)).value
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return try {
            transactions.execute({ it is PersistenceFailure.Deadlock || it is PersistenceFailure.OptimisticConflict }) {
                val payment = payments.lock(PaymentId(row.paymentId)) ?: error("a checkout session references a stored payment")
                val session = checkoutSessions.lock(CheckoutSessionId(row.checkoutSessionId)) ?: error("a read checkout session can be locked")
                if (payment.value.status != PaymentStatus.REQUESTED && session.value.status == CheckoutSessionStatus.ACTIVE) {
                    checkoutSessions.update(session.value.closed(CheckoutSessionCloseReason.PAYMENT_NOT_AVAILABLE, now), session.version)
                }
            }
            CheckoutPageResult.NotAvailable(CheckoutFailCode.PAYMENT_NOT_AVAILABLE, redirect)
        } catch (e: CommitOutcomeUnknownException) {
            unavailable(request, e)
        } catch (e: CannotCreateTransactionException) {
            unavailable(request, e)
        } catch (e: UnexpectedRollbackException) {
            unavailable(request, e)
        } catch (e: PersistenceFailure) {
            unavailable(request, e)
        }
    }

    private fun unavailable(request: CheckoutPageRequest, e: Throwable): CheckoutPageResult {
        log.warn("operation=CheckoutPage correlationId={} result=UNAVAILABLE exception={}", request.correlationId, e.javaClass.name)
        return CheckoutPageResult.Unavailable
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val LUMP_SUM = "LUMP_SUM"
        const val INSTALLMENT_PREFIX = "INSTALLMENT_"
        val HANDLE = Regex("^[A-Za-z0-9_-]{22}$")
        val SECRET = Regex("^[A-Za-z0-9_-]{43}$")

        /** 표시 전용 금액 서식(PayCore 통화 minor unit — 계산에 쓰지 않는다). */
        fun displayAmount(amountMinor: Long, digits: Int): String {
            val pattern = if (digits == 0) "#,##0" else "#,##0." + "0".repeat(digits)
            return DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(BigDecimal.valueOf(amountMinor).movePointLeft(digits))
        }
    }
}
