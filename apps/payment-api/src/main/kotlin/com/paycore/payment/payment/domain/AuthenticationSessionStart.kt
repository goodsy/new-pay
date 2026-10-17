package com.paycore.payment.payment.domain

import java.time.Instant

/** [AuthenticationSessionStart.start] 결과: 새 세션과, 이번 시작이 만료시킨 이전 세션들. */
data class AuthenticationSessionStarted(val session: AuthenticationSession, val superseded: List<AuthenticationSession>)

/**
 * 인증 시작 domain 서비스(AUTHN-001 §7.1·§7.2, 관례 §12 "다중 Aggregate 연산은 검사 먼저"). 모든 검사를 먼저 하고(실패하면 어떤 세션도 바꾸지 않는다),
 * 같은 Payment의 미폐기·미귀속 세션(비종료와 VERIFIED 미귀속 — AN-07)을 EXPIRED로 만든 뒤(만료 시각이 지났으면 TTL_ELAPSED, 아니면 SUPERSEDED)
 * 새 CREATED 세션을 연다. 사용 가능한 인증 근거는 Payment당 최대 하나가 된다. 직렬화(payment 행 잠금)는 application의 책임이다.
 */
object AuthenticationSessionStart {
    fun start(
        payment: Payment,
        sessionsOfPayment: List<AuthenticationSession>,
        id: AuthenticationSessionId,
        binding: AuthenticationBinding,
        correlation: AuthenticationCorrelation,
        expiresAt: Instant,
        now: Instant,
    ): AuthenticationSessionStarted {
        require(payment.status == PaymentStatus.REQUESTED) { "authentication starts only for a REQUESTED payment: ${payment.status}" }
        require(binding.paymentId == payment.id && binding.orderKey == payment.orderKey) { "binding must target this payment" }
        require(expiresAt.isAfter(now)) { "expiresAt must be after now" }
        require(sessionsOfPayment.all { it.binding.paymentId == payment.id }) { "sessions must belong to this payment" }
        require(sessionsOfPayment.none { it.isBound() }) { "this payment already has a bound authentication session" }
        require(sessionsOfPayment.none { it.id == id }) { "authentication session id is already used" }
        require(sessionsOfPayment.none { it.correlation.stateDigest == correlation.stateDigest }) { "authentication state is already used" }

        val superseded = sessionsOfPayment.filterNot { it.isDiscarded() }
        superseded.forEach {
            val cause = if (now.isBefore(it.expiresAt)) AuthenticationExpiryCause.SUPERSEDED else AuthenticationExpiryCause.TTL_ELAPSED
            it.expire(cause, now)
        }
        val session = AuthenticationSession.create(id, payment, binding, correlation, expiresAt, now, sessionsOfPayment)
        return AuthenticationSessionStarted(session, superseded)
    }
}
