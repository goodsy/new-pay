package com.paycore.master.merchant.application.provided

import java.time.Instant
import java.time.LocalDate

/**
 * 사업자 상세(BUSINESS-PROFILE-001 §6.4 — 현재 행만, 표시 순서대로). **개인정보·법인등록번호는 원문**(D-BIZ-006 ② — 상세·수정 화면 전용, 열람 감사·조회 창은 ADMIN BIZ-3).
 * 사업자등록번호는 원문 10자리(BRN-UNMASK-001 — D-BIZ-007: 조회 응답 원문 10자리, 응답 필드 `registrationNumber` — v2 계약). [BusinessBlock.version]은 수정 요청의 기대값(낙관적 잠금).
 * 업태·종목은 업태 → 종목, 각 유형 안 순서. 주소는 사업장 → 실사업장.
 */
data class BusinessDetailView(
    val business: BusinessBlock,
    val registration: RegistrationBlock,
    val representatives: List<Representative>,
    val categories: List<Category>,
    val addresses: List<Address>,
    val contacts: List<Contact>,
) {
    data class BusinessBlock(
        val businessId: String,
        val businessName: String,
        val registrationNumber: String,
        val businessType: String?,
        val registrationStatus: String,
        val verificationStatus: String,
        val lifecycle: String,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
        val merchantCount: Long,
    ) {
        /** 로그 마스킹 유지(D-BIZ-007) — 번호는 [registrationNumberLogText]. */
        override fun toString(): String =
            "BusinessBlock(businessId=$businessId, businessName=$businessName, registrationNumber=${registrationNumberLogText(registrationNumber)}, " +
                "businessType=$businessType, registrationStatus=$registrationStatus, verificationStatus=$verificationStatus, lifecycle=$lifecycle, version=$version, " +
                "createdAt=$createdAt, updatedAt=$updatedAt, merchantCount=$merchantCount)"
    }

    data class RegistrationBlock(
        val corporateRegistrationNumber: String?,
        val openingDate: LocalDate?,
        val countryCode: String,
        val englishName: String?,
        val phoneNumber: String?,
        val email: String?,
    )

    data class Representative(val name: String, val representativeType: String)

    data class Category(val categoryType: String, val name: String)

    data class Address(val addressType: String, val postalCode: String, val baseAddress: String, val detailAddress: String?)

    data class Contact(val name: String, val email: String, val phoneNumber: String, val primary: Boolean)

    /** 개인정보를 출력하지 않는다(로그·예외에 상세가 섞여도 원문이 나가지 않게). */
    override fun toString(): String = "BusinessDetailView(businessId=${business.businessId})"
}
