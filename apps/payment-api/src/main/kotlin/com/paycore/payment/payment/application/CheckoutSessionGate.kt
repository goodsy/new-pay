package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationExpiryCause
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import org.springframework.stereotype.Component
import java.time.Instant

/** [CheckoutSessionGate.gate] 결과. */
sealed interface CheckoutGate {
    data class Open(val checkoutSession: Versioned<CheckoutSessionRecord>) : CheckoutGate

    /** 쿠키 불일치·종료·만료(만료 시각 경과면 이번 TX가 EXPIRED(TTL_ELAPSED)로 기록했다). */
    data object Expired : CheckoutGate

    data class PaymentNotAvailable(val failRedirect: String) : CheckoutGate

    data class TimeOver(val failRedirect: String) : CheckoutGate
}

/**
 * 결제창 쓰기 경로(인증 시작·TX-B·취소)의 결제창 세션 확인(AUTHN-001 §6.3·§7.4.1·§7.5.3). control을 쥐지 않는 경로라 **Payment를 바꾸지 않는다**:
 * Payment가 REQUESTED가 아니면 결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE), 결제창 시각이 지났으면 자기 비종료 인증 세션 EXPIRED(PAYMENT_CLOSED)·결제창 세션 EXPIRED
 * (PAYMENT_CLOSED). 호출자 TX가 payment(X) → authentication_session(X)를 쥔 뒤 부른다 — 결제창 세션은 여기서 잠근다(§10.3 순서).
 */
@Component
class CheckoutSessionGate(
    private val checkoutSessions: CheckoutSessionStore,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
) {

    /** handle·쿠키 비밀 형식(DB를 보지 않는다). */
    fun wellFormed(handle: String, secret: String?): Boolean = HANDLE.matches(handle) && secret != null && SECRET.matches(secret)

    /** 비잠금 대상 식별(호출자 TX 안): handle·쿠키 형식, digest 일치, ACTIVE. 판정은 [gate]가 잠금 아래에서 다시 한다. */
    fun find(handle: String, secret: String?): CheckoutSessionRecord? = findMatching(handle, secret)?.takeIf { it.status == CheckoutSessionStatus.ACTIVE }

    /** 쿠키 digest가 맞는 결제창 세션(상태 무관, 비잠금) — 닫힌 결제창의 안내(failUrl)를 고르는 데만 쓴다(검토 #4 m-1). */
    fun findMatching(handle: String, secret: String?): CheckoutSessionRecord? {
        if (secret == null || !wellFormed(handle, secret)) return null
        val record = checkoutSessions.findByHandle(CheckoutHandle(handle)) ?: return null
        return record.takeIf { it.digest == SecretDigest.of(SecretKind.CHECKOUT_SESSION, secret) }
    }

    /** 이 TX가 잠근(또는 잠글) 결제창 세션의 현재 기록. */
    fun lockedRecord(checkoutSessionId: CheckoutSessionId): CheckoutSessionRecord? = checkoutSessions.lock(checkoutSessionId)?.value

    fun gate(
        payment: Payment,
        sessionsOfPayment: List<Versioned<AuthenticationSession>>,
        checkoutSessionId: CheckoutSessionId,
        now: Instant,
        correlationId: String,
    ): CheckoutGate {
        val cs = checkoutSessions.lock(checkoutSessionId) ?: return CheckoutGate.Expired
        if (cs.value.status != CheckoutSessionStatus.ACTIVE || cs.value.paymentId != payment.id) return CheckoutGate.Expired
        if (!now.isBefore(cs.value.expiresAt)) {
            checkoutSessions.update(cs.value.closed(CheckoutSessionCloseReason.TTL_ELAPSED, now), cs.version)
            return CheckoutGate.Expired
        }
        if (payment.status != PaymentStatus.REQUESTED) {
            checkoutSessions.update(cs.value.closed(CheckoutSessionCloseReason.PAYMENT_NOT_AVAILABLE, now), cs.version)
            return CheckoutGate.PaymentNotAvailable(failRedirect(cs.value, CheckoutFailCode.PAYMENT_NOT_AVAILABLE))
        }
        if (payment.isCheckoutWindowElapsed(now)) {
            expireOpen(sessionsOfPayment, AuthenticationExpiryCause.PAYMENT_CLOSED, now, correlationId)
            checkoutSessions.update(cs.value.closed(CheckoutSessionCloseReason.PAYMENT_CLOSED, now), cs.version)
            return CheckoutGate.TimeOver(failRedirect(cs.value, CheckoutFailCode.CHECKOUT_EXPIRED))
        }
        return CheckoutGate.Open(cs)
    }

    /** 잠근 결제창 세션의 failUrl + paymentId·code(상태 변경 없음 — 이미 닫힌 결제창의 안내 링크). */
    fun failRedirectOf(checkoutSessionId: CheckoutSessionId, code: CheckoutFailCode): String? =
        checkoutSessions.lock(checkoutSessionId)?.value?.let { failRedirect(it, code) }

    /** 비종료(CREATED·IN_PROGRESS) 세션을 EXPIRED로 만들고 결정 로그 EXPIRED(원인)를 남긴다. */
    fun expireOpen(sessionsOfPayment: List<Versioned<AuthenticationSession>>, cause: AuthenticationExpiryCause, now: Instant, correlationId: String) {
        sessionsOfPayment.filter { it.value.isOpen() }.forEach { s ->
            s.value.expire(cause, now)
            sessions.update(s.value, s.version)
            events.append(AuthenticationSessionEventRecord(s.value.id, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.valueOf(cause.name), now, correlationId))
        }
    }

    companion object {
        val HANDLE = Regex("^[A-Za-z0-9_-]{22}$")
        val SECRET = Regex("^[A-Za-z0-9_-]{43}$")

        /**
         * 결제창 세션이 Payment 쪽 사유로 닫혔다(EXPIRED(PAYMENT_CLOSED) — 시각 경과·control 보유 만료, COMPLETED(PAYMENT_NOT_AVAILABLE)). 종료 상태라 다시 열리지 않으므로
         * 비잠금 읽기로 판정해도 된다. 이런 결제창은 401이 아니라 가맹점 failUrl로 안내한다(§6.3, 검토 #4 m-1).
         */
        fun closedForPayment(cs: CheckoutSessionRecord): Boolean =
            (cs.status == CheckoutSessionStatus.EXPIRED && cs.closeReason == CheckoutSessionCloseReason.PAYMENT_CLOSED) ||
                (cs.status == CheckoutSessionStatus.COMPLETED && cs.closeReason == CheckoutSessionCloseReason.PAYMENT_NOT_AVAILABLE)

        /** 닫힌 결제창의 failUrl code(검토 #4 m-1): 시각 경과 REQUESTED·결제창 시각으로 EXPIRED → CHECKOUT_EXPIRED, 그 밖 → PAYMENT_NOT_AVAILABLE. */
        fun closedCode(paymentStatus: PaymentStatus): CheckoutFailCode =
            if (paymentStatus == PaymentStatus.REQUESTED || paymentStatus == PaymentStatus.EXPIRED) CheckoutFailCode.CHECKOUT_EXPIRED else CheckoutFailCode.PAYMENT_NOT_AVAILABLE

        fun failRedirect(cs: CheckoutSessionRecord, code: CheckoutFailCode): String =
            cs.failUrl.withQueryParameters(listOf("paymentId" to cs.paymentId.value, "code" to code.name)).value
    }
}
