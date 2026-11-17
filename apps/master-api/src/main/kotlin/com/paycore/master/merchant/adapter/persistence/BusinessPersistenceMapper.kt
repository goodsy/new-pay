package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.AddressLine
import com.paycore.master.merchant.domain.BusinessAddress
import com.paycore.master.merchant.domain.BusinessAddressType
import com.paycore.master.merchant.domain.BusinessCategory
import com.paycore.master.merchant.domain.BusinessCategoryType
import com.paycore.master.merchant.domain.BusinessContact
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistration
import com.paycore.master.merchant.domain.BusinessRepresentative
import com.paycore.master.merchant.domain.CategoryName
import com.paycore.master.merchant.domain.CorporateRegistrationNumber
import com.paycore.master.merchant.domain.EmailAddress
import com.paycore.master.merchant.domain.EnglishBusinessName
import com.paycore.master.merchant.domain.PersonName
import com.paycore.master.merchant.domain.PhoneNumber
import com.paycore.master.merchant.domain.PostalCode
import com.paycore.master.merchant.domain.RepresentativeType

/**
 * 사업자 하위 행 ↔ [BusinessProfile] 변환(BUSINESS-PROFILE-001 §5.5, adapter 전용). 복원은 값 객체 생성자를 거치므로 저장 값이 현재 규칙에서 존재할 수 없으면
 * 조용히 건너뛰지 않고 예외로 드러난다(MasterPersistenceMapper와 같은 관례). 현재 행(effective_to IS NULL)만 받는다 — 과거 행은 Aggregate에 싣지 않는다.
 */
internal object BusinessPersistenceMapper {

    fun toProfile(
        registration: JpaBusinessRegistrationEntity,
        representatives: List<JpaBusinessRepresentativeEntity>,
        categories: List<JpaBusinessCategoryEntity>,
        addresses: List<JpaBusinessAddressEntity>,
        contacts: List<JpaBusinessContactEntity>,
    ): BusinessProfile = BusinessProfile(
        toRegistration(registration),
        representatives.sortedBy { it.displayOrder }.map(::toRepresentative),
        categories.sortedWith(compareBy({ BusinessCategoryType.valueOf(it.categoryType).ordinal }, { it.displayOrder })).map(::toCategory),
        addresses.sortedBy { BusinessAddressType.valueOf(it.addressType).ordinal }.map(::toAddress),
        contacts.sortedBy { it.displayOrder }.map(::toContact),
    )

    fun toRegistration(row: JpaBusinessRegistrationEntity) = BusinessRegistration(
        row.corporateRegistrationNumber?.let(::CorporateRegistrationNumber), row.openingDate, row.countryCode, row.englishName?.let(::EnglishBusinessName),
        row.phoneNumber?.let(::PhoneNumber), row.email?.let(::EmailAddress),
    )

    fun toRepresentative(row: JpaBusinessRepresentativeEntity) = BusinessRepresentative(PersonName(row.name), RepresentativeType.valueOf(row.representativeType))

    fun toCategory(row: JpaBusinessCategoryEntity) = BusinessCategory(BusinessCategoryType.valueOf(row.categoryType), CategoryName(row.name))

    fun toAddress(row: JpaBusinessAddressEntity) =
        BusinessAddress(BusinessAddressType.valueOf(row.addressType), PostalCode(row.postalCode), AddressLine(row.baseAddress), row.detailAddress?.let(::AddressLine))

    fun toContact(row: JpaBusinessContactEntity) = BusinessContact(PersonName(row.name), EmailAddress(row.email), PhoneNumber(row.phoneNumber), row.primary)

    fun registrationRow(businessId: String, r: BusinessRegistration) = JpaBusinessRegistrationEntity.newRow(
        businessId, r.countryCode, r.corporateRegistrationNumber?.value, r.openingDate, r.englishName?.value, r.phoneNumber?.value, r.email?.value,
    )
}
