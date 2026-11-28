package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.BusinessAddressInput
import com.paycore.admin.merchantops.application.provided.BusinessCategoryInput
import com.paycore.admin.merchantops.application.provided.BusinessContactInput
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.BusinessRegistrationInput
import com.paycore.admin.merchantops.application.provided.BusinessRepresentativeInput
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 사업자 명령 본문 엄격 해석(BUSINESS-PROFILE-001 §7.1 — MASTER `BusinessProfileRequestParser` 1단계(구조)와 같은 규칙): JSON 객체 하나, **모든 key 필수**(선택 값은 `null`),
 * 모르는 key·중복 key·뒤따르는 토큰·자료형 강제 변환 거부, 목록 전체 상한(대표자 5·업태+종목 20·주소 2·담당자 5 — C-19), create `registrationStatus` ∈ DRAFT·REGISTERED,
 * update·등록 완료 `version` = JSON 정수 ≥ 0. 사업자등록번호·상태는 update·등록 완료 본문에 없다(보내면 모르는 key — TBD-04). 대상 businessId는 경로 값이다.
 * 값 규칙(형식·Aggregate — §4.2·§4.3)은 Use Case(`BusinessProfileInputRules`)가 본다(역할이 형식보다 먼저 — 감사 순서). 실패하면 null(본문 원문·값은 넘기지 않는다).
 */
object BusinessProfileBodyParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val PROFILE = setOf("businessType", "displayName", "registration", "representatives", "categories", "addresses", "contacts")
    private val CREATE = setOf("submissionId", "registrationStatus", "registrationNumber") + PROFILE
    private val CHANGE = setOf("submissionId", "version", "reason") + PROFILE
    private const val EVIDENCE = "evidenceRef"
    private val REGISTRATION = setOf("corporateRegistrationNumber", "openingDate", "countryCode", "englishName", "phoneNumber", "email")
    private val REPRESENTATIVE = setOf("name", "representativeType")
    private val CATEGORY = setOf("categoryType", "name")
    private val ADDRESS = setOf("addressType", "postalCode", "baseAddress", "detailAddress")
    private val CONTACT = setOf("name", "email", "phoneNumber", "primary")
    private val CREATE_STATUSES = setOf("DRAFT", "REGISTERED")

    // 목록 전체 상한(구조 — MASTER와 같다, C-19). 업태·종목 유형별 10 초과는 값 규칙(categories TOO_MANY — MINOR-2).
    private const val MAX_REPRESENTATIVES = 5
    private const val MAX_CATEGORIES = 20
    private const val MAX_ADDRESSES = 2
    private const val MAX_CONTACTS = 5

    fun parse(operation: BusinessProfileOperation, businessId: String?, body: ByteArray): BusinessProfileRequest? = try {
        val root: JsonNode? = mapper.readTree(body)
        if (root == null || !root.isObject) {
            null
        } else if (operation == BusinessProfileOperation.CREATE) {
            exact(root, CREATE)
            val status = text(root, "registrationStatus").also { if (it !in CREATE_STATUSES) throw Malformed() }
            BusinessProfileRequest.Create(text(root, "submissionId"), status, text(root, "registrationNumber"), content(root))
        } else {
            // BIZ-CRIT-APPROVAL-001 BR-BIZ-391: 선택 key `evidenceRef`(없거나 null = 근거 없음 — 기존 본문 그대로). 중요정보 변경 승인 요청도 같은 모양(근거 필수는 Use Case 값 규칙).
            exactWithOptional(root, CHANGE, EVIDENCE)
            val version = root.get("version")
            if (!version.isIntegralNumber || !version.canConvertToLong() || version.asLong() < 0) throw Malformed()
            BusinessProfileRequest.Change(
                operation, requireNotNull(businessId), text(root, "submissionId"), version.asLong(), optText(root, "reason"), content(root),
                if (root.has(EVIDENCE)) optText(root, EVIDENCE) else null,
            )
        }
    } catch (_: Malformed) {
        null
    } catch (_: RuntimeException) {
        // JSON 문법·중복 key·뒤따르는 토큰(Jackson 예외) — 메시지(입력 조각 포함 가능)는 버린다.
        null
    }

    private fun content(root: JsonNode): BusinessProfileContent {
        val reg = exact(root.get("registration"), REGISTRATION)
        return BusinessProfileContent(
            businessType = text(root, "businessType"),
            displayName = text(root, "displayName"),
            registration = BusinessRegistrationInput(
                optText(reg, "corporateRegistrationNumber"), optText(reg, "openingDate"), text(reg, "countryCode"), optText(reg, "englishName"),
                optText(reg, "phoneNumber"), optText(reg, "email"),
            ),
            representatives = array(root.get("representatives"), MAX_REPRESENTATIVES).map { exact(it, REPRESENTATIVE).let { r -> BusinessRepresentativeInput(text(r, "name"), text(r, "representativeType")) } },
            categories = array(root.get("categories"), MAX_CATEGORIES).map { exact(it, CATEGORY).let { c -> BusinessCategoryInput(text(c, "categoryType"), text(c, "name")) } },
            addresses = array(root.get("addresses"), MAX_ADDRESSES).map {
                exact(it, ADDRESS).let { a -> BusinessAddressInput(text(a, "addressType"), text(a, "postalCode"), text(a, "baseAddress"), optText(a, "detailAddress")) }
            },
            contacts = array(root.get("contacts"), MAX_CONTACTS).map {
                exact(it, CONTACT).let { c ->
                    val primary = c.get("primary").also { p -> if (!p.isBoolean) throw Malformed() }.asBoolean()
                    BusinessContactInput(text(c, "name"), text(c, "email"), text(c, "phoneNumber"), primary)
                }
            },
        )
    }

    /** 구조 위반 신호(제어 흐름 전용 — 스택 추적 없음). */
    private class Malformed : RuntimeException(null, null, false, false)

    /** key 집합이 [names] 전부 + ([optional]이 있으면) 그것까지만. */
    private fun exactWithOptional(node: JsonNode, names: Set<String>, optional: String) {
        val present = node.propertyNames().toSet()
        if (present != names && present != names + optional) throw Malformed()
    }

    private fun exact(node: JsonNode?, names: Set<String>): JsonNode {
        if (node == null || !node.isObject || node.propertyNames().toSet() != names) throw Malformed()
        return node
    }

    private fun array(node: JsonNode?, max: Int): List<JsonNode> {
        if (node == null || !node.isArray || node.size() > max) throw Malformed()
        return (0 until node.size()).map { node.get(it) }
    }

    private fun text(node: JsonNode, name: String): String {
        val value = node.get(name)
        if (value == null || !value.isString) throw Malformed()
        return value.asString()
    }

    private fun optText(node: JsonNode, name: String): String? {
        val value = node.get(name) ?: throw Malformed()
        if (value.isNull) return null
        if (!value.isString) throw Malformed()
        return value.asString()
    }
}
