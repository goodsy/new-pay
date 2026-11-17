package com.paycore.admin.merchantops.application.provided

/**
 * 필드 오류(BUSINESS-PROFILE-001 §4.6 — path·code만, **입력 값 없음**). path 문법·code 집합은 MASTER와 같다(ADMIN 복제 규칙 `BusinessProfileInputRules`·MASTER 400
 * `BUSINESS_VALIDATION_FAILED` 응답 모두 — 응답은 gateway가 [PATH]·[CODES]로 모양 검사한다).
 */
data class BusinessFieldError(val path: String, val code: String) {
    init {
        require(PATH.matches(path) && code in CODES) { "field error path/code outside the §4.6 grammar" }
    }

    companion object {
        private const val INDEX = "\\[(0|[1-9][0-9]?)\\]"

        /** §4.6 path 문법(i = 0부터 — 목록 전체 상한 안의 정수). */
        val PATH = Regex(
            "^(businessType|registrationNumber|displayName|reason|version" +
                "|registration\\.(corporateRegistrationNumber|openingDate|countryCode|englishName|phoneNumber|email)" +
                "|representatives($INDEX\\.(name|type))?" +
                "|categories($INDEX\\.(type|name))?" +
                "|addresses($INDEX\\.(type|postalCode|baseAddress|detailAddress))?" +
                "|contacts($INDEX\\.(name|email|phoneNumber|primary))?)$",
        )

        /** §4.6 code 집합(MASTER `BusinessFieldErrorCode`와 같다). */
        val CODES = setOf(
            "REQUIRED", "FORMAT", "TOO_LONG", "TOO_MANY", "DUPLICATE", "NOT_ALLOWED", "SOLE_WITH_OTHERS", "PRIMARY_REQUIRED", "PRIMARY_MULTIPLE",
            "ACTUAL_SAME_AS_REGISTERED", "BUSINESS_TYPE_ITEM_REQUIRED",
        )

        /** 한 응답 최대 개수(§4.6). */
        const val MAX = 50
    }
}
