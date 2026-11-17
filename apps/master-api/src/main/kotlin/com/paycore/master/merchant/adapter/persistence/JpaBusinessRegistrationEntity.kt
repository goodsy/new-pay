package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * business_registration 행(BUSINESS-PROFILE-001 §5.2 — 사업자당 정확히 1행, UNIQUE(business_id)). 연관 매핑 없이 business_id 문자열로만 잇는다(§9.1 관례 — cascade 경로 없음).
 * 내용 열은 제자리 수정(전후 값은 master_change_history)이고 소속(business_id)·국가(KR 고정)는 `updatable = false`.
 * 행 ID는 DB AUTO_INCREMENT(IDENTITY — persist 즉시 INSERT). 업태·종목·주소 행이 이 ID를 FK로 가진다.
 */
@Entity
@Table(name = "business_registration")
class JpaBusinessRegistrationEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "registration_id", nullable = false, updatable = false)
    var registrationId: Long? = null
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "corporate_registration_number", columnDefinition = "char(13)")
    var corporateRegistrationNumber: String? = null
        protected set

    @field:Column(name = "opening_date")
    var openingDate: LocalDate? = null
        protected set

    @field:Column(name = "country_code", nullable = false, updatable = false, columnDefinition = "char(2)")
    lateinit var countryCode: String
        protected set

    @field:Column(name = "english_name", length = 100)
    var englishName: String? = null
        protected set

    @field:Column(name = "phone_number", length = 12)
    var phoneNumber: String? = null
        protected set

    @field:Column(name = "email", length = 254)
    var email: String? = null
        protected set

    /** 제자리 수정(값이 같으면 변경 감지 UPDATE가 나가지 않는다). */
    fun apply(corporate: String?, opening: LocalDate?, english: String?, phone: String?, mail: String?) {
        corporateRegistrationNumber = corporate
        openingDate = opening
        englishName = english
        phoneNumber = phone
        email = mail
    }

    /** 저장 전 id가 없으므로 동일성은 인스턴스 기준(비교·컬렉션에 쓰지 않는다). toString은 ID만(개인정보·번호 없음). */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaBusinessRegistrationEntity(registrationId=$registrationId, businessId=$businessId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(businessId: String, countryCode: String, corporate: String?, opening: LocalDate?, english: String?, phone: String?, mail: String?) =
            JpaBusinessRegistrationEntity().also {
                it.businessId = businessId
                it.countryCode = countryCode
                it.apply(corporate, opening, english, phone, mail)
            }
    }
}