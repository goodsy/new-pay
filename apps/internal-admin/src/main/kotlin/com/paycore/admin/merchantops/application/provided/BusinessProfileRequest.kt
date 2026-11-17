package com.paycore.admin.merchantops.application.provided

/**
 * 화면 → ADMIN 사업자 명령 입력(BUSINESS-PROFILE-001 §7.1 — MASTER §6.6 본문과 같은 필드 + `submissionId`, "모든 key 필수·선택 null"). 구조(key 집합·자료형·목록 전체 상한)는 adapter가
 * 엄격 해석했고, 값 규칙(§4.2·§4.3)은 Use Case가 `BusinessProfileInputRules`로 검사한다(즉시 안내용 복제 — 권위는 MASTER, C-10 선례).
 * 모든 toString은 값을 가린다(개인정보·사업자등록번호 — 로그·예외 비노출).
 */
sealed interface BusinessProfileRequest {
    val operation: BusinessProfileOperation
    val submissionId: String
    val content: BusinessProfileContent

    /** 생성 — 목표 상태 [registrationStatus](DRAFT·REGISTERED만 — 그 밖은 adapter가 구조 위반으로 거절). */
    class Create(
        override val submissionId: String,
        val registrationStatus: String,
        val registrationNumber: String,
        override val content: BusinessProfileContent,
    ) : BusinessProfileRequest {
        override val operation: BusinessProfileOperation get() = BusinessProfileOperation.CREATE

        override fun toString(): String = "BusinessProfileRequest.Create(status=$registrationStatus, ***)"
    }

    /** 수정·등록 완료 — 대상 [businessId]는 경로, [version]은 상세의 기대값(JSON 정수 ≥ 0), [reason]은 변경 사유(선택 — 중요정보 변경이면 MASTER가 필수로 판정). */
    class Change(
        override val operation: BusinessProfileOperation,
        val businessId: String,
        override val submissionId: String,
        val version: Long,
        val reason: String?,
        override val content: BusinessProfileContent,
    ) : BusinessProfileRequest {
        init {
            require(operation != BusinessProfileOperation.CREATE) { "a change is an update or a registration completion" }
        }

        override fun toString(): String = "BusinessProfileRequest.Change(operation=$operation, version=$version, ***)"
    }
}

/** 사업자 내용(구분·상호·등록정보·네 목록 — 요청 순서 그대로). */
class BusinessProfileContent(
    val businessType: String,
    val displayName: String,
    val registration: BusinessRegistrationInput,
    val representatives: List<BusinessRepresentativeInput>,
    val categories: List<BusinessCategoryInput>,
    val addresses: List<BusinessAddressInput>,
    val contacts: List<BusinessContactInput>,
) {
    override fun toString(): String = "BusinessProfileContent(***)"
}

class BusinessRegistrationInput(
    val corporateRegistrationNumber: String?,
    val openingDate: String?,
    val countryCode: String,
    val englishName: String?,
    val phoneNumber: String?,
    val email: String?,
) {
    override fun toString(): String = "BusinessRegistrationInput(***)"
}

class BusinessRepresentativeInput(val name: String, val representativeType: String) {
    override fun toString(): String = "BusinessRepresentativeInput(***)"
}

class BusinessCategoryInput(val categoryType: String, val name: String) {
    override fun toString(): String = "BusinessCategoryInput(***)"
}

class BusinessAddressInput(val addressType: String, val postalCode: String, val baseAddress: String, val detailAddress: String?) {
    override fun toString(): String = "BusinessAddressInput(***)"
}

class BusinessContactInput(val name: String, val email: String, val phoneNumber: String, val primary: Boolean) {
    override fun toString(): String = "BusinessContactInput(***)"
}
