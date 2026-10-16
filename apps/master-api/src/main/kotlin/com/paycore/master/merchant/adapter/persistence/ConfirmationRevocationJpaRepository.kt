package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ConfirmationRevocationJpaRepository : JpaRepository<JpaConfirmationRevocationEntity, String> {
    @Query("select r from JpaConfirmationRevocationEntity r where r.confirmationId in :ids")
    fun findByConfirmations(@Param("ids") ids: Collection<String>): List<JpaConfirmationRevocationEntity>
}
