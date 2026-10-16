package com.paycore.payment.entitlement.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EntitlementHeadJpaRepository : JpaRepository<JpaEntitlementHeadEntity, String> {
    /** 빈 head 자리를 만든다(있으면 바꾸지 않고 그 행의 잠금을 기다린다 — 최초 경쟁을 PK로 직렬화, 관례 §9 IODKU). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT INTO entitlement_head (merchant_id, revision, version) VALUES (:merchantId, 0, 0) ON DUPLICATE KEY UPDATE version = version",
    )
    fun insertEmptyIfAbsent(@Param("merchantId") merchantId: String): Int
}
