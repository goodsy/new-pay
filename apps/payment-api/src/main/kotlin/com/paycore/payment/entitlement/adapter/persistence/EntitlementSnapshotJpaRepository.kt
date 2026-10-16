package com.paycore.payment.entitlement.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface EntitlementSnapshotJpaRepository : JpaRepository<JpaEntitlementSnapshotEntity, SnapshotKey>
