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
import java.time.LocalDate

/**
 * `business_contract_version` 행(MERCHANT-FLOW-001 §4B.3·§4B.10 4 — 버전 한 판, **INSERT 전용**, BR-BIZ-329 "저장 뒤 바꾸지 않는다"). JPA 선택은 [JpaBusinessContractEntity]와 같다
 * (`@Immutable`·persist만·`@Version` 없음·연관 매핑 없음). 근거 참조·사유 원문은 이 표에만 — toString에 넣지 않는다. 범위는 별도 표([JpaBusinessContractVersionServiceEntity]).
 */
@Entity
@Immutable
@IdClass(JpaBusinessContractVersionEntity.Key::class)
@Table(name = "business_contract_version")
class JpaBusinessContractVersionEntity protected constructor() {
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

    @field:Column(name = "origin_intake_seq", nullable = false, updatable = false)
    var originIntakeSeq: Int = 0
        protected set

    @field:Column(name = "migrated", nullable = false, updatable = false)
    var migrated: Boolean = false
        protected set

    @field:Column(name = "starts_at", nullable = false, updatable = false)
    lateinit var startsAt: Instant
        protected set

    @field:Column(name = "start_date", updatable = false)
    var startDate: LocalDate? = null
        protected set

    @field:Column(name = "end_date", updatable = false)
    var endDate: LocalDate? = null
        protected set

    @field:Column(name = "document_ref", updatable = false, length = 128)
    var documentRef: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 200)
    var reason: String? = null
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
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessContractVersionEntity).businessId &&
                contractSeq == other.contractSeq && versionNo == other.versionNo
            )

    override fun hashCode(): Int = (31 * businessId.hashCode() + contractSeq) * 31 + versionNo

    override fun toString(): String = "JpaBusinessContractVersionEntity(businessId=$businessId, contractSeq=$contractSeq, versionNo=$versionNo, migrated=$migrated)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            businessId: String,
            contractSeq: Int,
            versionNo: Int,
            originIntakeSeq: Int,
            startsAt: Instant,
            startDate: LocalDate,
            endDate: LocalDate?,
            documentRef: String,
            reason: String?,
            at: Instant,
            by: String,
        ) = JpaBusinessContractVersionEntity().also {
            it.businessId = businessId
            it.contractSeq = contractSeq
            it.versionNo = versionNo
            it.originIntakeSeq = originIntakeSeq
            it.migrated = false
            it.startsAt = startsAt
            it.startDate = startDate
            it.endDate = endDate
            it.documentRef = documentRef
            it.reason = reason
            it.createdAt = at
            it.createdBy = by
        }
    }
}
