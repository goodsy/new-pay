package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.ContractIntakeRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 계약 접수 건 명령 ADMIN 1차 검사(MERCHANT-FLOW-001 §4A.3·§4A.3a·§4A.4·§4A.6·§4B.3 — 즉시 안내용, 권위는 MASTER: `ContractIntakeRequestParser` 값 단계 + Domain 재검사).
 * 코드 공유 없음(모듈·서비스 경계 — `BusinessProfileInputRules`와 같은 복제 관례). 결과는 path 사전순, 값 없음(path·code만), 정규화·trim 없음.
 *
 * 두 묶음으로 나눈다:
 * - [Checks.format] — 같은 본문이면 늘 같은 결과(형식·길이·카드번호 패턴·열거·되돌리기/계약 불가 사유 필수·종료일 < 시작일·범위 빈 값).
 * - [Checks.timely] — ADMIN 시계에 따라 바뀌는 검사(접수일 미래 `receivedOn NOT_ALLOWED` — n-4, 시작일 과거 `startDate NOT_ALLOWED` — §4B.3). 같은 submissionId 재전송에서는
 *   다시 판정하지 않는다(n-3 — 저장된 결과 회수, Use Case가 앞선 시도 감사로 판단).
 * 계약 담당자 ACTIVE OPERATOR 검사는 계정 조회가 필요해 Use Case가 한다(같은 재전송 규칙).
 */
internal object ContractIntakeInputRules {
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val SUBMISSION_ID = Regex("^[0-9a-f]{32}$")
    val BUSINESS_ID = Regex("^biz_$ULID$")
    val ADMIN_ACCOUNT_ID = Regex("^adm_$ULID$")

    /** 지금 저장할 수 있는 접수 경로(DB CHECK `ck_cin_channel`). `SALES_AGENCY`는 FLOW-4(대행사 목록·열) 전까지 `NOT_ALLOWED`(m-2 — MASTER parser와 같다). */
    val CHANNELS = listOf("DIRECT_SALES", "PHONE", "WEBSITE", "OTHER")
    const val SALES_AGENCY = "SALES_AGENCY"
    val DESIRED_SERVICES = listOf("CARD_AUTH", "CARD_KEYIN")
    val CONTRACT_SERVICES = listOf("CARD_PG_PAYMENT")
    val KINDS = setOf("NEW", "CHANGE")
    val MOVE_TARGETS = setOf("DOCUMENT_RECEIVED", "CONSULTING", "REJECTED")

    /** 사유 필수 목표(§4A.3 — 되돌리기 = 상담 중, 계약 불가). */
    private val REASON_REQUIRED_TARGETS = setOf("CONSULTING", "REJECTED")

    private const val MAX_REASON = 200
    private const val MAX_DOCUMENT_REF = 128
    private val DOCUMENT_REF_CHAR = Regex("^[A-Za-z0-9._:#/-]+$")
    private val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private const val REQUIRED = "REQUIRED"
    private const val FORMAT = "FORMAT"
    private const val TOO_LONG = "TOO_LONG"
    private const val NOT_ALLOWED = "NOT_ALLOWED"
    private const val CARD_NUMBER_PATTERN = "CARD_NUMBER_PATTERN"

    class Checks(val format: List<BusinessFieldError>, val timely: List<BusinessFieldError>)

    /** submissionId(32 hex)·경로 businessId(`biz_` + ULID)·intakeSeq ≥ 1. 위반은 필드 오류 없는 400. */
    fun identifiersWellFormed(request: ContractIntakeRequest): Boolean =
        SUBMISSION_ID.matches(request.submissionId) && BUSINESS_ID.matches(request.businessId) && when (request) {
            is ContractIntakeRequest.Open -> true
            is ContractIntakeRequest.Move -> request.intakeSeq >= 1 && request.expectedVersion >= 0
            is ContractIntakeRequest.Complete -> request.intakeSeq >= 1 && request.expectedVersion >= 0
            is ContractIntakeRequest.ContractEvent -> request.contractSeq >= 1 && request.expectedEventCount >= 0
            is ContractIntakeRequest.UpdateItems -> request.intakeSeq >= 1 && request.expectedVersion >= 0
        }

    /** [today] = ADMIN 시계의 KST 날짜. */
    fun check(request: ContractIntakeRequest, today: LocalDate): Checks {
        val format = mutableListOf<BusinessFieldError>()
        val timely = mutableListOf<BusinessFieldError>()
        fun add(list: MutableList<BusinessFieldError>, path: String, code: String?) {
            if (code != null) list += BusinessFieldError(path, code)
        }
        request.reason?.let { add(format, "reason", reason(it)) }
        when (request) {
            is ContractIntakeRequest.Open -> {
                val received = date(request.receivedOn).also { (code, _) -> add(format, "receivedOn", code) }.second
                if (received != null && received.isAfter(today)) add(timely, "receivedOn", NOT_ALLOWED)
                add(format, "intakeChannel", channel(request.intakeChannel))
                request.contractManagerId?.let { add(format, "contractManager", if (ADMIN_ACCOUNT_ID.matches(it)) null else FORMAT) }
                request.desiredServices.forEachIndexed { i, s -> add(format, "desiredServices[$i]", if (s in DESIRED_SERVICES) null else FORMAT) }
            }
            is ContractIntakeRequest.Move -> if (request.reason == null && request.target in REASON_REQUIRED_TARGETS) add(format, "reason", REQUIRED)
            is ContractIntakeRequest.Complete -> {
                add(format, "documentRef", documentRef(request.documentRef))
                val start = date(request.startDate).also { (code, _) -> add(format, "startDate", code) }.second
                if (start != null && start.isBefore(today)) add(timely, "startDate", NOT_ALLOWED)
                val end = request.endDate?.let { raw -> date(raw).also { (code, _) -> add(format, "endDate", code) }.second }
                if (start != null && end != null && end.isBefore(start)) add(format, "endDate", NOT_ALLOWED)
                if (request.services.isEmpty()) add(format, "services", REQUIRED)
                request.services.forEachIndexed { i, s -> add(format, "services[$i]", if (s in CONTRACT_SERVICES) null else FORMAT) }
            }
            // FLOW-2c(BR-BIZ-336·339, D-GOV-022): 사유 필수, 근거 참조 선택(계약서 근거와 같은 형식·카드번호 패턴 거부).
            // FLOW-2d 접수 항목 수정(§4A.3): 생성과 같은 항목 규칙(접수일 미래는 시각 의존). 접수일을 바꿀 때 사유 필수는 저장 값과 비교해야 해 MASTER Domain이 판정한다(저장된 거절).
            is ContractIntakeRequest.UpdateItems -> {
                val received = date(request.receivedOn).also { (code, _) -> add(format, "receivedOn", code) }.second
                if (received != null && received.isAfter(today)) add(timely, "receivedOn", NOT_ALLOWED)
                add(format, "intakeChannel", channel(request.intakeChannel))
                request.contractManagerId?.let { add(format, "contractManager", if (ADMIN_ACCOUNT_ID.matches(it)) null else FORMAT) }
                request.desiredServices.forEachIndexed { i, s -> add(format, "desiredServices[$i]", if (s in DESIRED_SERVICES) null else FORMAT) }
            }
            is ContractIntakeRequest.ContractEvent -> {
                if (request.reason == null) add(format, "reason", REQUIRED)
                request.evidenceRef?.let { add(format, "evidenceRef", documentRef(it)) }
            }
        }
        return Checks(sorted(format), sorted(timely))
    }

    fun sorted(errors: List<BusinessFieldError>): List<BusinessFieldError> = errors.distinct().sortedWith(compareBy({ it.path }, { it.code }))

    /** '사유 및 비고'(§4A.3a — MASTER `BusinessChangeReason.errorOf`와 같은 규칙: 문자 규칙 1~200 code point → 카드번호 패턴). 판정 식은 사업자 사유와 같은 [RegistrationInputRules.CARD_NUMBER]. */
    private fun reason(raw: String): String? = text(raw, MAX_REASON) ?: CARD_NUMBER_PATTERN.takeIf { RegistrationInputRules.CARD_NUMBER.containsMatchIn(raw) }

    /**
     * 계약서 수령 근거 참조(§4A.4 — MASTER `ContractDocumentRef.errorOf`와 같은 순서): 빈 값 REQUIRED, 128자 초과 TOO_LONG, `[A-Za-z0-9._:#/-]` 밖 FORMAT, 그 뒤 카드번호 패턴.
     * 형식은 `ReacquisitionInputRules.EVIDENCE`·`MerchantOpsInputRules.evidenceProblem`과 같은 문법(D-MOPS-008 ①).
     */
    private fun documentRef(raw: String): String? = when {
        raw.isEmpty() -> REQUIRED
        raw.length > MAX_DOCUMENT_REF -> TOO_LONG
        !DOCUMENT_REF_CHAR.matches(raw) -> FORMAT
        RegistrationInputRules.CARD_NUMBER.containsMatchIn(raw) -> CARD_NUMBER_PATTERN
        else -> null
    }

    private fun channel(raw: String): String? = when {
        raw.isEmpty() -> REQUIRED
        raw == SALES_AGENCY -> NOT_ALLOWED
        raw !in CHANNELS -> FORMAT
        else -> null
    }

    /** 날짜(`yyyy-MM-dd`, STRICT 달력). (오류 code, 값) — 하나만 있다. */
    private fun date(raw: String): Pair<String?, LocalDate?> = when {
        raw.isEmpty() -> REQUIRED to null
        !DATE_TEXT.matches(raw) -> FORMAT to null
        else -> runCatching { LocalDate.parse(raw, DATE) }.getOrNull()?.let { null to it } ?: (FORMAT to null)
    }

    /** MASTER `BusinessTextRule.errorOf`: 빈 값 REQUIRED, 제어문자·짝 없는 서로게이트·앞뒤 공백 FORMAT, code point 상한 초과 TOO_LONG. */
    private fun text(raw: String, maxCodePoints: Int): String? = when {
        raw.isEmpty() -> REQUIRED
        !wellFormed(raw) || raw.first() == ' ' || raw.last() == ' ' -> FORMAT
        raw.codePointCount(0, raw.length) > maxCodePoints -> TOO_LONG
        else -> null
    }

    private fun wellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(c) -> return false
                c.code in 0x00..0x1F || c.code in 0x7F..0x9F -> return false
                else -> i++
            }
        }
        return true
    }
}
