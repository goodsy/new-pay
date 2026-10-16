package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SiteActivationConfirmationJpaRepository : JpaRepository<JpaSiteActivationConfirmationEntity, String> {
    @Query("select c from JpaSiteActivationConfirmationEntity c where c.activationId = :activationId order by c.confirmedAt, c.confirmationId")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaSiteActivationConfirmationEntity>

    @Query("select c from JpaSiteActivationConfirmationEntity c where c.merchantAccountId = :accountId order by c.confirmedAt, c.confirmationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaSiteActivationConfirmationEntity>
}
