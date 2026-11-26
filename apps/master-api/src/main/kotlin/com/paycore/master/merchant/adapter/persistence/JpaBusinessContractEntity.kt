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
 * `business_contract` 행(MERCHANT-FLOW-001 §4B.10 4 — 사업자 계약 머리 행, **INSERT 전용**). DB 행 모양만 가진다 — 상태 계산·기간 규칙은 순수 Domain `BusinessContract`.
 *
 * JPA 선택(이 코드의 역할·예·이유·주의점 — §4B.12):
 * - **역할**: 열만 들고 규칙이 없다. 예: "효력 전 계약은 정지·해지만 가능"은 `BusinessContract`가 판정하고, 이 Entity는 결과로 생긴 새 행을 옮겨 적는다. 이유: Domain을 DB 없이 시험하고
 *   JPA·Spring에 의존하지 않게(Mapper `BusinessContractPersistenceMapper`가 잇는다).
 * - **INSERT 전용**: `@Immutable` + 모든 열 `updatable = false` + protected setter — Hibernate가 UPDATE를 만들 길이 없다. 새 행은 `persist`만(Spring Data `save`는 merge가 될 수 있다).
 *   runtime DB 권한도 SELECT, INSERT만(V22 — UPDATE·DELETE 1142)이라 이중 방어다.
 * - **`@Version` 없음**: 행이 바뀌지 않으므로 낙관적 잠금 열이 없다. 동시성은 사업자 행 잠금 + 기대 사건 수 + DB UNIQUE(§4B.9).
 * - **연관 매핑 없음**: 버전·사건 Entity와 `@OneToMany`를 두지 않는다 — 지연 로딩·컬렉션 캐시가 판정 입력을 오래된 값으로 만들지 않게, 읽기는 저장 Port가 잠금 뒤 JPQL 조회로 직접 한다.
 * - data class가 아니다. equals/hashCode는 키만, toString은 키만.
 */
@Entity
@Immutable
@IdClass(JpaBusinessContractEntity.Key::class)
@Table(name = "business_contract")
class JpaBusinessContractEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Id
    @field:Column(name = "contract_seq", nullable = false, updatable = false)
    var contractSeq: Int = 0
        protected set

    @field:Column(name = "origin_intake_seq", nullable = false, updatable = false)
    var originIntakeSeq: Int = 0
        protected set

    @field:Column(name = "migrated", nullable = false, updatable = false)
    var migrated: Boolean = false
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    data class Key(var businessId: String = "", var contractSeq: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessContractEntity).businessId &&
                contractSeq == other.contractSeq
            )

    override fun hashCode(): Int = 31 * businessId.hashCode() + contractSeq

    override fun toString(): String = "JpaBusinessContractEntity(businessId=$businessId, contractSeq=$contractSeq, migrated=$migrated)"

    companion object {
        fun newRow(businessId: String, contractSeq: Int, originIntakeSeq: Int, at: Instant, by: String) = JpaBusinessContractEntity().also {
            it.businessId = businessId
            it.contractSeq = contractSeq
            it.originIntakeSeq = originIntakeSeq
            it.migrated = false
            it.createdAt = at
            it.createdBy = by
        }
    }
}
