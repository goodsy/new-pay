package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventJpaRepository : JpaRepository<JpaOutboxEventEntity, Long>
