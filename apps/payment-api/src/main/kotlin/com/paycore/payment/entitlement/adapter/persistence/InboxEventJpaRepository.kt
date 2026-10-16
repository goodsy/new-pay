package com.paycore.payment.entitlement.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InboxEventJpaRepository : JpaRepository<JpaInboxEventEntity, Long> {
    @Query("select i from JpaInboxEventEntity i where i.consumer = :consumer and i.eventId = :eventId")
    fun findByEvent(@Param("consumer") consumer: String, @Param("eventId") eventId: String): JpaInboxEventEntity?
}
