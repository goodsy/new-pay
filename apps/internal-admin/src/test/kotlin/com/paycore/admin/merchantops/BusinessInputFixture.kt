package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.application.provided.BusinessAddressInput
import com.paycore.admin.merchantops.application.provided.BusinessCategoryInput
import com.paycore.admin.merchantops.application.provided.BusinessContactInput
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.BusinessRegistrationInput
import com.paycore.admin.merchantops.application.provided.BusinessRepresentativeInput
import java.security.SecureRandom

/**
 * 사업자 명령 입력 Named Fixture(BUSINESS-PROFILE-001 §14 Test Fixture Policy — 업무 조건이 이름에 드러남, 정상 Fixture는 §4.2·§4.3 규칙 만족). 독립 random은 사업자등록번호뿐
 * (저장소 관례 `SecureRandom` — 공유 DB·공유 대역에서 고정 번호 충돌을 피한다). 값은 Demo 시험 값(운영값 아님). 캔어리 값은 §9 BIZ-3-6 대조 값(MASTER `BusinessFixtures.CANARY_*`와 같다).
 */
object BusinessInputFixture {
    const val CANARY_NUMBER = "7319024865"
    const val CANARY_REPRESENTATIVE = "캔어리대표"
    const val CANARY_EMAIL = "canary.person@example.test"
    const val CANARY_PHONE = "01073190248"
    const val CANARY_DETAIL_ADDRESS = "캔어리동 101호"
    const val CANARY_REASON = "캔어리사유"
    const val CANARY_NAME = "캔어리상호"

    private val random = SecureRandom()

    /** 다른 시험과 겹치지 않는 10자리 번호(앞자리 9 — Demo 번호 `0000000000`·캔어리와 다름). */
    fun randomNumber(): String = "9" + (1..9).joinToString("") { random.nextInt(10).toString() }

    /** REGISTERED 필수를 모두 채운 개인사업자(대표 SOLE 1·업태/종목·사업장·기본 담당자). */
    fun individualRegistered(name: String = "시험 사업자", representative: String = "홍길동", contactEmail: String = "op@paycore.test", contactPhone: String = "01012345678",
        detailAddress: String? = "101호"): BusinessProfileContent = BusinessProfileContent(
        businessType = "INDIVIDUAL",
        displayName = name,
        registration = BusinessRegistrationInput(null, "2026-01-01", "KR", null, "0212345678", null),
        representatives = listOf(BusinessRepresentativeInput(representative, "SOLE")),
        categories = listOf(BusinessCategoryInput("BUSINESS_TYPE", "도소매"), BusinessCategoryInput("BUSINESS_ITEM", "전자상거래")),
        addresses = listOf(BusinessAddressInput("REGISTERED", "06236", "서울특별시 강남구 테헤란로 1", detailAddress)),
        contacts = listOf(BusinessContactInput("담당자", contactEmail, contactPhone, true)),
    )

    /** D-BIZ-002 DRAFT 최소(구분·번호·상호만 — 나머지 비움). */
    fun draftMinimal(name: String = "시험 임시 사업자"): BusinessProfileContent = BusinessProfileContent(
        businessType = "INDIVIDUAL",
        displayName = name,
        registration = BusinessRegistrationInput(null, null, "KR", null, null, null),
        representatives = emptyList(), categories = emptyList(), addresses = emptyList(), contacts = emptyList(),
    )

    /** 캔어리 값(대표자·담당자 이메일·전화·상세주소·상호)이 든 REGISTERED 개인사업자(BIZ-3-6). */
    fun canaryRegistered(): BusinessProfileContent = individualRegistered(
        name = CANARY_NAME, representative = CANARY_REPRESENTATIVE, contactEmail = CANARY_EMAIL, contactPhone = CANARY_PHONE, detailAddress = CANARY_DETAIL_ADDRESS,
    )

    fun create(submissionId: String, status: String = "REGISTERED", number: String = randomNumber(), content: BusinessProfileContent = individualRegistered()) =
        BusinessProfileRequest.Create(submissionId, status, number, content)

    fun update(submissionId: String, businessId: String, version: Long = 0, reason: String? = null, content: BusinessProfileContent = individualRegistered()) =
        BusinessProfileRequest.Change(BusinessProfileOperation.UPDATE, businessId, submissionId, version, reason, content)

    fun complete(submissionId: String, businessId: String, version: Long = 0, reason: String? = null, content: BusinessProfileContent = individualRegistered()) =
        BusinessProfileRequest.Change(BusinessProfileOperation.COMPLETE_REGISTRATION, businessId, submissionId, version, reason, content)

    /** 화면 → ADMIN JSON 본문(모든 key, 선택 null). [extra]는 최상위에 그대로 덧붙인다(구조 위반 시험용). */
    fun json(content: BusinessProfileContent, head: Map<String, Any?>, extra: String = ""): String {
        fun q(v: String?): String = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun value(v: Any?): String = when (v) {
            null -> "null"
            is String -> q(v)
            else -> v.toString()
        }
        val r = content.registration
        return buildString {
            append('{')
            head.forEach { (k, v) -> append(q(k)).append(':').append(value(v)).append(',') }
            append("\"businessType\":").append(q(content.businessType)).append(",\"displayName\":").append(q(content.displayName))
            append(",\"registration\":{\"corporateRegistrationNumber\":").append(q(r.corporateRegistrationNumber)).append(",\"openingDate\":").append(q(r.openingDate))
            append(",\"countryCode\":").append(q(r.countryCode)).append(",\"englishName\":").append(q(r.englishName)).append(",\"phoneNumber\":").append(q(r.phoneNumber))
            append(",\"email\":").append(q(r.email)).append('}')
            append(",\"representatives\":[").append(content.representatives.joinToString(",") { "{\"name\":${q(it.name)},\"representativeType\":${q(it.representativeType)}}" }).append(']')
            append(",\"categories\":[").append(content.categories.joinToString(",") { "{\"categoryType\":${q(it.categoryType)},\"name\":${q(it.name)}}" }).append(']')
            append(",\"addresses\":[").append(
                content.addresses.joinToString(",") {
                    "{\"addressType\":${q(it.addressType)},\"postalCode\":${q(it.postalCode)},\"baseAddress\":${q(it.baseAddress)},\"detailAddress\":${q(it.detailAddress)}}"
                },
            ).append(']')
            append(",\"contacts\":[").append(
                content.contacts.joinToString(",") { "{\"name\":${q(it.name)},\"email\":${q(it.email)},\"phoneNumber\":${q(it.phoneNumber)},\"primary\":${it.primary}}" },
            ).append(']')
            append(extra)
            append('}')
        }
    }

    fun createJson(submissionId: String, number: String, status: String = "REGISTERED", content: BusinessProfileContent = individualRegistered(), extra: String = "") =
        json(content, linkedMapOf("submissionId" to submissionId, "registrationStatus" to status, "registrationNumber" to number), extra)

    fun changeJson(submissionId: String, version: Long = 0, reason: String? = null, content: BusinessProfileContent = individualRegistered(), extra: String = "") =
        json(content, linkedMapOf("submissionId" to submissionId, "version" to version, "reason" to reason), extra)
}
