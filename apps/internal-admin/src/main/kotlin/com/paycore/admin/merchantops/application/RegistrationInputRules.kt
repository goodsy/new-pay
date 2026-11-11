package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.RegistrationRequest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 등록 입력 형식(MERCHANT-OPS-002 C-10 — MASTER Domain 값 객체 `BusinessRegistrationNumber`·`DisplayName`·`BusinessId`·`MerchantId`·`SiteChannel`과 B1-2 `ContractId`·`MerchantSiteId`·
 * `EffectivePeriod`(끝 > 시작)·`ServiceKind`·Scope Site 상한과 **같은 규칙을 복제**한다. 코드 공유 없음 —
 * 모듈·서비스 경계. 화면 즉시 안내용이고 권위는 MASTER다). 경계 사례 표는 양쪽 시험(MASTER `MerchantOperationsRequestParserSpec`, ADMIN `MerchantRegistrationServiceSpec`)에 있다.
 */
internal object RegistrationInputRules {
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val SUBMISSION_ID = Regex("^[0-9a-f]{32}$")
    val REGISTRATION_NUMBER = Regex("^[0-9]{10}$")
    val BUSINESS_ID = Regex("^biz_$ULID$")
    val MERCHANT_ID = Regex("^mer_$ULID$")
    private val WEB_HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val APP_ID = Regex("^[a-z0-9][a-z0-9_]*(\\.[a-z0-9_]+)+$")
    private val NUMERIC_LAST_LABEL = Regex("^([0-9]+|0x[0-9a-f]*)$")
    private const val MAX_CHANNEL = 253
    private const val MAX_NAME_CODE_POINTS = 100

    val CONTRACT_ID = Regex("^ctr_$ULID$")
    private val SITE_ID = Regex("^site_$ULID$")
    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    /** SITES Scope 상한(C-2 — MASTER `MerchantOperationsRequestParser.MAX_SCOPE_SITES`와 같다). */
    const val MAX_SCOPE_SITES = 50
    private val SERVICE_KINDS = setOf("CARD_PG_PAYMENT")
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val MASTER_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    /** MASTER가 받는 시각 표기(같은 규칙 복제 — MASTER `MerchantOperationsRequestParser.INSTANT_TEXT`). */
    private val MASTER_INSTANT_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")

    fun wellFormed(request: RegistrationRequest): Boolean = SUBMISSION_ID.matches(request.submissionId) && when (request) {
        is RegistrationRequest.Business -> REGISTRATION_NUMBER.matches(request.registrationNumber) && displayName(request.displayName)
        is RegistrationRequest.Merchant -> BUSINESS_ID.matches(request.businessId) && displayName(request.displayName)
        is RegistrationRequest.MerchantSite -> MERCHANT_ID.matches(request.merchantId) && channel(request.channelType, request.channelIdentifier)
        is RegistrationRequest.Contract -> BUSINESS_ID.matches(request.partyBusinessId) && MERCHANT_ID.matches(request.scopeMerchantId) &&
            scopeSites(request.scopeSiteIds) && period(request.effectiveFromDate, request.effectiveToDate)
        is RegistrationRequest.ServiceAgreement -> CONTRACT_ID.matches(request.contractId) && request.serviceKind in SERVICE_KINDS
        is RegistrationRequest.ContractRevision -> CONTRACT_ID.matches(request.contractId) && MERCHANT_ID.matches(request.scopeMerchantId) &&
            scopeSites(request.scopeSiteIds) && period(request.effectiveFromDate, request.effectiveToDate)
        is RegistrationRequest.PaymentMethodActivation -> MERCHANT_ACCOUNT_ID.matches(request.merchantAccountId) && request.paymentMethod in PAYMENT_METHODS
        is RegistrationRequest.SiteReviewRejection -> MERCHANT_SITE_ID.matches(request.merchantSiteId) && request.reasonCode in REJECT_REASONS &&
            (request.evidenceRef == null || evidence(request.evidenceRef))
    }

    val MERCHANT_ACCOUNT_ID = Regex("^mid_$ULID$")
    val MERCHANT_SITE_ID = Regex("^site_$ULID$")

    /** B3-1 결제수단(MASTER `PaymentMethod` — 현재 CARD 하나). */
    private val PAYMENT_METHODS = setOf("CARD")

    /** C-37 Site 심사 거절 사유 코드(Demo 목록 — 운영 사유 체계 아님, MASTER `SiteReviewReasonCodes.REJECT`와 같은 목록 복제). */
    val REJECT_REASONS = setOf("DOCUMENT_INSUFFICIENT", "PROHIBITED_BUSINESS", "SITE_UNREACHABLE", "OTHER_POLICY")
    private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")

    /** 카드 번호 모양(13~19자리, 공백·하이픈 구분 허용 — 근거 참조에 받지 않는다. approval `OperatorCancelInputRules.CARD_NUMBER`와 같은 규칙 복제, 모듈 경계). */
    private val CARD_NUMBER = Regex("(?<!\\p{Nd})(?:\\p{Nd}[ -]?){12,18}\\p{Nd}(?!\\p{Nd})")

    /** 근거 참조(§9.3 형식 — MASTER `EvidenceRef`와 같다) + 카드 번호 모양 거부. */
    private fun evidence(value: String): Boolean = EVIDENCE.matches(value) && !CARD_NUMBER.containsMatchIn(value)

    /** 비면 MERCHANT Scope. 최대 [MAX_SCOPE_SITES]개, 모두 `site_` ID, 중복 없음(MASTER가 400으로 거절하는 것과 같다). */
    private fun scopeSites(ids: List<String>): Boolean = ids.size <= MAX_SCOPE_SITES && ids.toSet().size == ids.size && ids.all { SITE_ID.matches(it) }

    /** 실제 달력 날짜(`YYYY-MM-DD`, STRICT)이고 종료일이 있으면 시작일보다 뒤(MASTER `EffectivePeriod`와 같은 규칙). **소급 여부는 보지 않는다**(MASTER Domain — §3.4). */
    private fun period(from: String, to: String?): Boolean {
        val start = date(from) ?: return false
        if (to == null) return true
        val end = date(to) ?: return false
        return end.isAfter(start)
    }

    /**
     * 달력 날짜이고, 그 날 00:00 KST의 UTC 표기가 MASTER 정규형(`MerchantOperationsRequestParser.INSTANT_TEXT` — 4자리 연도)에 맞아야 한다(리뷰 m-2).
     * 예: `0000-01-01` → `-0001-12-31T15:00:00.000Z`는 MASTER가 400으로 거절해 결과 불명으로 잠기므로 MASTER 호출 전에 400. `0000-01-02`·`9999-12-31`은 통과.
     */
    private fun date(value: String): LocalDate? {
        if (!DATE.matches(value)) return null
        val parsed = runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() ?: return null
        return parsed.takeIf { MASTER_INSTANT_TEXT.matches(MASTER_INSTANT.format(it.atStartOfDay(SEOUL).toInstant())) }
    }

    /**
     * C-7: Asia/Seoul 날짜 → 그 날 00:00 KST의 UTC 시각, MASTER 표기(`…Z` ms). 예: `2026-11-09` → `2026-11-08T15:00:00.000Z`. 종료일도 같은 변환이라 그 날 0시에 끝난다(배타).
     * [wellFormed]를 통과한 값만 넣는다.
     */
    fun kstStartOfDay(date: String): String = MASTER_INSTANT.format(LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(SEOUL).toInstant())

    /** 1~100 code point, 앞뒤 공백 금지(암묵 trim 없음), 제어문자·짝 없는 서로게이트 금지. */
    fun displayName(value: String): Boolean {
        if (value.isEmpty() || value.first() == ' ' || value.last() == ' ') return false
        var i = 0
        var codePoints = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isISOControl(c) || Character.isLowSurrogate(c)) return false
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
            } else {
                i++
            }
            codePoints++
        }
        return codePoints <= MAX_NAME_CODE_POINTS
    }

    /** D-MD-009: 소문자 정규형만(자동 정규화 없음). WEB = host 이름(IP 리터럴 거절), APP = 점 구분 앱 식별자. */
    fun channel(type: String, identifier: String): Boolean = identifier.length <= MAX_CHANNEL && when (type) {
        "WEB" -> WEB_HOST.matches(identifier) && !NUMERIC_LAST_LABEL.matches(identifier.substringAfterLast('.'))
        "APP" -> APP_ID.matches(identifier)
        else -> false
    }
}
