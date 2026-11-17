package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.AddressLine
import com.paycore.master.merchant.domain.BusinessAddress
import com.paycore.master.merchant.domain.BusinessAddressType
import com.paycore.master.merchant.domain.BusinessCategory
import com.paycore.master.merchant.domain.BusinessCategoryType
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContact
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessFieldErrorCode
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessProfileRules
import com.paycore.master.merchant.domain.BusinessRegistration
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessRepresentative
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.CategoryName
import com.paycore.master.merchant.domain.CorporateRegistrationNumber
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EmailAddress
import com.paycore.master.merchant.domain.EnglishBusinessName
import com.paycore.master.merchant.domain.PersonName
import com.paycore.master.merchant.domain.PhoneNumber
import com.paycore.master.merchant.domain.PostalCode
import com.paycore.master.merchant.domain.RepresentativeType
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** 사업자 명령 본문 해석 결과(BUSINESS-PROFILE-001 §6.6). */
sealed interface BusinessCommandParse<out T> {
    data class Parsed<T>(val command: T) : BusinessCommandParse<T>

    /** 구조 위반(JSON·key·자료형·null 불가 null·목록 상한·공통 필드·ID 형식) → 400 `REQUEST_MALFORMED`(필드·값 없음). */
    data object Malformed : BusinessCommandParse<Nothing>

    /** 구조는 맞고 값 규칙 위반 → 400 `BUSINESS_VALIDATION_FAILED` + [errors](path·code만, 값 없음, path 사전순·최대 50 — §4.6). */
    data class Invalid(val errors: List<BusinessFieldError>) : BusinessCommandParse<Nothing>
}

/**
 * 사업자 명령 본문 엄격 해석(BUSINESS-PROFILE-001 §6.6 — [MerchantOperationsRequestParser]와 같은 JSON 규칙: 객체 하나, 중복 key·뒤따르는 토큰 거부, 자료형 강제 변환 없음).
 * **모든 key 필수**(선택 값은 `null`), 모르는 key 거부 — 사업자등록번호·등록 상태는 update·complete 본문에 없다(보내면 모르는 key → 400, TBD-04·TC-13).
 *
 * 판정은 두 단계다(C-16 — 둘 다 Use Case 호출 전이라 `admin_request`에 저장되지 않는다):
 * 1. 구조 → [BusinessCommandParse.Malformed]: JSON·key 집합·자료형(문자열·`primary` 불리언·`version` 정수 ≥ 0), null 불가 필드의 null, 목록 전체 상한(C-19 — 대표자 5·업태+종목 20·
 *    주소 2·담당자 5), 공통 필드(멱등키·`admin:` 행위자·상관 ID), `businessId`·`registrationStatus`(DRAFT|REGISTERED).
 * 2. 값 → [BusinessCommandParse.Invalid]: 각 문자열을 Domain 값 객체의 `errorOf`(생성자와 같은 판정)로 검사해 §4.6 path로 모은다. 형식 위반이 없을 때만 profile을 만들어
 *    [BusinessProfileRules.validate]를 돌린다(목표: create = 요청 상태, update = DRAFT 공통 규칙 — 잠근 행 상태는 TX 안에서만 알 수 있어 REGISTERED 유지 규칙은
 *    Domain 재검사가 판정한다(BIZ-2-11), complete = REGISTERED). 형식 위반이 있으면 Aggregate 규칙은 판정하지 않는다(행을 만들 수 없다 — 형식 오류만 돌려준다).
 *
 * 어느 경우든 응답·로그에 입력 값이 없다(오류는 path·code만 — 사업자등록번호 oracle 방어 D-MOPS-003 포함). 정규화·암묵 trim 없음(X-14).
 * 열거 값(구분·대표자·업태/종목·주소 유형)이 빈 문자열이면 REQUIRED, 모르는 값이면 FORMAT.
 */
object BusinessProfileRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val ACTOR_PREFIX = "admin:"
    private val COMMON = setOf("idempotencyKey", "actor", "correlationId")
    private val PROFILE = setOf("businessType", "displayName", "registration", "representatives", "categories", "addresses", "contacts")
    private val CREATE = COMMON + setOf("registrationStatus", "registrationNumber") + PROFILE
    private val UPDATE = COMMON + setOf("businessId", "version", "reason") + PROFILE
    private val REGISTRATION = setOf("corporateRegistrationNumber", "openingDate", "countryCode", "englishName", "phoneNumber", "email")
    private val REPRESENTATIVE = setOf("name", "representativeType")
    private val CATEGORY = setOf("categoryType", "name")
    private val ADDRESS = setOf("addressType", "postalCode", "baseAddress", "detailAddress")
    private val CONTACT = setOf("name", "email", "phoneNumber", "primary")

    /** 목록 전체 상한(C-19 — 기술 상한, 16 KiB 안). 업태·종목은 유형별 10이라 전체 20(유형별 초과는 규칙 `categories` TOO_MANY). */
    const val MAX_CATEGORIES = 2 * BusinessProfileRules.MAX_CATEGORIES_PER_TYPE

    private val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
    private val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    fun create(body: ByteArray): BusinessCommandParse<CreateBusinessCommand> = parse(body, CREATE) { root, v ->
        val target = when (text(root, "registrationStatus")) {
            BusinessRegistrationStatus.DRAFT.name -> BusinessRegistrationStatus.DRAFT
            BusinessRegistrationStatus.REGISTERED.name -> BusinessRegistrationStatus.REGISTERED
            else -> throw Malformed()
        }
        val request = request(root)
        val number = v.value("registrationNumber", text(root, "registrationNumber"), BusinessRegistrationNumber::errorOf, ::BusinessRegistrationNumber)
        val content = v.content(root)
        v.finish(target, content.type, number, content.name, content.profile) { type, n, name, profile ->
            CreateBusinessCommand(request, target, type, requireNotNull(n), name, profile)
        }
    }

    fun update(body: ByteArray): BusinessCommandParse<UpdateBusinessCommand> = parse(body, UPDATE) { root, v ->
        val (request, id, version) = target(root)
        val reason = v.optValue("reason", optText(root, "reason"), BusinessChangeReason::errorOf, ::BusinessChangeReason)
        val content = v.content(root)
        v.finish(BusinessRegistrationStatus.DRAFT, content.type, null, content.name, content.profile) { type, _, name, profile ->
            UpdateBusinessCommand(request, id, version, reason, type, name, profile)
        }
    }

    fun completeRegistration(body: ByteArray): BusinessCommandParse<CompleteBusinessRegistrationCommand> = parse(body, UPDATE) { root, v ->
        val (request, id, version) = target(root)
        val reason = v.optValue("reason", optText(root, "reason"), BusinessChangeReason::errorOf, ::BusinessChangeReason)
        val content = v.content(root)
        v.finish(BusinessRegistrationStatus.REGISTERED, content.type, null, content.name, content.profile) { type, _, name, profile ->
            CompleteBusinessRegistrationCommand(request, id, version, reason, type, name, profile)
        }
    }

    /** 구조 위반 신호(제어 흐름 전용 — 스택 추적 없음). */
    private class Malformed : RuntimeException(null, null, false, false)

    private data class Target(val request: AdminRequest, val id: BusinessId, val version: Long)

    private data class Content(val type: BusinessType?, val name: DisplayName?, val profile: BusinessProfile?)

    private fun target(root: JsonNode): Target {
        val request = request(root)
        val id = text(root, "businessId").also { if (!BUSINESS_ID.matches(it)) throw Malformed() }
        val versionNode = root.get("version")
        if (!versionNode.isIntegralNumber || !versionNode.canConvertToLong() || versionNode.asLong() < 0) throw Malformed()
        return Target(request, BusinessId(id), versionNode.asLong())
    }

    private fun request(root: JsonNode): AdminRequest {
        val actor = text(root, "actor")
        if (!actor.startsWith(ACTOR_PREFIX) || actor.length <= ACTOR_PREFIX.length) throw Malformed()
        return runCatching { AdminRequest(AdminIdempotencyKey(text(root, "idempotencyKey")), ActorRef(actor), text(root, "correlationId")) }
            .getOrElse { throw Malformed() }
    }

    private fun <T> parse(body: ByteArray, names: Set<String>, build: (JsonNode, Values) -> BusinessCommandParse<T>): BusinessCommandParse<T> = try {
        val root: JsonNode? = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != names) {
            BusinessCommandParse.Malformed
        } else {
            build(root, Values())
        }
    } catch (_: Malformed) {
        BusinessCommandParse.Malformed
    } catch (_: RuntimeException) {
        // JSON 문법·중복 key·뒤따르는 토큰(Jackson 예외) — 메시지(입력 조각 포함 가능)는 버린다.
        BusinessCommandParse.Malformed
    }

    /** 객체이고 key 집합이 정확히 [names]여야 한다. */
    private fun obj(node: JsonNode?, names: Set<String>): JsonNode {
        if (node == null || !node.isObject || node.propertyNames().toSet() != names) throw Malformed()
        return node
    }

    /** 배열이고 길이 ≤ [max]여야 한다(목록 상한 — C-19). */
    private fun array(node: JsonNode?, max: Int): List<JsonNode> {
        if (node == null || !node.isArray || node.size() > max) throw Malformed()
        return (0 until node.size()).map { node.get(it) }
    }

    /** JSON 문자열이어야 한다(null·다른 자료형 → 구조 위반). */
    private fun text(node: JsonNode, name: String): String {
        val value = node.get(name)
        if (value == null || !value.isString) throw Malformed()
        return value.asString()
    }

    /** 선택 값: `null` 또는 JSON 문자열(key 자체는 있어야 한다 — key 집합 검사가 먼저). */
    private fun optText(node: JsonNode, name: String): String? {
        val value = node.get(name) ?: throw Malformed()
        if (value.isNull) return null
        if (!value.isString) throw Malformed()
        return value.asString()
    }

    /** 값 규칙 판정 누적기(§4.6 path — 값 없음). */
    private class Values {
        private val errors = mutableListOf<BusinessFieldError>()

        fun <T> value(path: String, raw: String, errorOf: (String) -> BusinessFieldErrorCode?, build: (String) -> T): T? {
            val code = errorOf(raw)
            if (code != null) {
                errors += BusinessFieldError(path, code)
                return null
            }
            return build(raw)
        }

        fun <T> optValue(path: String, raw: String?, errorOf: (String) -> BusinessFieldErrorCode?, build: (String) -> T): T? =
            raw?.let { value(path, it, errorOf, build) }

        fun <E : Enum<E>> enum(path: String, raw: String, entries: List<E>): E? = value(path, raw, { r ->
            when {
                r.isEmpty() -> BusinessFieldErrorCode.REQUIRED
                entries.none { it.name == r } -> BusinessFieldErrorCode.FORMAT
                else -> null
            }
        }) { r -> entries.single { it.name == r } }

        fun date(path: String, raw: String?): LocalDate? = raw?.let {
            value(path, it, { r ->
                when {
                    r.isEmpty() -> BusinessFieldErrorCode.REQUIRED
                    !DATE_TEXT.matches(r) || runCatching { LocalDate.parse(r, DATE) }.isFailure -> BusinessFieldErrorCode.FORMAT
                    else -> null
                }
            }) { r -> LocalDate.parse(r, DATE) }
        }

        /** 구분·상호·등록정보·네 목록(create·update·complete 공통 — 요청 순서 그대로, 지문은 이 순서). */
        fun content(root: JsonNode): Content {
            val type = enum("businessType", text(root, "businessType"), BusinessType.entries)
            val name = value("displayName", text(root, "displayName"), DisplayName::errorOf, ::DisplayName)

            val reg = obj(root.get("registration"), REGISTRATION)
            val corporate = optValue(
                "registration.corporateRegistrationNumber", optText(reg, "corporateRegistrationNumber"), CorporateRegistrationNumber::errorOf, ::CorporateRegistrationNumber,
            )
            val openingDate = date("registration.openingDate", optText(reg, "openingDate"))
            val country = text(reg, "countryCode")
            val english = optValue("registration.englishName", optText(reg, "englishName"), EnglishBusinessName::errorOf, ::EnglishBusinessName)
            val phone = optValue("registration.phoneNumber", optText(reg, "phoneNumber"), PhoneNumber::errorOf, ::PhoneNumber)
            val email = optValue("registration.email", optText(reg, "email"), EmailAddress::errorOf, ::EmailAddress)

            val representatives = array(root.get("representatives"), BusinessProfileRules.MAX_REPRESENTATIVES).mapIndexed { i, node ->
                val row = obj(node, REPRESENTATIVE)
                val n = value("representatives[$i].name", text(row, "name"), PersonName::errorOf, ::PersonName)
                val t = enum("representatives[$i].type", text(row, "representativeType"), RepresentativeType.entries)
                if (n != null && t != null) BusinessRepresentative(n, t) else null
            }
            val categories = array(root.get("categories"), MAX_CATEGORIES).mapIndexed { i, node ->
                val row = obj(node, CATEGORY)
                val t = enum("categories[$i].type", text(row, "categoryType"), BusinessCategoryType.entries)
                val n = value("categories[$i].name", text(row, "name"), CategoryName::errorOf, ::CategoryName)
                if (t != null && n != null) BusinessCategory(t, n) else null
            }
            val addresses = array(root.get("addresses"), BusinessProfileRules.MAX_ADDRESSES).mapIndexed { i, node ->
                val row = obj(node, ADDRESS)
                val t = enum("addresses[$i].type", text(row, "addressType"), BusinessAddressType.entries)
                val postal = value("addresses[$i].postalCode", text(row, "postalCode"), PostalCode::errorOf, ::PostalCode)
                val base = value("addresses[$i].baseAddress", text(row, "baseAddress"), AddressLine::errorOf, ::AddressLine)
                val detailRaw = optText(row, "detailAddress")
                val detail = optValue("addresses[$i].detailAddress", detailRaw, AddressLine::errorOf, ::AddressLine)
                if (t != null && postal != null && base != null && (detailRaw == null || detail != null)) BusinessAddress(t, postal, base, detail) else null
            }
            val contacts = array(root.get("contacts"), BusinessProfileRules.MAX_CONTACTS).mapIndexed { i, node ->
                val row = obj(node, CONTACT)
                val n = value("contacts[$i].name", text(row, "name"), PersonName::errorOf, ::PersonName)
                val e = value("contacts[$i].email", text(row, "email"), EmailAddress::errorOf, ::EmailAddress)
                val p = value("contacts[$i].phoneNumber", text(row, "phoneNumber"), PhoneNumber::errorOf, ::PhoneNumber)
                val primary = row.get("primary").also { if (!it.isBoolean) throw Malformed() }.asBoolean()
                if (n != null && e != null && p != null) BusinessContact(n, e, p, primary) else null
            }
            val profile = if (errors.isEmpty()) {
                BusinessProfile(
                    BusinessRegistration(corporate, openingDate, country, english, phone, email),
                    representatives.map { requireNotNull(it) }, categories.map { requireNotNull(it) },
                    addresses.map { requireNotNull(it) }, contacts.map { requireNotNull(it) },
                )
            } else {
                null
            }
            return Content(type, name, profile)
        }

        /** 형식 위반이 없으면 Aggregate 규칙(§4.3)을 [target]으로 판정하고, 모두 통과하면 [build]. */
        fun <T> finish(
            target: BusinessRegistrationStatus,
            type: BusinessType?,
            number: BusinessRegistrationNumber?,
            name: DisplayName?,
            profile: BusinessProfile?,
            build: (BusinessType, BusinessRegistrationNumber?, DisplayName, BusinessProfile) -> T,
        ): BusinessCommandParse<T> {
            if (errors.isNotEmpty() || type == null || name == null || profile == null) return invalid()
            // update·complete에는 번호가 없다(변경 불가 — TBD-04). 규칙의 번호 필수 판정은 생성에서만 의미가 있으므로 그때만 넘긴다.
            val ruleNumber = number ?: PLACEHOLDER_NUMBER
            errors += BusinessProfileRules.validate(type, ruleNumber, name, profile, target)
            if (errors.isNotEmpty()) return invalid()
            return BusinessCommandParse.Parsed(build(type, number, name, profile))
        }

        private fun invalid(): BusinessCommandParse.Invalid =
            BusinessCommandParse.Invalid(errors.distinct().sortedWith(compareBy({ it.path }, { it.code.name })).take(BusinessProfileRules.MAX_ERRORS))
    }

    /**
     * 규칙 함수의 번호 인자 자리(update·complete — 번호는 저장된 값이며 본문에 없다). 값은 판정에 쓰이지 않는다(null 여부만). 저장·비교·응답에 쓰지 않는다.
     * 실제 번호가 아님이 드러나는 값(세무서 코드 `000`은 발급되지 않는다)이며 Demo seed 번호(`0000000000`)와도 다르게 둔다(리뷰 NIT-2 — 혼동 방지).
     */
    private val PLACEHOLDER_NUMBER = BusinessRegistrationNumber("0009999999")
}
