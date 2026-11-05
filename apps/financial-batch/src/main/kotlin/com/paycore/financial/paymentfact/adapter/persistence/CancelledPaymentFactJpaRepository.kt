package com.paycore.financial.paymentfact.adapter.persistence

import org.springframework.data.repository.Repository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 좁은 Spring Data 표면(검토 m-2): `Repository`만 상속하고 아래 조회·자리 확보 메서드만 선언한다 — `save`·`delete*`·`flush` 계열이 없어 append-only 사실을 지우거나
 * merge할 경로가 이 인터페이스에 없다(쓰기는 Store의 `EntityManager.persist`·잠가 읽은 Entity 변경 감지뿐). 시험: PaymentFactRepositorySurfaceSpec.
 */
interface CancelledPaymentFactJpaRepository : Repository<JpaCancelledPaymentFactEntity, String> {
    @Query("select c from JpaCancelledPaymentFactEntity c where c.paymentId = :paymentId order by c.receivedAt, c.transactionId")
    fun findByPayment(@Param("paymentId") paymentId: String): List<JpaCancelledPaymentFactEntity>
}
