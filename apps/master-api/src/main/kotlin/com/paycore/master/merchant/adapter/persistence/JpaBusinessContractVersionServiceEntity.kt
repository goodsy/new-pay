package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable

/**
 * `business_contract_version_service` 행(MERCHANT-FLOW-001 §4B.10 4 — 버전 범위 서비스 종류 한 개, **INSERT 전용**, I-5 — 버전마다 ≥ 1행은 Domain·저장 시험이 지킨다).
 * PK = 모든 열. JPA 선택은 [JpaBusinessContractEntity]와 같다.
 */
@Entity
@Immutable
@IdClass(JpaBusinessContractVersionServiceEntity.Key::class)
@Table(name = "business_contract_version_service")
class JpaBusinessContractVersionServiceEntity protected constructor() {
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

    @field:Id
    @field:Column(name = "service_kind", nullable = false, updatable = false, length = 32)
    lateinit var serviceKind: String
        protected set

    data class Key(var businessId: String = "", var contractSeq: Int = 0, var versionNo: Int = 0, var serviceKind: String = "") : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessContractVersionServiceEntity).businessId &&
                contractSeq == other.contractSeq && versionNo == other.versionNo && serviceKind == other.serviceKind
            )

    override fun hashCode(): Int = ((31 * businessId.hashCode() + contractSeq) * 31 + versionNo) * 31 + serviceKind.hashCode()

    override fun toString(): String =
        "JpaBusinessContractVersionServiceEntity(businessId=$businessId, contractSeq=$contractSeq, versionNo=$versionNo, serviceKind=$serviceKind)"

    companion object {
        fun newRow(businessId: String, contractSeq: Int, versionNo: Int, serviceKind: String) = JpaBusinessContractVersionServiceEntity().also {
            it.businessId = businessId
            it.contractSeq = contractSeq
            it.versionNo = versionNo
            it.serviceKind = serviceKind
        }
    }
}
