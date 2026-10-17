package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest

/** domain 세션에 없는 생성 시 불변 속성(AUTHN-001 §10.1): 소속 결제창 세션, 인증 프로파일·Plan 정책·결제창 정책 버전(AN-21). */
data class AuthenticationSessionAttributes(
    val checkoutSessionId: CheckoutSessionId,
    val profileId: String,
    val planPolicyVersion: String,
    val policyVersion: String,
) {
    init {
        listOf(profileId, planPolicyVersion, policyVersion).forEach { require(it.isNotEmpty() && it.length <= 64) { "versions must be 1..64 characters" } }
    }
}

/**
 * 인증 세션 Aggregate 저장 Port(AUTHN-001 §11.2 — CONVENTIONS §14 Aggregate 저장 `<Aggregate>Repository`). TX 없이 호출하면 거부한다(MANDATORY).
 * 잠금 순서는 payment → authentication_session(§10.3) — 호출자가 payment 행을 먼저 잠근다.
 */
interface AuthenticationSessionRepository {
    /** 새 세션 INSERT(persist만). state·결과 참조·인증 참조·비종료·귀속 UNIQUE 충돌은 [PersistenceFailure.UniqueConflict]. 최초 version을 돌려준다. */
    fun insertNew(session: AuthenticationSession, attributes: AuthenticationSessionAttributes): Long

    fun lock(id: AuthenticationSessionId): Versioned<AuthenticationSession>?

    /** 같은 Payment의 세션 전부를 잠가 읽는다(생성 순). 인증 시작의 대체·Payment 만료가 쓴다. */
    fun lockAllOfPayment(paymentId: PaymentId): List<Versioned<AuthenticationSession>>

    /** 복귀 세션 선택(§7.3): 그 결제창 세션의 세션 중 state digest가 같은 것(비잠금, UNIQUE). */
    fun findIdByState(checkoutSessionId: CheckoutSessionId, stateDigest: SecretDigest): AuthenticationSessionId?

    /** 상태 전이를 반영한다. 생성 시 불변 값(바인딩·상관 값·만료 시각·속성)은 바꾸지 않는다. 새 version을 돌려준다. */
    fun update(session: AuthenticationSession, expectedVersion: Long): Long
}
