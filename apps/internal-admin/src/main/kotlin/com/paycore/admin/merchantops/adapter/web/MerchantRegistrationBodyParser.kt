package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.RegistrationKind
import com.paycore.admin.merchantops.application.provided.RegistrationRequest
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 등록 본문 엄격 해석(MERCHANT-OPS-002 §4.3·§4.4 0 — MASTER `MerchantOperationsRequestParser`와 같은 규칙의 자료형 부분): JSON 객체 하나, **정해진 필드 전부**(선택 필드는 B1-2 계약의 `effectiveToDate` 하나 — 없음 또는 `null`, 배열은 계약 `scopeSiteIds` 하나 — 문자열만),
 * 모르는 필드·중복 key·뒤따르는 토큰·자료형 강제 변환·`null` 거부. 값의 **형식**(10자리·ID 모양·host 규칙)은 Use Case가 검사한다(감사 순서 유지 — 역할이 형식보다 먼저).
 * 해석 실패면 [Parsed.Malformed]이고 사업자 등록의 **등록번호 시도 여부**만 넘긴다(본문 원문·값 없음 — Q-MOPS-07 규칙: 첫 최상위 객체의 직속 key `registrationNumber`).
 */
object MerchantRegistrationBodyParser {

    sealed interface Parsed {
        data class Request(val request: RegistrationRequest) : Parsed

        data class Malformed(val registrationNumberAttempt: Boolean) : Parsed
    }

    private val FIELDS: Map<RegistrationKind, Set<String>> = mapOf(
        RegistrationKind.BUSINESS to setOf("submissionId", "registrationNumber", "displayName"),
        RegistrationKind.MERCHANT to setOf("submissionId", "businessId", "displayName"),
        RegistrationKind.MERCHANT_SITE to setOf("submissionId", "merchantId", "channelType", "channelIdentifier"),
    )
    private const val SCOPE_SITE_IDS = "scopeSiteIds"
    private const val EFFECTIVE_TO = "effectiveToDate"
    private val CONTRACT_REQUIRED = setOf("submissionId", "partyBusinessId", "scopeMerchantId", SCOPE_SITE_IDS, "effectiveFromDate")
    private val CONTRACT_OPTIONAL = setOf(EFFECTIVE_TO)
    private val REVISION_REQUIRED = setOf("submissionId", "contractId", "scopeMerchantId", SCOPE_SITE_IDS, "effectiveFromDate")
    private val AGREEMENT = setOf("submissionId", "contractId", "serviceKind")

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(kind: RegistrationKind, body: ByteArray): Parsed {
        val root = try {
            strict.readTree(body)
        } catch (e: JacksonException) {
            null
        }
        val request: RegistrationRequest? = when (kind) {
            RegistrationKind.CONTRACT -> scoped(root, CONTRACT_REQUIRED)?.let { (f, sites, to) ->
                RegistrationRequest.Contract(f.getValue("submissionId"), f.getValue("partyBusinessId"), f.getValue("scopeMerchantId"), sites, f.getValue("effectiveFromDate"), to)
            }
            RegistrationKind.CONTRACT_REVISION -> scoped(root, REVISION_REQUIRED)?.let { (f, sites, to) ->
                RegistrationRequest.ContractRevision(f.getValue("submissionId"), f.getValue("contractId"), f.getValue("scopeMerchantId"), sites, f.getValue("effectiveFromDate"), to)
            }
            RegistrationKind.SERVICE_AGREEMENT -> text(root, AGREEMENT)?.let {
                RegistrationRequest.ServiceAgreement(it.getValue("submissionId"), it.getValue("contractId"), it.getValue("serviceKind"))
            }
            RegistrationKind.BUSINESS -> text(root, FIELDS.getValue(kind))?.let {
                RegistrationRequest.Business(it.getValue("submissionId"), it.getValue("registrationNumber"), it.getValue("displayName"))
            }
            RegistrationKind.MERCHANT -> text(root, FIELDS.getValue(kind))?.let {
                RegistrationRequest.Merchant(it.getValue("submissionId"), it.getValue("businessId"), it.getValue("displayName"))
            }
            RegistrationKind.MERCHANT_SITE -> text(root, FIELDS.getValue(kind))?.let {
                RegistrationRequest.MerchantSite(it.getValue("submissionId"), it.getValue("merchantId"), it.getValue("channelType"), it.getValue("channelIdentifier"))
            }
        }
        // 해석 실패: 등록번호 시도 여부는 사업자 등록만(감사 brn=INVALID 판정용 — 본문 값은 넘기지 않는다).
        return request?.let { Parsed.Request(it) } ?: Parsed.Malformed(kind == RegistrationKind.BUSINESS && MerchantSearchBodyParser.namesRegistrationNumber(body))
    }

    /**
     * B1-2 계약 초안·B2-1 계약 Revision: [required]의 문자열 필드(계약 = `partyBusinessId`, Revision = `contractId`), 필수 **문자열 배열** `scopeSiteIds`(비면 MERCHANT Scope),
     * 선택 문자열 `effectiveToDate`(없음 또는 `null` = 종료 없음). 그 밖 key·자료형은 해석 실패. 결과 = (문자열 필드, Site 목록, 종료일?).
     */
    private fun scoped(root: JsonNode?, required: Set<String>): Triple<Map<String, String>, List<String>, String?>? {
        if (root == null || !root.isObject) return null
        val names = root.propertyNames().toSet()
        if (!names.containsAll(required) || !(required + CONTRACT_OPTIONAL).containsAll(names)) return null
        val textNames = required - SCOPE_SITE_IDS
        if (textNames.any { !root.get(it).isString }) return null
        val sites = root.get(SCOPE_SITE_IDS)
        if (!sites.isArray || (0 until sites.size()).any { !sites.get(it).isString }) return null
        val to = root.get(EFFECTIVE_TO)
        if (to != null && !to.isNull && !to.isString) return null
        return Triple(textNames.associateWith { root.get(it).asString() }, (0 until sites.size()).map { sites.get(it).asString() }, to?.takeIf { it.isString }?.asString())
    }

    /** 필드 집합이 정확히 [names]이고 값이 모두 JSON 문자열이면 이름 → 값, 아니면 null. */
    private fun text(root: JsonNode?, names: Set<String>): Map<String, String>? {
        if (root == null || !root.isObject || root.propertyNames().toSet() != names) return null
        if (names.any { !root.get(it).isString }) return null
        return names.associateWith { root.get(it).asString() }
    }
}
