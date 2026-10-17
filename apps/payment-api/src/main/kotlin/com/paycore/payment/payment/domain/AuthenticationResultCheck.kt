package com.paycore.payment.payment.domain

import java.time.Instant

/** 복귀 URL이 가져온 값의 digest(원문은 domain에 들어오지 않는다). */
data class PresentedResult(val stateDigest: SecretDigest, val resultRefDigest: SecretDigest) {
    init {
        require(stateDigest.kind == SecretKind.AUTHENTICATION_STATE && resultRefDigest.kind == SecretKind.RESULT_REFERENCE) { "state and result reference digests expected" }
    }
}

/** 판정 결과(화면·응답 매핑은 application/web — AUTHN-001 §6.3). [PROCEED]는 1단계에서만(조회로 진행). */
enum class ResultVerdict { PROCEED, IGNORED, DUPLICATE, REJECTED, PAYMENT_CLOSED, SESSION_EXPIRED, UNVERIFIABLE, FAILED, VERIFIED }

data class ResultCheckDecision(val verdict: ResultVerdict, val event: AuthenticationEventKind?, val detail: AuthenticationEventDetail?)

/**
 * 인증 결과 복귀 판정 domain 서비스(AUTHN-001 rev 1.3 §7.3, D-AUTHN-003). 세션은 application이 결제창 세션 안에서 URL state digest로 고른다(전제 —
 * 다르면 호출 결함). 판정은 위에서 아래로 첫 일치이며, 상태를 바꾸는 행은 주어진 세션을 바꾼다(application은 잠금 TX 안에서 다시 부른다).
 *
 * - [beforeLookup](1단계, 외부 호출 전): P1 종료 → IGNORED, P2 같은 결과 참조의 VERIFIED → DUPLICATE, P3 다른 참조의 VERIFIED → REJECTED(증거 충돌),
 *   P4 CREATED → REJECTED, P5 Payment가 REQUESTED 아님·결제창 시각 경과 → 세션 EXPIRED(PAYMENT_CLOSED, **Payment는 바꾸지 않는다**),
 *   P6 세션 만료 시각 경과 → EXPIRED(TTL_ELAPSED), 그 밖 PROCEED.
 * - [afterLookup](2단계, TX-C): 1단계를 다시 판정한 뒤 R3 확인 불가(상태 불변) → R6 다른 거래·state → R5 기관 근거 만료 → R7 바인딩·발급사 불일치 →
 *   R8 인증 실패 → R9 사용자 취소 → R10 미지원 cardType → R11 VERIFIED.
 */
object AuthenticationResultCheck {

    fun beforeLookup(session: AuthenticationSession, payment: Payment, presented: PresentedResult, now: Instant): ResultCheckDecision {
        require(session.binding.paymentId == payment.id) { "the session must belong to this payment" }
        require(session.correlation.stateDigest == presented.stateDigest) { "the session must be the one selected by the presented state" }
        return when (session.status) {
            AuthenticationStatus.FAILED, AuthenticationStatus.EXPIRED -> decision(ResultVerdict.IGNORED, AuthenticationEventKind.IGNORED_LATE_RESULT)
            AuthenticationStatus.VERIFIED ->
                if (presented.resultRefDigest == session.resultRefDigest) {
                    decision(ResultVerdict.DUPLICATE, AuthenticationEventKind.DUPLICATE_RESULT)
                } else {
                    decision(ResultVerdict.REJECTED, AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.EVIDENCE_CONFLICT)
                }
            AuthenticationStatus.CREATED -> decision(ResultVerdict.REJECTED, AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.SESSION_NOT_STARTED)
            AuthenticationStatus.IN_PROGRESS -> when {
                payment.status != PaymentStatus.REQUESTED || payment.isCheckoutWindowElapsed(now) -> {
                    session.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, now)
                    decision(ResultVerdict.PAYMENT_CLOSED, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.PAYMENT_CLOSED)
                }
                !now.isBefore(session.expiresAt) -> {
                    session.expire(AuthenticationExpiryCause.TTL_ELAPSED, now)
                    decision(ResultVerdict.SESSION_EXPIRED, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.TTL_ELAPSED)
                }
                else -> decision(ResultVerdict.PROCEED, null)
            }
        }
    }

    fun afterLookup(
        session: AuthenticationSession,
        payment: Payment,
        presented: PresentedResult,
        lookup: ProviderResultLookup,
        authenticationRef: AuthenticationRef,
        now: Instant,
    ): ResultCheckDecision {
        val first = beforeLookup(session, payment, presented, now)
        if (first.verdict != ResultVerdict.PROCEED) return first
        val result = when (lookup) {
            is ProviderResultLookup.Unverifiable ->
                return decision(ResultVerdict.UNVERIFIABLE, AuthenticationEventKind.LOOKUP_UNVERIFIABLE, AuthenticationEventDetail.valueOf(lookup.reason.name))
            is ProviderResultLookup.Found -> lookup.result
        }
        val rrd = presented.resultRefDigest
        val details = result.authenticated
        return when {
            // R6: 다른 거래의 결과다 — 그 결과 참조는 이 세션의 것이 아니므로 소비 기록(UNIQUE digest)으로 남기지 않는다(남기면 정당한 세션의 VERIFIED가
            // 1062로 막힌다 — 검토 m-1). 상세는 이벤트·로그로만.
            result.providerTransactionRef != session.providerTransactionRef || result.stateDigest != session.correlation.stateDigest ->
                failed(session, AuthenticationFailure.STATE_MISMATCH, null, AuthenticationEventDetail.STATE_MISMATCH)
            result.outcome == ProviderOutcome.EXPIRED || !now.isBefore(result.validUntil) ->
                failed(session, AuthenticationFailure.PROVIDER_EXPIRED, rrd, AuthenticationEventDetail.PROVIDER_EXPIRED)
            result.binding != echoOf(session.binding) || (details != null && details.issuerCode != session.binding.cardCompany) ->
                failed(session, AuthenticationFailure.BINDING_MISMATCH, rrd, AuthenticationEventDetail.BINDING_MISMATCH)
            result.outcome == ProviderOutcome.FAILED -> failed(session, AuthenticationFailure.AUTHENTICATION_FAILED, rrd, AuthenticationEventDetail.AUTHENTICATION_FAILED)
            result.outcome == ProviderOutcome.CANCELLED -> failed(session, AuthenticationFailure.USER_CANCELLED, rrd, AuthenticationEventDetail.USER_CANCELLED)
            else -> {
                val d = checkNotNull(details) { "AUTHENTICATED results carry evidence details" }
                val cardType = SUPPORTED_CARD_TYPES[d.cardType]
                    ?: return failed(session, AuthenticationFailure.AUTHENTICATION_FAILED, rrd, AuthenticationEventDetail.UNSUPPORTED_CARD_TYPE)
                val evidence = VerifiedAuthentication(d.evidenceRef, session.binding, cardType, d.issuerCode, d.acquirerCode, d.providerCode, result.validUntil)
                val outcome = session.verify(evidence, rrd, authenticationRef, now)
                check(outcome == VerificationOutcome.VERIFIED) { "every earlier rule passed, verification must succeed: $outcome" }
                decision(ResultVerdict.VERIFIED, AuthenticationEventKind.VERIFIED)
            }
        }
    }

    private val SUPPORTED_CARD_TYPES: Map<String, CardType> = CardType.entries.associateBy { it.name }

    private fun echoOf(b: AuthenticationBinding) =
        ProviderBindingEcho(b.paymentId, b.orderKey.environment, b.orderKey.merchantAccountId, b.money, b.plan, b.cardCompany)

    private fun failed(session: AuthenticationSession, reason: AuthenticationFailure, rrd: SecretDigest?, detail: AuthenticationEventDetail): ResultCheckDecision {
        session.fail(reason, rrd)
        return decision(ResultVerdict.FAILED, AuthenticationEventKind.FAILED, detail)
    }

    private fun decision(verdict: ResultVerdict, event: AuthenticationEventKind?, detail: AuthenticationEventDetail? = null) = ResultCheckDecision(verdict, event, detail)
}
