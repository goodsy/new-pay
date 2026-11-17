package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.application.provided.RejectSiteReviewCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteChannel
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * MASTER 관리 명령 본문 엄격 해석(MERCHANT-OPS-002 §3.2 — MERCHANT-OPS-001 [MerchantSearchRequestParser]와 같은 규칙): JSON 객체 하나, **정해진 필드 전부**(선택 필드는 B1-2 계약의
 * `effectiveTo` 하나 — 빠진 필수 필드·모르는 필드 거부), 중복 key·뒤따르는 토큰 거부, 값은 JSON 문자열(계약 `scopeSiteIds`만 문자열 배열 — 자료형 강제 변환·`null` 거부, 선택 필드만 `null` 허용).
 *
 * 공통 필드: `idempotencyKey`([AdminIdempotencyKey] 형식), `actor`(**`admin:` 접두** + [ActorRef] 형식 — 서명 principal은 kid뿐이라 운영자 신원은 검증할 수 없고, 다른 이름공간
 * (`system:demo-seed` 등)을 채널로 쓰지 못하게 접두만 강제한다, §3.1), `correlationId`(1..128 printable ASCII — [AdminRequest]).
 * 명령 값은 Domain 값 객체(`BusinessRegistrationNumber`·`DisplayName`·`*Id`·`SiteChannel`)가 검사한다. 어긋나면 null → 400 `REQUEST_MALFORMED`(어느 필드인지·입력 값을
 * 응답·로그에 넣지 않는다). 위반은 Use Case 호출 전이라 `admin_request`에 저장되지 않는다.
 */
object MerchantOperationsRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val ACTOR_PREFIX = "admin:"
    private val COMMON = setOf("idempotencyKey", "actor", "correlationId")
    // MERCHANT-CODE-001 MC-1(§3.3·§4): `merchantCode` 필수 — 형식(§3.1)은 값 객체가 검사(위반 400, 정규화 없음). 예약어·중복은 Use Case의 저장된 거절.
    private val MERCHANT = COMMON + setOf("businessId", "displayName", "merchantCode")
    private val SITE = COMMON + setOf("merchantId", "channelType", "channelIdentifier")
    private val CONTRACT = COMMON + setOf("partyBusinessId", "scopeMerchantId", "scopeSiteIds", "effectiveFrom")
    private val CONTRACT_OPTIONAL = setOf("effectiveTo")
    private val CONTRACT_REVISION = COMMON + setOf("contractId", "scopeMerchantId", "scopeSiteIds", "effectiveFrom")
    private val AGREEMENT = COMMON + setOf("contractId", "serviceKind")
    private val ACTIVATION = COMMON + setOf("merchantAccountId", "paymentMethod")
    private val SITE_REJECTION = COMMON + setOf("merchantSiteId", "reasonCode")

    /** SITES Scope 상한(MERCHANT-OPS-002 C-2 — Demo 값 아님, 채널 형식 상한). */
    const val MAX_SCOPE_SITES = 50
    private val INSTANT_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val INSTANT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun merchant(body: ByteArray): RegisterMerchantCommand? = parse(body, MERCHANT) { fields ->
        RegisterMerchantCommand(
            request(fields), BusinessId(fields.getValue("businessId")), DisplayName(fields.getValue("displayName")), MerchantCode(fields.getValue("merchantCode")),
        )
    }

    fun site(body: ByteArray): RegisterMerchantSiteCommand? = parse(body, SITE) { fields ->
        val type = ChannelType.entries.single { it.name == fields.getValue("channelType") }
        // D-MD-009: 소문자 정규형만 — 자동 정규화 없이 Domain 값 객체로 검사만 한다(Use Case 안에서 같은 값으로 다시 만든다).
        val channel = SiteChannel(type, fields.getValue("channelIdentifier"))
        RegisterMerchantSiteCommand(request(fields), MerchantId(fields.getValue("merchantId")), channel.type, channel.identifier)
    }

    /**
     * B1-2 계약 초안(Revision 1). `scopeSiteIds`는 **필수 배열**(비면 MERCHANT Scope, 최대 [MAX_SCOPE_SITES] — C-2, 원소는 JSON 문자열 `site_…`, 중복 거부 — 조용히 합치지 않는다),
     * `effectiveFrom`·`effectiveTo`는 정규 표기 `yyyy-MM-ddTHH:mm:ss.SSSZ`만(다시 같은 표기로 써서 같아야 한다 — 24:00·윤초·없는 날짜·offset 거부), `effectiveTo`는 선택(없음 또는 `null`).
     * 기간은 [EffectivePeriod] 값 객체가 검사한다(끝 ≤ 시작이면 400). **소급 시작은 여기서 판정하지 않는다** — Domain `Contract.register`가 서버 시각으로 판정한다(§3.4).
     */
    fun contract(body: ByteArray): RegisterContractCommand? = parseNode(body, CONTRACT, CONTRACT_OPTIONAL) { root ->
        RegisterContractCommand(
            request(COMMON.associateWith { text(root.get(it)) }),
            BusinessId(text(root.get("partyBusinessId"))),
            scope(root),
            period(root),
        )
    }

    /**
     * B2-1 계약 Revision 등록(MERCHANT-OPS-002 §9.2 — 1인, 미승인으로 남는다): `contractId`, `scopeMerchantId`, `scopeSiteIds`, `effectiveFrom`, `effectiveTo`(선택).
     * Scope·기간 규칙은 [contract]와 같다. **소급 시작은 여기서 판정하지 않는다** — Domain `Contract.registerRevision`이 서버 시각으로 판정한다(D-MOPS-007 ②).
     */
    fun contractRevision(body: ByteArray): RegisterContractRevisionCommand? = parseNode(body, CONTRACT_REVISION, CONTRACT_OPTIONAL) { root ->
        RegisterContractRevisionCommand(
            request(COMMON.associateWith { text(root.get(it)) }),
            ContractId(text(root.get("contractId"))),
            scope(root),
            period(root),
        )
    }

    /** `scopeSiteIds`: 필수 배열(비면 MERCHANT Scope, 최대 [MAX_SCOPE_SITES], 원소 문자열, 중복 거부). */
    private fun scope(root: JsonNode): ScopeInput {
        val sites = root.get("scopeSiteIds")
        require(sites.isArray && sites.size() <= MAX_SCOPE_SITES) { "scopeSiteIds" }
        val siteIds = (0 until sites.size()).map { i -> MerchantSiteId(text(sites.get(i))) }
        require(siteIds.toSet().size == siteIds.size) { "duplicate site" }
        return ScopeInput(MerchantId(text(root.get("scopeMerchantId"))), siteIds.toSet())
    }

    /** `effectiveFrom`(필수)·`effectiveTo`(선택 — 없음 또는 `null`), 정규 표기만. */
    private fun period(root: JsonNode): EffectivePeriod {
        val to = root.get("effectiveTo")?.takeUnless { it.isNull }?.let { instant(text(it)) }
        return EffectivePeriod(instant(text(root.get("effectiveFrom"))), to)
    }

    /** B1-2 서비스 계약(CSA) 추가: `contractId`, `serviceKind`(현재 `CARD_PG_PAYMENT`만 — D-MD-002). */
    fun serviceAgreement(body: ByteArray): AddServiceAgreementCommand? = parse(body, AGREEMENT) { fields ->
        val kind = ServiceKind.entries.single { it.name == fields.getValue("serviceKind") }
        AddServiceAgreementCommand(request(fields), ContractId(fields.getValue("contractId")), kind)
    }

    /** B3-1 1인 수단 개통 등록(§9.14 `payment-method-activations` — UNDER_REVIEW로 남는다): `merchantAccountId`, `paymentMethod`(현재 `CARD`만 — 기존 enum). */
    fun paymentMethodActivation(body: ByteArray): RegisterPaymentMethodActivationCommand? = parse(body, ACTIVATION) { fields ->
        val method = PaymentMethod.entries.single { it.name == fields.getValue("paymentMethod") }
        RegisterPaymentMethodActivationCommand(request(fields), MerchantAccountId(fields.getValue("merchantAccountId")), method)
    }

    /**
     * B3-1 1인 Site 심사 거절(§9.14 `site-review-rejections`): `merchantSiteId`, `reasonCode`(거절 사유 목록 — C-37), `evidenceRef`(선택 — 없음만, `null` 거부).
     * **결정값 필드를 받지 않는다**(오면 모르는 key라 400 — C-40: 1인 채널로 APPROVED·SUSPENDED를 만들 수 없다).
     */
    fun siteReviewRejection(body: ByteArray): RejectSiteReviewCommand? = parseNode(body, SITE_REJECTION, setOf("evidenceRef")) { root ->
        val reasonCode = text(root.get("reasonCode"))
        require(reasonCode in SiteReviewReasonCodes.REJECT) { "reasonCode" }
        RejectSiteReviewCommand(
            request(COMMON.associateWith { text(root.get(it)) }),
            MerchantSiteId(text(root.get("merchantSiteId"))),
            DecisionReason(reasonCode),
            root.get("evidenceRef")?.let { EvidenceRef(text(it)) },
        )
    }

    private fun text(node: JsonNode?): String = requireNotNull(node).also { require(it.isString) { "type" } }.asString()

    /** 정규 ms 표기만(왕복 표기가 같아야 한다). */
    private fun instant(value: String): Instant {
        require(INSTANT_TEXT.matches(value)) { "instant format" }
        val parsed = Instant.parse(value)
        require(INSTANT_FORMAT.format(parsed) == value) { "instant canonical" }
        return parsed
    }

    private fun request(fields: Map<String, String>): AdminRequest {
        val actor = fields.getValue("actor")
        require(actor.startsWith(ACTOR_PREFIX) && actor.length > ACTOR_PREFIX.length) { "actor namespace" }
        return AdminRequest(AdminIdempotencyKey(fields.getValue("idempotencyKey")), ActorRef(actor), fields.getValue("correlationId"))
    }

    /** 필드 집합이 정확히 [names]이고 값이 모두 JSON 문자열이면 [build]. 어떤 위반(예외 포함)이든 null. */
    private fun <T> parse(body: ByteArray, names: Set<String>, build: (Map<String, String>) -> T): T? = parseNode(body, names, emptySet()) { root ->
        build(names.associateWith { name -> text(root.get(name)) })
    }

    /** 필드 집합이 [required]를 모두 포함하고 [required] + [optional] 밖의 key가 없으면 [build](값 자료형은 [build]가 검사). 어떤 위반(예외 포함)이든 null. */
    private fun <T> parseNode(body: ByteArray, required: Set<String>, optional: Set<String>, build: (JsonNode) -> T): T? = runCatching {
        val root: JsonNode = mapper.readTree(body) ?: return null
        val names = if (root.isObject) root.propertyNames().toSet() else return null
        if (!names.containsAll(required) || !(required + optional).containsAll(names)) return null
        build(root)
    }.getOrNull()
}
