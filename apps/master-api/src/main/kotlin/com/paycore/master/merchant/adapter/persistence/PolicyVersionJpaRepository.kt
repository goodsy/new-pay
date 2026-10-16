package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PolicyVersionJpaRepository : JpaRepository<JpaPolicyVersionEntity, String> {
    @Query("select v from JpaPolicyVersionEntity v where v.policyKind = :kind and v.policyCode = :code order by v.versionNo")
    fun findByCode(@Param("kind") kind: String, @Param("code") code: String): List<JpaPolicyVersionEntity>

    @Query("select v from JpaPolicyVersionEntity v where v.policyVersionId in :ids")
    fun findByIds(@Param("ids") ids: Collection<String>): List<JpaPolicyVersionEntity>
}
