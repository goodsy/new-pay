package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedCommand
import com.paycore.master.merchant.application.provided.ApprovedContractRevisionApprovalCommand
import com.paycore.master.merchant.application.provided.ApprovedCurrencyEnablementCommand
import com.paycore.master.merchant.application.provided.ApprovedFirstDefaultIssuanceCommand
import com.paycore.master.merchant.application.provided.ApprovedMerchantCodeChangeCommand
import com.paycore.master.merchant.application.provided.ApprovedPaymentMethodActivationApprovalCommand
import com.paycore.master.merchant.application.provided.ApprovedSiteReviewApprovalCommand
import com.paycore.master.merchant.application.provided.CurrencyEffectiveStart
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeChangeReason
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SeoulMidnight
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 2인 승인 명령 본문 엄격 해석(MERCHANT-OPS-002 §9.3 본문 공통 필드·§9.4 — [MerchantOperationsRequestParser]와 같은 규칙): JSON 객체 하나, **정해진 필드 전부**(선택 필드 없음,
 * 모르는 필드·빠진 필드 거부), 중복 key·뒤따르는 토큰 거부, 값은 모두 JSON 문자열(`null`·자료형 강제 변환 거부).
 *
 * 공통 필드(B1 공통 필드와 다름 — `actor` 필드는 **없다**, 오면 400 C-17): `idempotencyKey` = 정확히 `mop1.<approvalId>`, `correlationId` = 정확히 `mop.<approvalId>`,
 * `approvalId` `^apr_<ULID>$`, `requestedBy`·`approvedBy` 정규형 `^admin:adm_<ULID 대문자>$`(대소문자 변형 거부 — 문자열 비교가 신원 비교), `approvedAt`·`executeBefore` 정규 ms `…Z`,
 * `evidenceRef` 필수 `^[A-Za-z0-9._:#/-]{1,128}$`(D-MOPS-008 ①), `approvalFingerprint` = MASTER `mop1` 재계산과 같아야 한다(C-23). 실행자 = 승인자([AdminRequest.actor] = approvedBy).
 * 같은 행위자·창 위반은 여기서 거르지 않는다 — **저장된 거절**(§9.3 판정 1·2, 자기 감사)이다.
 *
 * 어긋나면 null → 400 `REQUEST_MALFORMED`(어느 필드인지·값을 응답·로그에 넣지 않는다, Use Case 호출 전이라 저장 없음).
 */
object MerchantApprovalCommandParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val EVIDENCE_FIELDS = setOf(
        "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint",
    )
    private val CONTRACT_REVISION_APPROVAL = EVIDENCE_FIELDS + setOf("contractId", "revisionNo")
    private val FIRST_DEFAULT_ISSUANCE = EVIDENCE_FIELDS + setOf("merchantId", "contractId", "agreementId", "revisionNo", "primarySiteId")
    private val ACTIVATION_APPROVAL = EVIDENCE_FIELDS + setOf("operation", "activationId", "primarySiteId")
    private val SITE_REVIEW_APPROVAL = EVIDENCE_FIELDS + setOf("operation", "merchantSiteId", "reasonCode")

    /** B3-2 필수 필드(선택 `effectiveTo`는 [currencyEnablement]가 따로 허용). */
    private val CURRENCY_ENABLEMENT = EVIDENCE_FIELDS + setOf("operation", "merchantAccountId", "currency", "effectiveFrom")

    /** MERCHANT-CODE-001 MC-1b(§3.7.2): 근거 공통 9 + `operation` + 명령 필드 4(등록자 필드 없음 — Q-MC-10). */
    private val MERCHANT_CODE_CHANGE = EVIDENCE_FIELDS + setOf("operation", "merchantId", "currentCode", "newCode", "reasonCode")

    /** C-41: 관리 채널 통화 활성화는 KRW만(실제 통화 범위 추가안은 사용자 채택 전). */
    private val ENABLED_CURRENCIES = setOf("KRW")
    private val DECISION = setOf("approvalId", "correlationId")

    /** C-30: `[1-9][0-9]{0,9}` + 값 ≤ 2147483647(DB `revision_no INT`). */
    private val REVISION_NO = Regex("^[1-9][0-9]{0,9}$")
    private val INSTANT_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val INSTANT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val CORRELATION = Regex("^[!-~]{1,128}$")

    /** B2-2 `contract-revision-approvals`: 명령 필드 `contractId`, `revisionNo`(§9.4 표 순서 = `mop1` 끝 줄 순서). */
    fun contractRevisionApproval(body: ByteArray, window: ApprovalWindow): ApprovedContractRevisionApprovalCommand? =
        parse(body, CONTRACT_REVISION_APPROVAL) { fields ->
            val contractId = ContractId(fields.getValue("contractId"))
            val revisionText = fields.getValue("revisionNo")
            require(REVISION_NO.matches(revisionText)) { "revisionNo" }
            val revisionNo = revisionText.toLong().also { require(it <= Int.MAX_VALUE) { "revisionNo range" } }.toInt()
            val (request, evidence) = approved(fields, AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId.value, revisionText))
            ApprovedContractRevisionApprovalCommand(request, evidence, window, contractId, revisionNo)
        }

    /**
     * B2-3 `first-default-merchant-accounts`: 명령 필드 `merchantId`, `contractId`, `agreementId`, `revisionNo`, `primarySiteId`(§9.4 표 순서 = `mop1` 끝 줄 순서).
     * 형식은 Domain 값 객체가 검사한다(위반 = 400).
     */
    fun firstDefaultIssuance(body: ByteArray, window: ApprovalWindow): ApprovedFirstDefaultIssuanceCommand? =
        parse(body, FIRST_DEFAULT_ISSUANCE) { fields ->
            val merchantId = MerchantId(fields.getValue("merchantId"))
            val contractId = ContractId(fields.getValue("contractId"))
            val agreementId = ContractServiceAgreementId(fields.getValue("agreementId"))
            val primarySiteId = MerchantSiteId(fields.getValue("primarySiteId"))
            val revisionText = fields.getValue("revisionNo")
            require(REVISION_NO.matches(revisionText)) { "revisionNo" }
            val revisionNo = revisionText.toLong().also { require(it <= Int.MAX_VALUE) { "revisionNo range" } }.toInt()
            val (request, evidence) = approved(
                fields, AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf(merchantId.value, contractId.value, agreementId.value, revisionText, primarySiteId.value),
            )
            ApprovedFirstDefaultIssuanceCommand(request, evidence, window, merchantId, contractId, agreementId, revisionNo, primarySiteId)
        }

    /**
     * `approval-deadline-confirmations`: 실행 요청과 **같은 저장 바이트**(§9.4). operation은 본문 모양으로 정한다 — 알려진 승인 명령 해석기(계약 승인·첫 MID 발급) 중
     * 정확히 하나가 받아야 한다(B2-3 리뷰 n-1 — KDoc 위치 정정).
     */
    fun deadlineConfirmation(body: ByteArray, window: ApprovalWindow): ApprovedCommand? =
        // B3 본문은 `operation` 필드가 있어 B2 해석기(정확한 필드 집합)가 받지 않고, B2 본문은 B3 해석기가 받지 않는다(minor-4 — 정확히 하나).
        listOfNotNull<ApprovedCommand>(
            contractRevisionApproval(body, window), firstDefaultIssuance(body, window), paymentMethodActivationApproval(body, window), siteReviewApproval(body, window),
            currencyEnablement(body, window), merchantCodeChange(body, window),
        ).singleOrNull()

    /**
     * B3-1 `payment-method-activation-approvals`(§9.14): 근거 공통 9 + **`operation` = `ApprovePaymentMethodActivation`**(C-39) + 명령 필드 `activationId`, `primarySiteId`(C-45 — `mop1` 끝 줄 순서).
     */
    fun paymentMethodActivationApproval(body: ByteArray, window: ApprovalWindow): ApprovedPaymentMethodActivationApprovalCommand? =
        parse(body, ACTIVATION_APPROVAL) { fields ->
            require(fields.getValue("operation") == AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION.code) { "operation" }
            val activationId = PaymentMethodActivationId(fields.getValue("activationId"))
            val primarySiteId = MerchantSiteId(fields.getValue("primarySiteId"))
            val (request, evidence) = approved(fields, AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, listOf(activationId.value, primarySiteId.value))
            ApprovedPaymentMethodActivationApprovalCommand(request, evidence, window, activationId, primarySiteId)
        }

    /** B3-1 `site-review-approvals`(§9.14 — APPROVED 고정, 결정값 필드 없음 C-40): `operation` = `DecideSiteReview` + `merchantSiteId`, `reasonCode`(승인 사유 목록 — C-37). */
    fun siteReviewApproval(body: ByteArray, window: ApprovalWindow): ApprovedSiteReviewApprovalCommand? =
        parse(body, SITE_REVIEW_APPROVAL) { fields ->
            require(fields.getValue("operation") == AdminOperation.DECIDE_SITE_REVIEW.code) { "operation" }
            val siteId = MerchantSiteId(fields.getValue("merchantSiteId"))
            val reasonCode = fields.getValue("reasonCode")
            require(reasonCode in SiteReviewReasonCodes.APPROVE) { "reasonCode" }
            val (request, evidence) = approved(fields, AdminOperation.DECIDE_SITE_REVIEW, listOf(siteId.value, reasonCode))
            ApprovedSiteReviewApprovalCommand(request, evidence, window, siteId, DecisionReason(reasonCode))
        }

    /**
     * B3-2 `currency-enablements`(§9.14·§9.26 — D-MOPS-009 ②, C-41·C-48): 근거 공통 9 + **`operation` = `EnableCurrency`**(C-39) + 명령 필드 `merchantAccountId`, `currency`(**KRW만**),
     * `effectiveFrom`(정확히 `AT_EXECUTION` 또는 정규 `…Z` ms 시각이면서 **KST 00:00**), `effectiveTo`(선택 — 오면 KST 00:00 정규 시각, 없으면 `mop1` 끝 줄은 빈 줄).
     * `mop1`·요청 지문의 명령 필드 = 본문 문자열 그대로(확정 시각 아님). 기간이 비는지(`to ≤ from`·`to ≤ 확정 시각`)는 MASTER Use Case의 저장된 거절(`EFFECTIVE_PERIOD_EMPTY`)이다.
     */
    fun currencyEnablement(body: ByteArray, window: ApprovalWindow): ApprovedCurrencyEnablementCommand? =
        parse(body, CURRENCY_ENABLEMENT, optional = setOf("effectiveTo")) { fields ->
            require(fields.getValue("operation") == AdminOperation.ENABLE_CURRENCY.code) { "operation" }
            val mid = MerchantAccountId(fields.getValue("merchantAccountId"))
            val currencyText = fields.getValue("currency")
            require(currencyText in ENABLED_CURRENCIES) { "currency" }
            val fromText = fields.getValue("effectiveFrom")
            val from = if (fromText == CurrencyEffectiveStart.AT_EXECUTION_TEXT) CurrencyEffectiveStart.AtExecution else CurrencyEffectiveStart.OnDate(kstMidnight(fromText))
            val toText = fields["effectiveTo"]
            val to = toText?.let(::kstMidnight)
            val (request, evidence) = approved(fields, AdminOperation.ENABLE_CURRENCY, listOf(mid.value, currencyText, fromText, toText ?: ""))
            ApprovedCurrencyEnablementCommand(request, evidence, window, mid, CurrencyCode(currencyText), from, to)
        }

    /**
     * MERCHANT-CODE-001 MC-1b `merchant-code-changes`(§3.7.2 — D-MCODE-002 Q-MC-7): 근거 공통 9 + `operation` = `ChangeMerchantCode`(B3 본문 관례 C-39 — 기한 확인 본문 판별) + 명령 필드
     * `merchantId`(`mer_`), `currentCode`·`newCode`(§3.1 형식 — 정규화 없음, 위반 400), `reasonCode`(C-12 고정 목록). `actor`·`registeredBy` 등 모르는 필드는 400(Q-MC-10 — 등록자 필드 없음).
     * 예약어·중복·변경 가능 여부는 저장된 거절(Use Case 판정)이다.
     */
    fun merchantCodeChange(body: ByteArray, window: ApprovalWindow): ApprovedMerchantCodeChangeCommand? =
        parse(body, MERCHANT_CODE_CHANGE) { fields ->
            require(fields.getValue("operation") == AdminOperation.CHANGE_MERCHANT_CODE.code) { "operation" }
            val merchantId = MerchantId(fields.getValue("merchantId"))
            val currentCode = MerchantCode(fields.getValue("currentCode"))
            val newCode = MerchantCode(fields.getValue("newCode"))
            val reasonText = fields.getValue("reasonCode")
            val reason = requireNotNull(MerchantCodeChangeReason.entries.firstOrNull { it.name == reasonText }) { "reasonCode" }
            val (request, evidence) = approved(fields, AdminOperation.CHANGE_MERCHANT_CODE, listOf(merchantId.value, currentCode.value, newCode.value, reason.name))
            ApprovedMerchantCodeChangeCommand(request, evidence, window, merchantId, currentCode, newCode, reason)
        }

    /** C-48: 정규 `…Z` ms 시각이면서 Asia/Seoul 00:00(`SeoulMidnight.startOfDay(x) == x`). */
    private fun kstMidnight(value: String): Instant {
        val at = instant(value)
        require(SeoulMidnight.startOfDay(at) == at) { "KST midnight" }
        return at
    }

    /** `approval-decisions`: `approvalId`, `correlationId`(1..128 printable ASCII — 상관용)만. 키·근거 필드 없음. */
    fun decisionApprovalId(body: ByteArray): String? = parse(body, DECISION) { fields ->
        val approvalId = fields.getValue("approvalId")
        require(ApprovalEvidence.APPROVAL_ID.matches(approvalId)) { "approvalId" }
        require(CORRELATION.matches(fields.getValue("correlationId"))) { "correlationId" }
        approvalId
    }

    /** 근거 공통 필드 → (요청 맥락, 근거). 지문은 MASTER가 다시 계산해 같아야 한다. */
    private fun approved(fields: Map<String, String>, operation: AdminOperation, commandFields: List<String>): Pair<AdminRequest, ApprovalEvidence> {
        val approvalId = fields.getValue("approvalId")
        require(ApprovalEvidence.APPROVAL_ID.matches(approvalId)) { "approvalId" }
        require(fields.getValue("idempotencyKey") == "$KEY_PREFIX$approvalId") { "idempotencyKey" }
        require(fields.getValue("correlationId") == "$CORRELATION_PREFIX$approvalId") { "correlationId" }
        val requestedBy = fields.getValue("requestedBy")
        val approvedBy = fields.getValue("approvedBy")
        require(ApprovalEvidence.ACTOR.matches(requestedBy) && ApprovalEvidence.ACTOR.matches(approvedBy)) { "actor" }
        val approvedAt = instant(fields.getValue("approvedAt"))
        val executeBefore = instant(fields.getValue("executeBefore"))
        val evidenceRef = EvidenceRef(fields.getValue("evidenceRef"))
        val fingerprint = fields.getValue("approvalFingerprint")
        val expected = ApprovalFingerprint.mop1(operation, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef.value, commandFields)
        require(fingerprint == expected) { "fingerprint" }
        val evidence = ApprovalEvidence(approvalId, ActorRef(requestedBy), ActorRef(approvedBy), approvedAt, executeBefore, evidenceRef, fingerprint)
        val request = AdminRequest(AdminIdempotencyKey(fields.getValue("idempotencyKey")), evidence.approvedBy, fields.getValue("correlationId"))
        return request to evidence
    }

    private fun instant(value: String): Instant {
        require(INSTANT_TEXT.matches(value)) { "instant format" }
        val parsed = Instant.parse(value)
        require(INSTANT_FORMAT.format(parsed) == value) { "instant canonical" }
        return parsed
    }

    /**
     * 필드 집합이 정확히 [names](+ [optional] 중 온 것)이고 값이 모두 JSON 문자열이면 [build]. 어떤 위반(예외 포함)이든 null. 선택 필드는 B3-2 `effectiveTo`뿐이다
     * (없으면 맵에 키가 없다 — JSON `null`·빈 문자열은 값 검사에서 거부).
     */
    private fun <T> parse(body: ByteArray, names: Set<String>, optional: Set<String> = emptySet(), build: (Map<String, String>) -> T): T? = runCatching {
        val root: JsonNode = mapper.readTree(body) ?: return null
        val present = root.takeIf { it.isObject }?.propertyNames()?.toSet() ?: return null
        if (!present.containsAll(names) || !(names + optional).containsAll(present)) return null
        build(present.associateWith { name -> root.get(name).also { require(it.isString) { "type" } }.asString() })
    }.getOrNull()

    private const val KEY_PREFIX = "mop1."
    private const val CORRELATION_PREFIX = "mop."
}
