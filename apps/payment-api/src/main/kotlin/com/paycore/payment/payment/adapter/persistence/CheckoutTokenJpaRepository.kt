package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/*
 * AUTHN-001 Spring Data repository(adapter.persistence 전용). 잠금 읽기는 PESSIMISTIC_WRITE(`SELECT … FOR UPDATE`) — 잠금 순서 payment → authentication_session →
 * checkout_session → checkout_token(명세 §10.3)은 호출자(application)가 지킨다. 비잠금 조회(digest·handle·state)는 잠금 전 대상 식별용이다.
 * 행 하나 잠금은 Store가 `lockRow`(find + refresh PESSIMISTIC_WRITE), 목록 잠금은 native `SELECT id … FOR UPDATE` 뒤 `lockRows`다(검토 #2 m-1 —
 * JPQL 잠금 조회는 영속성 컨텍스트에 이미 있는 Entity를 다시 읽지 않는다. `PessimisticRowLock` 참고).
 */

interface CheckoutTokenJpaRepository : JpaRepository<JpaCheckoutTokenEntity, String> {
    fun findByTokenDigest(tokenDigest: String): JpaCheckoutTokenEntity?

    /** 같은 Payment의 ISSUED 토큰 id를 잠그며 읽는다(최신 커밋 행 기준 판정 — 검토 #2 m-1). Entity는 Store가 `lockRows`로 읽는다. */
    @Query(
        value = "SELECT checkout_token_id FROM checkout_token WHERE payment_id = :paymentId AND status = 'ISSUED' ORDER BY checkout_token_id FOR UPDATE",
        nativeQuery = true,
    )
    fun lockIssuedIdsByPayment(@Param("paymentId") paymentId: String): List<String>
}
