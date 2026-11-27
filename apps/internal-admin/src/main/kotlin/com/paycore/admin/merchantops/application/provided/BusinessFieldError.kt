package com.paycore.admin.merchantops.application.provided

/**
 * 필드 오류(BUSINESS-PROFILE-001 §4.6 — path·code만, **입력 값 없음**). path 문법·code 집합은 MASTER와 같다(ADMIN 복제 규칙 `BusinessProfileInputRules`·MASTER 400
 * `BUSINESS_VALIDATION_FAILED` 응답 모두 — 응답은 gateway가 [PATH]·[CODES]로 모양 검사한다).
 */
data class BusinessFieldError(val path: String, val code: String) {
    init {
        require((PATH.matches(path) || CONTRACT_INTAKE_PATH.matches(path)) && code in CODES) { "field error path/code outside the §4.6 grammar" }
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

        /**
         * MERCHANT-FLOW-001 FLOW-2b 계약 접수 건 명령 path 문법(MASTER `ContractIntakeRequestParser` 값 단계 + ADMIN 1차 검사 — 같은 이름). 사업자 명령 응답 모양([PATH])과 따로 둔다
         * (한 명령의 400 응답에 다른 명령의 path가 오면 해석 불가 — fail-closed). `reason`은 두 문법에 다 있다.
         */
        val CONTRACT_INTAKE_PATH = Regex(
            "^(receivedOn|intakeChannel|contractManager|desiredServices(\\[[01]\\])?|reason|documentRef|evidenceRef|startDate|endDate|services(\\[0\\])?)$",
        )

        /** §4.6 code 집합(MASTER `BusinessFieldErrorCode`와 같다). */
        val CODES = setOf(
            "REQUIRED", "FORMAT", "TOO_LONG", "TOO_MANY", "DUPLICATE", "NOT_ALLOWED", "SOLE_WITH_OTHERS", "PRIMARY_REQUIRED", "PRIMARY_MULTIPLE",
            "ACTUAL_SAME_AS_REGISTERED", "BUSINESS_TYPE_ITEM_REQUIRED",
            // SEC-BIZ-001 M-1(BR-BIZ-305): '사유 및 비고' 카드번호 패턴. 이 code를 만드는 곳은 `reason` 검사뿐이다(ADMIN 복제 규칙·MASTER `BusinessChangeReason`).
            // 집합 검사는 path와 짝짓지 않는다(다른 code와 같은 방식 — MASTER 400 응답 모양 검사도 path·code를 따로 본다).
            "CARD_NUMBER_PATTERN",
        )

        /** 한 응답 최대 개수(§4.6). */
        const val MAX = 50
    }
}
