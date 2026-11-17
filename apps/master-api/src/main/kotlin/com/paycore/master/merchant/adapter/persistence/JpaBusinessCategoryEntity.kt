package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** business_category 행(업태·종목 — 등록정보 소속, 순서는 유형 안 1..10). 공통 규칙은 [JpaBusinessRepresentativeEntity] 파일 머리 주석. */
@Entity
@Table(name = "business_category")
class JpaBusinessCategoryEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "category_id", nullable = false, updatable = false)
    var categoryId: Long? = null
        protected set

    @field:Column(name = "registration_id", nullable = false, updatable = false)
    var registrationId: Long = 0
        protected set

    @field:Column(name = "category_type", nullable = false, updatable = false, length = 16)
    lateinit var categoryType: String
        protected set

    @field:Column(name = "name", nullable = false, updatable = false, length = 50)
    lateinit var name: String
        protected set

    @field:Column(name = "display_order", nullable = false, updatable = false, columnDefinition = "smallint")
    var displayOrder: Int = 0
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Column(name = "ended_by", length = 64)
    var endedBy: String? = null
        protected set

    /** 종료 기록(유일한 UPDATE — effective_to·ended_by). 이미 종료된 행은 다시 종료하지 않는다. */
    fun end(at: Instant, by: String) {
        check(effectiveTo == null) { "row already ended" }
        effectiveTo = at
        endedBy = by
    }

    /** 저장 전 id가 없으므로 동일성은 인스턴스 기준(비교·컬렉션에 쓰지 않는다). */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaBusinessCategoryEntity(id=$categoryId, type=$categoryType, order=$displayOrder, ended=${effectiveTo != null})"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(registrationId: Long, type: String, name: String, order: Int, at: Instant, by: String) = JpaBusinessCategoryEntity().also {
            it.registrationId = registrationId
            it.categoryType = type
            it.name = name
            it.displayOrder = order
            it.effectiveFrom = at
            it.createdBy = by
        }
    }
}