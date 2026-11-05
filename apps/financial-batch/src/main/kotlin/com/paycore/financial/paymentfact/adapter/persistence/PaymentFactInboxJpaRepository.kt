package com.paycore.financial.paymentfact.adapter.persistence

import org.springframework.data.repository.Repository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 좁은 Spring Data 표면(검토 m-2): `Repository`만 상속하고 아래 조회·자리 확보 메서드만 선언한다 — `save`·`delete*`·`flush` 계열이 없어 append-only 사실을 지우거나
 * merge할 경로가 이 인터페이스에 없다(쓰기는 Store의 `EntityManager.persist`·잠가 읽은 Entity 변경 감지뿐). 시험: PaymentFactRepositorySurfaceSpec.
 */
interface PaymentFactInboxJpaRepository : Repository<JpaPaymentFactInboxEntity, Long> {
    @Query("select i from JpaPaymentFactInboxEntity i where i.eventId = :eventId")
    fun findByEvent(@Param("eventId") eventId: String): JpaPaymentFactInboxEntity?

    @Query("select i from JpaPaymentFactInboxEntity i where i.effectKey = :effectKey")
    fun findByEffect(@Param("effectKey") effectKey: String): JpaPaymentFactInboxEntity?
}
