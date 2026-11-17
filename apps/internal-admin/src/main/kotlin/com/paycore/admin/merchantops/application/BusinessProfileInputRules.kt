package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 사업자 명령 값 규칙(BUSINESS-PROFILE-001 §7.2 — MASTER §4.2 값 객체 `errorOf`·§4.3 `BusinessProfileRules.validate`·`BusinessProfileRequestParser` 값 단계를 **복제**한다. 코드 공유 없음 —
 * 모듈·서비스 경계, C-10 선례: 화면 즉시 안내용이고 권위는 MASTER다. 어긋나면 MASTER 400 `BUSINESS_VALIDATION_FAILED` → 지표 `business_rule_drift`).
 * 판정 두 단계(MASTER E-10 ①과 같다): ① 값 형식 — 위반이 하나라도 있으면 형식 오류만 돌려준다(행을 만들 수 없음) ② Aggregate 규칙(목표 = create는 요청 상태, update는 DRAFT 공통 규칙 —
 * 잠근 행 상태의 REGISTERED 유지 규칙은 MASTER Domain 재검사가 판정(BIZ-2-11), 등록 완료는 REGISTERED). 결과는 path 사전순 → code, 중복 제거, 최대 50(§4.6). 오류에 값이 없다.
 * 정규화·암묵 trim 없음(X-14). 열거 값은 빈 문자열 REQUIRED·모르는 값 FORMAT(E-10 ②).
 */
internal object BusinessProfileInputRules {
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val SUBMISSION_ID = Regex("^[0-9a-f]{32}$")
    val BUSINESS_ID = Regex("^biz_$ULID$")
    val CREATE_STATUSES = setOf(DRAFT, REGISTERED)

    // §4.2 기술 상한(C-19 — MASTER BusinessProfileRules·값 객체와 같다).
    private const val MAX_DISPLAY_NAME = 100
    private const val MAX_PERSON_NAME = 50
    private const val MAX_CATEGORY_NAME = 50
    private const val MAX_ADDRESS_LINE = 200
    private const val MAX_REASON = 200
    private const val MAX_ENGLISH_NAME = 100
    private const val REGISTRATION_NUMBER_LENGTH = 10
    private const val CORPORATE_NUMBER_LENGTH = 13
    private const val POSTAL_CODE_LENGTH = 5
    private const val PHONE_MIN = 8
    private const val PHONE_MAX = 12
    private const val EMAIL_MAX = 254
    private const val EMAIL_LOCAL_MAX = 64
    private const val EMAIL_LABEL_MAX = 63
    const val MAX_REPRESENTATIVES = 5
    const val MAX_CATEGORIES_PER_TYPE = 10
    const val MAX_ADDRESSES = 2
    const val MAX_CONTACTS = 5

    /** 목록 전체 상한(구조 — adapter가 넘으면 구조 위반으로 거절, MASTER `BusinessProfileRequestParser.MAX_CATEGORIES`와 같다). */
    const val MAX_CATEGORIES = 2 * MAX_CATEGORIES_PER_TYPE

    private const val KOREA = "KR"
    private val BUSINESS_TYPES = listOf("INDIVIDUAL", "CORPORATION")
    private val REPRESENTATIVE_TYPES = listOf("SOLE", "JOINT", "EACH")
    private val CATEGORY_TYPES = listOf("BUSINESS_TYPE", "BUSINESS_ITEM")
    private val ADDRESS_TYPES = listOf("REGISTERED", "ACTUAL")
    private val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** 값 규칙 위반 목록(비면 통과). 식별자(submissionId·경로 businessId)는 [identifiersWellFormed]가 따로 본다(필드 오류 없는 400). */
    fun errors(request: BusinessProfileRequest): List<BusinessFieldError> {
        val errors = mutableListOf<BusinessFieldError>()
        fun add(path: String, code: String?) {
            if (code != null) errors += BusinessFieldError(path, code)
        }
        when (request) {
            is BusinessProfileRequest.Create -> add("registrationNumber", digits(request.registrationNumber, REGISTRATION_NUMBER_LENGTH))
            is BusinessProfileRequest.Change -> request.reason?.let { add("reason", text(it, MAX_REASON)) }
        }
        formatErrors(request.content, ::add)
        if (errors.isNotEmpty()) return sorted(errors)
        val target = when (request) {
            is BusinessProfileRequest.Create -> request.registrationStatus
            is BusinessProfileRequest.Change -> if (request.operation == BusinessProfileOperation.COMPLETE_REGISTRATION) REGISTERED else DRAFT
        }
        aggregateErrors(request.content, target, ::add)
        return sorted(errors)
    }

    /** submissionId(32 hex)와 수정·등록 완료의 경로 businessId(`biz_` + ULID). */
    fun identifiersWellFormed(request: BusinessProfileRequest): Boolean =
        SUBMISSION_ID.matches(request.submissionId) && (request !is BusinessProfileRequest.Change || BUSINESS_ID.matches(request.businessId))

    /** 상호 문자 규칙(MASTER `DisplayName.errorOf` — 목록 검색 상호 접두에도 같은 규칙, §6.2). */
    fun displayNameWellFormed(value: String): Boolean = text(value, MAX_DISPLAY_NAME) == null

    private fun sorted(errors: List<BusinessFieldError>): List<BusinessFieldError> =
        errors.distinct().sortedWith(compareBy({ it.path }, { it.code })).take(BusinessFieldError.MAX)

    /** ① 값 형식(MASTER `BusinessProfileRequestParser.Values.content`와 같은 path·순서). */
    private fun formatErrors(c: BusinessProfileContent, add: (String, String?) -> Unit) {
        add("businessType", enum(c.businessType, BUSINESS_TYPES))
        add("displayName", text(c.displayName, MAX_DISPLAY_NAME))
        val r = c.registration
        r.corporateRegistrationNumber?.let { add("registration.corporateRegistrationNumber", digits(it, CORPORATE_NUMBER_LENGTH)) }
        r.openingDate?.let { add("registration.openingDate", date(it)) }
        r.englishName?.let { add("registration.englishName", englishName(it)) }
        r.phoneNumber?.let { add("registration.phoneNumber", phone(it)) }
        r.email?.let { add("registration.email", email(it)) }
        c.representatives.forEachIndexed { i, rep ->
            add("representatives[$i].name", text(rep.name, MAX_PERSON_NAME))
            // E-10 ⑤: 대표자 유형 path는 `representatives[i].type`(본문 key는 representativeType).
            add("representatives[$i].type", enum(rep.representativeType, REPRESENTATIVE_TYPES))
        }
        c.categories.forEachIndexed { i, cat ->
            add("categories[$i].type", enum(cat.categoryType, CATEGORY_TYPES))
            add("categories[$i].name", text(cat.name, MAX_CATEGORY_NAME))
        }
        c.addresses.forEachIndexed { i, a ->
            add("addresses[$i].type", enum(a.addressType, ADDRESS_TYPES))
            add("addresses[$i].postalCode", digits(a.postalCode, POSTAL_CODE_LENGTH))
            add("addresses[$i].baseAddress", text(a.baseAddress, MAX_ADDRESS_LINE))
            a.detailAddress?.let { add("addresses[$i].detailAddress", text(it, MAX_ADDRESS_LINE)) }
        }
        c.contacts.forEachIndexed { i, ct ->
            add("contacts[$i].name", text(ct.name, MAX_PERSON_NAME))
            add("contacts[$i].email", email(ct.email))
            add("contacts[$i].phoneNumber", phone(ct.phoneNumber))
        }
    }

    /** ② Aggregate 규칙(MASTER `BusinessProfileRules.validate` 복제 — 구분·번호·상호는 ①을 통과했으므로 있음). */
    @Suppress("CyclomaticComplexMethod")
    private fun aggregateErrors(c: BusinessProfileContent, target: String, add: (String, String?) -> Unit) {
        val r = c.registration
        if (r.countryCode != KOREA) add("registration.countryCode", "NOT_ALLOWED")
        if (c.businessType == "INDIVIDUAL" && r.corporateRegistrationNumber != null) add("registration.corporateRegistrationNumber", "NOT_ALLOWED")

        val reps = c.representatives
        if (reps.size > MAX_REPRESENTATIVES) add("representatives", "TOO_MANY")
        // C-22: SOLE이면 정확히 1명. 같은 이름·유형 두 행은 거절하지 않는다(m-5).
        if (reps.size > 1 && reps.any { it.representativeType == "SOLE" }) add("representatives", "SOLE_WITH_OTHERS")

        val cats = c.categories
        if (cats.groupingBy { it.categoryType }.eachCount().values.any { it > MAX_CATEGORIES_PER_TYPE }) add("categories", "TOO_MANY")
        val seen = HashSet<Pair<String, String>>()
        cats.forEachIndexed { i, cat -> if (!seen.add(cat.categoryType to cat.name)) add("categories[$i].name", "DUPLICATE") }

        val addresses = c.addresses
        if (addresses.size > MAX_ADDRESSES) add("addresses", "TOO_MANY")
        val seenTypes = HashSet<String>()
        addresses.forEachIndexed { i, a -> if (!seenTypes.add(a.addressType)) add("addresses[$i].type", "TOO_MANY") }
        val registered = addresses.firstOrNull { it.addressType == "REGISTERED" }
        val actual = addresses.firstOrNull { it.addressType == "ACTUAL" }
        if (registered != null && actual != null && registered.postalCode == actual.postalCode && registered.baseAddress == actual.baseAddress &&
            registered.detailAddress == actual.detailAddress
        ) {
            add("addresses", "ACTUAL_SAME_AS_REGISTERED")
        }

        val contacts = c.contacts
        if (contacts.size > MAX_CONTACTS) add("contacts", "TOO_MANY")
        val primaries = contacts.count { it.primary }
        if (primaries > 1) add("contacts", "PRIMARY_MULTIPLE")

        if (target == REGISTERED) {
            if (r.openingDate == null) add("registration.openingDate", "REQUIRED")
            if (r.phoneNumber == null) add("registration.phoneNumber", "REQUIRED")
            if (c.businessType == "CORPORATION" && r.corporateRegistrationNumber == null) add("registration.corporateRegistrationNumber", "REQUIRED")
            if (registered == null) add("addresses", "REQUIRED")
            if (reps.isEmpty()) add("representatives", "REQUIRED")
            if (cats.none { it.categoryType == "BUSINESS_TYPE" } || cats.none { it.categoryType == "BUSINESS_ITEM" }) add("categories", "BUSINESS_TYPE_ITEM_REQUIRED")
            if (contacts.isEmpty()) add("contacts", "REQUIRED") else if (primaries == 0) add("contacts", "PRIMARY_REQUIRED")
        }
    }

    /** MASTER `BusinessTextRule.errorOf`: 빈 값 REQUIRED, 제어문자·짝 없는 서로게이트·앞뒤 공백 FORMAT, code point 상한 초과 TOO_LONG. */
    private fun text(raw: String, maxCodePoints: Int): String? = when {
        raw.isEmpty() -> "REQUIRED"
        !wellFormed(raw) || raw.first() == ' ' || raw.last() == ' ' -> "FORMAT"
        raw.codePointCount(0, raw.length) > maxCodePoints -> "TOO_LONG"
        else -> null
    }

    /** MASTER `BusinessTextRule.digitsErrorOf`. */
    private fun digits(raw: String, length: Int): String? = when {
        raw.isEmpty() -> "REQUIRED"
        raw.length != length || !raw.all { it in '0'..'9' } -> "FORMAT"
        else -> null
    }

    private fun enum(raw: String, values: List<String>): String? = when {
        raw.isEmpty() -> "REQUIRED"
        raw !in values -> "FORMAT"
        else -> null
    }

    private fun date(raw: String): String? = when {
        raw.isEmpty() -> "REQUIRED"
        !DATE_TEXT.matches(raw) || runCatching { LocalDate.parse(raw, DATE) }.isFailure -> "FORMAT"
        else -> null
    }

    /** MASTER `EnglishBusinessName.errorOf`(출력 가능 ASCII, 앞뒤 공백 금지, 1~100자). */
    private fun englishName(raw: String): String? = when {
        raw.isEmpty() -> "REQUIRED"
        !raw.all { it in ' '..'~' } || raw.first() == ' ' || raw.last() == ' ' -> "FORMAT"
        raw.length > MAX_ENGLISH_NAME -> "TOO_LONG"
        else -> null
    }

    /** MASTER `PhoneNumber.errorOf`(ASCII 숫자 8~12, 첫 자리 0·1). */
    private fun phone(raw: String): String? = when {
        raw.isEmpty() -> "REQUIRED"
        !raw.all { it in '0'..'9' } -> "FORMAT"
        raw.length > PHONE_MAX -> "TOO_LONG"
        raw.length < PHONE_MIN || (raw[0] != '0' && raw[0] != '1') -> "FORMAT"
        else -> null
    }

    /** MASTER `EmailAddress.errorOf`. */
    private fun email(raw: String): String? {
        if (raw.isEmpty()) return "REQUIRED"
        if (raw.length > EMAIL_MAX) return "TOO_LONG"
        val parts = raw.split('@')
        if (parts.size != 2) return "FORMAT"
        val (local, domain) = parts
        if (local.length !in 1..EMAIL_LOCAL_MAX || !local.all { it.isAsciiLetterOrDigit() || it in "._%+-" }) return "FORMAT"
        val labels = domain.split('.')
        if (labels.size < 2 || labels.any { it.length !in 1..EMAIL_LABEL_MAX || !it.all { c -> c.isAsciiLetterOrDigit() || c == '-' } }) return "FORMAT"
        if (labels.last().length < 2) return "FORMAT"
        return null
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private fun wellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isISOControl(c)) return false
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    const val DRAFT = "DRAFT"
    const val REGISTERED = "REGISTERED"
}
