package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentId

/**
 * 논리 승인 Aggregate 저장 Port(AUTH-001 명세 §7.3 — CONVENTIONS §14 Aggregate 저장 `<Aggregate>Repository`; AUTH-CONTRACT-001 §9.1의 "Store" 이름을 이 기준으로 바꿨다).
 * Attempt·관찰(키 상충 관찰 포함)·거래는 Aggregate 안이라 별도 Port가 없다. 관찰·거래는 append-only(저장된 것을 바꾸거나 지울 수 없다). TX 없이 호출하면 거부한다(MANDATORY).
 * 잠금 순서는 control → payment → payment_authorization → authorization_attempt(§4.7) — 호출자가 앞 행을 먼저 잠근다.
 */
interface PaymentAuthorizationRepository {
    /** 새 승인(TX1 — Attempt PREPARED 포함) INSERT(persist만). Payment당 하나·기관 요청 namespace·동시 실행 Attempt UNIQUE 충돌은 [PersistenceFailure.UniqueConflict]. 최초 version. */
    fun insertNew(authorization: PaymentAuthorization): Long

    fun lock(id: AuthorizationId): Versioned<PaymentAuthorization>?

    /** Payment의 승인을 잠가 읽는다(Payment 1:1). hold 대상 Payment의 승인을 찾는 데도 쓴다(AC 11 — 해제 Use Case는 승인이 있으면 함께 넘긴다). */
    fun lockByPaymentId(paymentId: PaymentId): Versioned<PaymentAuthorization>?

    /** 상태·Attempt 상태를 반영하고 새 관찰·거래를 append한다. 저장된 관찰·거래가 domain에서 사라지거나 바뀌었으면 결함으로 거부한다. 새 version을 돌려준다. */
    fun update(authorization: PaymentAuthorization, expectedVersion: Long): Long
}
