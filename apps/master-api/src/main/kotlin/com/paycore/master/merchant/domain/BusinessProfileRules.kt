package com.paycore.master.merchant.domain

/**
 * 사업자 내용 규칙(명세 §4.3 — 순수 함수). 모든 위반을 모아 [BusinessFieldError] 목록으로 돌려준다(값 없음, path 사전순 → code, 최대 [MAX_ERRORS]).
 * 같은 함수를 ① 입력 해석 직후(BIZ-2 — TX 전 400, 저장 안 함)와 ② Aggregate 생성·전이 안([require] — 위반이면 [BusinessProfileRuleViolation] 결함)에서 쓴다(C-16).
 * 값 형식(§4.2)은 각 값 객체의 `errorOf`가 판정한다 — 여기 입력은 이미 값 객체다.
 */
object BusinessProfileRules {
    // 기술 상한(C-19 — 업무 정책 아님, 서명 채널 본문 16 KiB 안).
    const val MAX_REPRESENTATIVES = 5
    const val MAX_CATEGORIES_PER_TYPE = 10
    const val MAX_ADDRESSES = 2
    const val MAX_CONTACTS = 5
    const val MAX_ERRORS = 50

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun validate(
        type: BusinessType?,
        number: BusinessRegistrationNumber?,
        name: DisplayName?,
        profile: BusinessProfile,
        target: BusinessRegistrationStatus,
    ): List<BusinessFieldError> {
        require(target == BusinessRegistrationStatus.DRAFT || target == BusinessRegistrationStatus.REGISTERED) { "rules target must be DRAFT or REGISTERED" }
        val errors = mutableListOf<BusinessFieldError>()
        fun add(path: String, code: BusinessFieldErrorCode) {
            errors += BusinessFieldError(path, code)
        }

        // D-BIZ-002 DRAFT 최소(모든 상태 공통).
        if (type == null) add("businessType", BusinessFieldErrorCode.REQUIRED)
        if (number == null) add("registrationNumber", BusinessFieldErrorCode.REQUIRED)
        if (name == null) add("displayName", BusinessFieldErrorCode.REQUIRED)

        val registration = profile.registration
        if (registration.countryCode != BusinessRegistration.KOREA) add("registration.countryCode", BusinessFieldErrorCode.NOT_ALLOWED)
        if (type == BusinessType.INDIVIDUAL && registration.corporateRegistrationNumber != null) {
            add("registration.corporateRegistrationNumber", BusinessFieldErrorCode.NOT_ALLOWED)
        }

        val representatives = profile.representatives
        if (representatives.size > MAX_REPRESENTATIVES) add("representatives", BusinessFieldErrorCode.TOO_MANY)
        // C-22: SOLE이면 정확히 1명(2명 이상이면 SOLE 없음). 같은 이름·유형 두 행은 거절하지 않는다(m-5).
        if (representatives.size > 1 && representatives.any { it.type == RepresentativeType.SOLE }) add("representatives", BusinessFieldErrorCode.SOLE_WITH_OTHERS)

        val categories = profile.categories
        if (categories.groupingBy { it.type }.eachCount().values.any { it > MAX_CATEGORIES_PER_TYPE }) add("categories", BusinessFieldErrorCode.TOO_MANY)
        val seenCategories = HashSet<BusinessCategory>()
        categories.forEachIndexed { i, c -> if (!seenCategories.add(c)) add("categories[$i].name", BusinessFieldErrorCode.DUPLICATE) }

        val addresses = profile.addresses
        if (addresses.size > MAX_ADDRESSES) add("addresses", BusinessFieldErrorCode.TOO_MANY)
        val seenAddressTypes = HashSet<BusinessAddressType>()
        addresses.forEachIndexed { i, a -> if (!seenAddressTypes.add(a.type)) add("addresses[$i].type", BusinessFieldErrorCode.TOO_MANY) }
        val registeredAddress = addresses.firstOrNull { it.type == BusinessAddressType.REGISTERED }
        val actualAddress = addresses.firstOrNull { it.type == BusinessAddressType.ACTUAL }
        if (registeredAddress != null && actualAddress != null && actualAddress.samePlaceAs(registeredAddress)) {
            add("addresses", BusinessFieldErrorCode.ACTUAL_SAME_AS_REGISTERED)
        }

        val contacts = profile.contacts
        if (contacts.size > MAX_CONTACTS) add("contacts", BusinessFieldErrorCode.TOO_MANY)
        val primaries = contacts.count { it.primary }
        if (primaries > 1) add("contacts", BusinessFieldErrorCode.PRIMARY_MULTIPLE)

        if (target == BusinessRegistrationStatus.REGISTERED) {
            // 지침서 §4·정책 §5 REGISTERED 필수. 선택: 사업체 이메일·영문 상호·실사업장 주소·상세주소(C-31).
            if (registration.openingDate == null) add("registration.openingDate", BusinessFieldErrorCode.REQUIRED)
            if (registration.phoneNumber == null) add("registration.phoneNumber", BusinessFieldErrorCode.REQUIRED)
            if (type == BusinessType.CORPORATION && registration.corporateRegistrationNumber == null) {
                add("registration.corporateRegistrationNumber", BusinessFieldErrorCode.REQUIRED)
            }
            if (registeredAddress == null) add("addresses", BusinessFieldErrorCode.REQUIRED)
            if (representatives.isEmpty()) add("representatives", BusinessFieldErrorCode.REQUIRED)
            if (categories.none { it.type == BusinessCategoryType.BUSINESS_TYPE } || categories.none { it.type == BusinessCategoryType.BUSINESS_ITEM }) {
                add("categories", BusinessFieldErrorCode.BUSINESS_TYPE_ITEM_REQUIRED)
            }
            // 지침서 F-006 "기본 등록 담당자 1명": 담당자 ≥ 1 그리고 primary 정확히 1.
            if (contacts.isEmpty()) {
                add("contacts", BusinessFieldErrorCode.REQUIRED)
            } else if (primaries == 0) {
                add("contacts", BusinessFieldErrorCode.PRIMARY_REQUIRED)
            }
        }
        return errors.distinct().sortedWith(compareBy({ it.path }, { it.code.name })).take(MAX_ERRORS)
    }

    /** Aggregate 생성·전이 안 재검사(C-16): 위반이면 [BusinessProfileRuleViolation](결함 — 정상 경로에서는 해석기가 먼저 막는다). */
    fun require(type: BusinessType?, number: BusinessRegistrationNumber?, name: DisplayName?, profile: BusinessProfile, target: BusinessRegistrationStatus) {
        val errors = validate(type, number, name, profile, target)
        if (errors.isNotEmpty()) throw BusinessProfileRuleViolation(errors)
    }
}

/**
 * Aggregate 안 규칙 재검사 위반(C-16 — [MasterRuleViolation]이 아니므로 저장된 거절이 되지 않고, 실행기 TX 전체 rollback·`admin_request` 0행).
 * [errors]는 path·code만(값 없음), 메시지는 개수만.
 */
class BusinessProfileRuleViolation(val errors: List<BusinessFieldError>) :
    IllegalArgumentException("business profile rules violated: ${errors.size} field error(s)")