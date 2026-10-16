package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface SnapshotIssueJpaRepository : JpaRepository<JpaSnapshotIssueEntity, SnapshotIssueKey> {
    @Query("select i from JpaSnapshotIssueEntity i where i.key.merchantId = :merchantId order by i.key.revision desc, i.key.leaseSeq desc")
    fun findLatestFirst(@Param("merchantId") merchantId: String, page: org.springframework.data.domain.Pageable): List<JpaSnapshotIssueEntity>

    @Query(
        "select i.key.merchantId from JpaSnapshotIssueEntity i group by i.key.merchantId having max(i.leaseUntil) < :threshold " +
            "order by max(i.leaseUntil)",
    )
    fun merchantsWithLeaseBefore(@Param("threshold") threshold: Instant, page: org.springframework.data.domain.Pageable): List<String>
}
