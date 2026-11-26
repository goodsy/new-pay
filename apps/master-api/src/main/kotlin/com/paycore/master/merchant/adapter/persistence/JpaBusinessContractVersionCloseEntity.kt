package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/**
 * `business_contract_version_close` 행(MERCHANT-FLOW-001 §4B.3 — BR-BIZ-341 "새 버전 시작이 직전 버전을 닫는 기록", **INSERT 전용**). PK = 닫힌 버전(버전당 닫기 1개),
 * `closed_by_version_no = version_no + 1`(DB CHECK). JPA 선택은 [JpaBusinessContractEntity]와 같다.
 */
@Entity
@Immutable
@IdClass(JpaBusinessContractVersionCloseEntity.Key::class)
@Table(name = "business_contract_version_close")
class JpaBusinessContractVersionCloseEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Id
    @field:Column(name = "contract_seq", nullable = false, updatable = false)
    var contractSeq: Int = 0
        protected set

    @field:Id
    @field:Column(name = "version_no", nullable = false, updatable = false)
    var versionNo: Int = 0
        protected set

    @field:Column(name = "closed_at", nullable = false, updatable = false)
    lateinit var closedAt: Instant
        protected set

    @field:Column(name = "closed_by_version_no", nullable = false, updatable = false)
    var closedByVersionNo: Int = 0
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    data class Key(var businessId: String = "", var contractSeq: Int = 0, var versionNo: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessContractVersionCloseEntity).businessId &&
                contractSeq == other.contractSeq && versionNo == other.versionNo
            )

    override fun hashCode(): Int = (31 * businessId.hashCode() + contractSeq) * 31 + versionNo

    override fun toString(): String = "JpaBusinessContractVersionCloseEntity(businessId=$businessId, contractSeq=$contractSeq, versionNo=$versionNo)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(businessId: String, contractSeq: Int, versionNo: Int, closedAt: Instant, closedByVersionNo: Int, at: Instant, by: String) =
            JpaBusinessContractVersionCloseEntity().also {
                it.businessId = businessId
                it.contractSeq = contractSeq
                it.versionNo = versionNo
                it.closedAt = closedAt
                it.closedByVersionNo = closedByVersionNo
                it.createdAt = at
                it.createdBy = by
            }
    }
}
