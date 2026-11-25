package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.domain.BusinessFixtures
import tools.jackson.databind.json.JsonMapper

/**
 * BUSINESS-PROFILE-001 BIZ-2 명령·조회 본문 Named Fixture(src/test 전용 — Test Fixture Policy). ADMIN이 보낼 모양 그대로(§6.6 — **모든 key 필수, 선택 값은 `null`**).
 * 정상 내용 Fixture는 §4.3 불변식을 만족한다(개인 REGISTERED·법인 REGISTERED·DRAFT 최소). 무효 Fixture는 이름·인자로 의도를 드러낸다. 값은 시험 값이다(실제 사업자·개인 아님).
 * 공통 필드는 [Submission](키 `mo1.<계정>.<submissionId>`, actor `admin:<계정>`, correlationId `mos.<submissionId>`).
 */
object BusinessBodies {
    const val CREATE = "/internal/v1/admin/businesses/commands/create"
    const val UPDATE = "/internal/v1/admin/businesses/commands/update"
    const val COMPLETE = "/internal/v1/admin/businesses/commands/complete-registration"
    const val SEARCHES = "/internal/v1/admin/businesses/searches"
    const val LOOKUPS = "/internal/v1/admin/businesses/registration-number-lookups"

    fun detail(businessId: String) = "/internal/v1/admin/businesses/$businessId"
    fun history(businessId: String, after: Long = 0) = "/internal/v1/admin/businesses/$businessId/history/after/$after"
    fun contractIntakes(businessId: String) = "/internal/v1/admin/businesses/$businessId/contract-intakes"

    val mapper: JsonMapper = JsonMapper.builder().build()

    fun json(value: Any?): String = mapper.writeValueAsString(value)

    fun registration(
        corporate: String? = null,
        openingDate: String? = "2026-01-01",
        countryCode: String = "KR",
        englishName: String? = null,
        phone: String? = "0212345678",
        email: String? = "office@example.test",
    ): Map<String, Any?> = linkedMapOf(
        "corporateRegistrationNumber" to corporate, "openingDate" to openingDate, "countryCode" to countryCode, "englishName" to englishName,
        "phoneNumber" to phone, "email" to email,
    )

    fun representative(name: String = "홍길동", type: String = "SOLE"): Map<String, Any?> = linkedMapOf("name" to name, "representativeType" to type)
    fun category(type: String, name: String): Map<String, Any?> = linkedMapOf("categoryType" to type, "name" to name)
    fun address(type: String = "REGISTERED", postal: String = "06236", base: String = "서울특별시 강남구 테헤란로 1", detail: String? = "101호"): Map<String, Any?> =
        linkedMapOf("addressType" to type, "postalCode" to postal, "baseAddress" to base, "detailAddress" to detail)
    fun contact(name: String = "김담당", email: String = "contact@example.test", phone: String = "01012345678", primary: Any? = true): Map<String, Any?> =
        linkedMapOf("name" to name, "email" to email, "phoneNumber" to phone, "primary" to primary)

    /** 구분·상호·등록정보·네 목록(create·update·complete 공통 내용 — §6.6). */
    fun content(
        type: String = "INDIVIDUAL",
        name: String = "개인 상호",
        registration: Map<String, Any?> = registration(),
        representatives: List<Any?> = listOf(representative()),
        categories: List<Any?> = listOf(category("BUSINESS_TYPE", "서비스업"), category("BUSINESS_ITEM", "전자상거래")),
        addresses: List<Any?> = listOf(address()),
        contacts: List<Any?> = listOf(contact()),
    ): Map<String, Any?> = linkedMapOf(
        "businessType" to type, "displayName" to name, "registration" to registration, "representatives" to representatives, "categories" to categories,
        "addresses" to addresses, "contacts" to contacts,
    )

    /** 개인 사업자 REGISTERED 필수값을 모두 채운 내용(TC-01). */
    fun individual(name: String = "개인 상호"): Map<String, Any?> = content(name = name)

    /** 법인 REGISTERED(법인등록번호·공동대표 2명·업태/종목 복수·주소 2·담당자 2 — TC-02·TC-08·TC-10). */
    fun corporation(name: String = "법인 상호"): Map<String, Any?> = content(
        type = "CORPORATION", name = name,
        registration = registration(corporate = "1101110000001", englishName = "Demo Corp"),
        representatives = listOf(representative("이공동", "JOINT"), representative("박공동", "JOINT")),
        categories = listOf(category("BUSINESS_TYPE", "도소매"), category("BUSINESS_TYPE", "서비스"), category("BUSINESS_ITEM", "소프트웨어"), category("BUSINESS_ITEM", "전자상거래")),
        addresses = listOf(address(), address("ACTUAL", "04524", "서울특별시 중구 세종대로 2", "2층")),
        contacts = listOf(contact(), contact("최보조", "sub@example.test", "0298765432", primary = false)),
    )

    /** D-BIZ-002 DRAFT 최소(구분 + 번호 + 상호, 하위 정보 없음 — TC-04). */
    fun draftMinimal(name: String = "임시 상호", type: String = "INDIVIDUAL"): Map<String, Any?> = content(
        type = type, name = name, registration = registration(openingDate = null, phone = null, email = null),
        representatives = emptyList(), categories = emptyList(), addresses = emptyList(), contacts = emptyList(),
    )

    /** 캔어리 값이 들어간 개인 REGISTERED 내용(§9 비노출 대조 — BusinessFixtures.CANARY_*). */
    fun canary(name: String = "캔어리 상호"): Map<String, Any?> = content(
        name = name,
        registration = registration(phone = BusinessFixtures.CANARY_PHONE, email = BusinessFixtures.CANARY_EMAIL),
        representatives = listOf(representative(BusinessFixtures.CANARY_REPRESENTATIVE)),
        addresses = listOf(address(detail = BusinessFixtures.CANARY_DETAIL_ADDRESS)),
        contacts = listOf(contact(BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE)),
    )

    private fun common(s: Submission): Map<String, Any?> = linkedMapOf("idempotencyKey" to s.idempotencyKey, "actor" to s.actor, "correlationId" to s.correlationId)

    fun createMap(s: Submission = Submission(), status: String = "REGISTERED", number: String, content: Map<String, Any?> = individual()): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(common(s))
            put("registrationStatus", status)
            put("registrationNumber", number)
            putAll(content)
        }

    fun create(s: Submission = Submission(), status: String = "REGISTERED", number: String, content: Map<String, Any?> = individual()): String =
        json(createMap(s, status, number, content))

    fun updateMap(s: Submission = Submission(), businessId: String, version: Any?, reason: String? = null, content: Map<String, Any?> = individual()): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(common(s))
            put("businessId", businessId)
            put("version", version)
            put("reason", reason)
            putAll(content)
        }

    fun update(s: Submission = Submission(), businessId: String, version: Any?, reason: String? = null, content: Map<String, Any?> = individual()): String =
        json(updateMap(s, businessId, version, reason, content))

    /** complete-registration 본문은 update와 같은 필드다(§6.6). */
    fun complete(s: Submission = Submission(), businessId: String, version: Any?, reason: String? = null, content: Map<String, Any?> = individual()): String =
        update(s, businessId, version, reason, content)

    /** 한 key를 바꾸거나(값 지정) 지운(REMOVE) 사본 — 구조 위반 Fixture용. */
    val REMOVE = Any()

    fun with(base: Map<String, Any?>, key: String, value: Any?): Map<String, Any?> =
        LinkedHashMap(base).also { if (value === REMOVE) it.remove(key) else it[key] = value }
}
