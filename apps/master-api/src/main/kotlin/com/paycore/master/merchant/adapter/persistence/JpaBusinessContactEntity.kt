package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** business_contact 행(사업자 측 등록 담당자 — 역할 REGISTRATION, 현재 기본 담당자 최대 하나). 공통 규칙은 [JpaBusinessRepresentativeEntity] 파일 머리 주석. */
@Entity
@Table(name = "business_contact")
class JpaBusinessContactEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "contact_id", nullable = false, updatable = false)
    var contactId: Long? = null
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "contact_role", nullable = false, updatable = false, length = 16)
    lateinit var contactRole: String
        protected set

    @field:Column(name = "name", nullable = false, updatable = false, length = 50)
    lateinit var name: String
        protected set

    @field:Column(name = "email", nullable = false, updatable = false, length = 254)
    lateinit var email: String
        protected set

    @field:Column(name = "phone_number", nullable = false, updatable = false, length = 12)
    lateinit var phoneNumber: String
        protected set

    @field:Column(name = "is_primary", nullable = false, updatable = false)
    var primary: Boolean = false
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

    override fun toString(): String = "JpaBusinessContactEntity(id=$contactId, order=$displayOrder, primary=$primary, ended=${effectiveTo != null})"

    companion object {
        const val ROLE_REGISTRATION = "REGISTRATION"

        @Suppress("LongParameterList")
        fun newRow(businessId: String, name: String, email: String, phone: String, primary: Boolean, order: Int, at: Instant, by: String) =
            JpaBusinessContactEntity().also {
                it.businessId = businessId
                it.contactRole = ROLE_REGISTRATION
                it.name = name
                it.email = email
                it.phoneNumber = phone
                it.primary = primary
                it.displayOrder = order
                it.effectiveFrom = at
                it.createdBy = by
            }
    }
}