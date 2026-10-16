package com.paycore.payment.entitlement.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MidOwnerJpaRepository : JpaRepository<JpaMidOwnerEntity, String>
