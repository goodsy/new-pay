package com.paycore.master.merchant.domain

import java.time.LocalDate

/**
 * 사업자 등록정보(1:1 값 — 표 `business_registration`, TBD-03 확장 대비 별도 표). 사업자등록번호·상호는 Aggregate Root([Business]) 필드다(C-8 — 기존 UNIQUE·불변 근거 유지).
 * [openingDate]는 달력 날짜(시간대 없음, 범위 규칙 없음 — C-21), [countryCode]는 `KR`만(TBD-10 — 규칙 [BusinessProfileRules]가 판정).
 * [phoneNumber]·[email]은 사업체 연락처(등록 담당자와 별개).
 */
data class BusinessRegistration(
    val corporateRegistrationNumber: CorporateRegistrationNumber?,
    val openingDate: LocalDate?,
    val countryCode: String,
    val englishName: EnglishBusinessName?,
    val phoneNumber: PhoneNumber?,
    val email: EmailAddress?,
) {
    companion object {
        const val KOREA = "KR"

        /** 아무것도 입력하지 않은 등록정보(DRAFT 최소·migration 행 — 국가 KR만). */
        val EMPTY = BusinessRegistration(null, null, KOREA, null, null, null)
    }
}