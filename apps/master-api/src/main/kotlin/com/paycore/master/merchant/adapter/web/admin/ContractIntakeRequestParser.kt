package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessFieldErrorCode
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.ServiceKind
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 계약 접수 건 명령 본문 엄격 해석(MERCHANT-FLOW-001 §4B.7 — FLOW-2b, [BusinessProfileRequestParser]와 같은 JSON 규칙·결과 틀 [BusinessCommandParse]): 객체 하나, 모든 key 필수(선택 값은
 * `null`), 모르는 key·중복 key·뒤따르는 토큰 거부, 자료형 강제 변환 없음.
 * 1. 구조 → `REQUEST_MALFORMED`: key 집합·자료형, 공통 필드(멱등키·`admin:` 행위자·상관 ID), `businessId` 형식, `expectedKind`·`target` 열거, `intakeSeq` ≥ 1·`expectedVersion` ≥ 0 정수,
 *    목록(희망 서비스 ≤ 2·범위 ≤ 서비스 종류 수, 중복 없음).
 * 2. 값 → `BUSINESS_VALIDATION_FAILED` + fieldErrors(path·code만): 날짜 형식, 접수 경로(**`SALES_AGENCY`는 FLOW-4 전까지 `NOT_ALLOWED`** — 대행사 목록·열이 없다, m-2),
 *    계약 담당자 형식(`admin:` 계정 참조 — ACTIVE OPERATOR 검사는 ADMIN), 희망 서비스·범위 값, '사유 및 비고'([BusinessChangeReason] — 카드번호 패턴 포함), 근거 참조([ContractDocumentRef]).
 * 업무 규칙(사유 필수 전이·소급·기간·범위 빈 값·접수일 미래)은 Domain이 잠금 뒤 판정해 저장된 거절로 낸다. 응답·로그에 입력 값이 없다.
 */
object ContractIntakeRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val ACTOR_PREFIX = "admin:"
    private val COMMON = setOf("idempotencyKey", "actor", "correlationId", "businessId")
    private val OPEN = COMMON + setOf("expectedKind", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "reason")
    private val MOVE = COMMON + setOf("intakeSeq", "expectedVersion", "target", "reason")
    private val COMPLETE = COMMON + setOf("intakeSeq", "expectedVersion", "documentRef", "startDate", "endDate", "services", "reason")
    private val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
    private val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** FLOW-4 전까지 저장할 수 없는 경로(대행사 열 없음 — DB CHECK `ck_cin_channel`도 받지 않는다). */
    private val UNAVAILABLE_CHANNELS = setOf(ContractIntakeChannel.SALES_AGENCY)

    fun open(body: ByteArray): BusinessCommandParse<OpenContractIntakeCommand> = parse(body, OPEN) { root, v ->
        val (request, id) = common(root)
        val kind = ContractIntakeKind.entries.firstOrNull { it.name == text(root, "expectedKind") } ?: throw Malformed()
        val receivedOn = v.date("receivedOn", text(root, "receivedOn"))
        val channel = v.value("intakeChannel", text(root, "intakeChannel"), { r ->
            when {
                r.isEmpty() -> BusinessFieldErrorCode.REQUIRED
                ContractIntakeChannel.entries.none { it.name == r } -> BusinessFieldErrorCode.FORMAT
                ContractIntakeChannel.valueOf(r) in UNAVAILABLE_CHANNELS -> BusinessFieldErrorCode.NOT_ALLOWED
                else -> null
            }
        }, ContractIntakeChannel::valueOf)
        val manager = v.value("contractManager", text(root, "contractManager"), ::managerError, ::ActorRef)
        val desired = strings(root.get("desiredServices"), DesiredService.entries.size).mapIndexed { i, raw ->
            v.value("desiredServices[$i]", raw, { r -> if (DesiredService.entries.none { it.name == r }) BusinessFieldErrorCode.FORMAT else null }, DesiredService::valueOf)
        }
        val reason = v.optValue("reason", optText(root, "reason"), BusinessChangeReason::errorOf, ::BusinessChangeReason)
        v.finish {
            OpenContractIntakeCommand(request, id, kind, ContractIntakeItems(requireNotNull(receivedOn), requireNotNull(channel), requireNotNull(manager), desired.map { requireNotNull(it) }.toSet()), reason)
        }
    }

    fun move(body: ByteArray): BusinessCommandParse<MoveContractIntakeCommand> = parse(body, MOVE) { root, v ->
        val (request, id) = common(root)
        val seq = int(root, "intakeSeq", min = 1)
        val version = long(root, "expectedVersion")
        val target = MoveContractIntakeCommand.MOVE_TARGETS.firstOrNull { it.name == text(root, "target") } ?: throw Malformed()
        val reason = v.optValue("reason", optText(root, "reason"), BusinessChangeReason::errorOf, ::BusinessChangeReason)
        v.finish { MoveContractIntakeCommand(request, id, seq, version, target, reason) }
    }

    fun complete(body: ByteArray): BusinessCommandParse<CompleteContractIntakeCommand> = parse(body, COMPLETE) { root, v ->
        val (request, id) = common(root)
        val seq = int(root, "intakeSeq", min = 1)
        val version = long(root, "expectedVersion")
        val document = v.value("documentRef", text(root, "documentRef"), ContractDocumentRef::errorOf, ::ContractDocumentRef)
        val start = v.date("startDate", text(root, "startDate"))
        val endRaw = optText(root, "endDate")
        val end = endRaw?.let { v.date("endDate", it) }
        val services = strings(root.get("services"), ServiceKind.entries.size).mapIndexed { i, raw ->
            v.value("services[$i]", raw, { r -> if (ServiceKind.entries.none { it.name == r }) BusinessFieldErrorCode.FORMAT else null }, ServiceKind::valueOf)
        }
        val reason = v.optValue("reason", optText(root, "reason"), BusinessChangeReason::errorOf, ::BusinessChangeReason)
        v.finish {
            CompleteContractIntakeCommand(
                request, id, seq, version, requireNotNull(document), BusinessContractTerms(requireNotNull(start), end, services.map { requireNotNull(it) }.toSet()), reason,
            )
        }
    }

    private fun managerError(raw: String): BusinessFieldErrorCode? = when {
        raw.isEmpty() -> BusinessFieldErrorCode.REQUIRED
        !raw.startsWith(ACTOR_PREFIX) || raw.length <= ACTOR_PREFIX.length || runCatching { ActorRef(raw) }.isFailure -> BusinessFieldErrorCode.FORMAT
        else -> null
    }

    /** 구조 위반 신호(제어 흐름 전용 — 스택 추적 없음). */
    private class Malformed : RuntimeException(null, null, false, false)

    private fun common(root: JsonNode): Pair<AdminRequest, BusinessId> {
        val actor = text(root, "actor")
        if (!actor.startsWith(ACTOR_PREFIX) || actor.length <= ACTOR_PREFIX.length) throw Malformed()
        val request = runCatching { AdminRequest(AdminIdempotencyKey(text(root, "idempotencyKey")), ActorRef(actor), text(root, "correlationId")) }
            .getOrElse { throw Malformed() }
        val id = text(root, "businessId").also { if (!BUSINESS_ID.matches(it)) throw Malformed() }
        return request to BusinessId(id)
    }

    private fun <T> parse(body: ByteArray, names: Set<String>, build: (JsonNode, Values) -> BusinessCommandParse<T>): BusinessCommandParse<T> = try {
        val root: JsonNode? = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != names) BusinessCommandParse.Malformed else build(root, Values())
    } catch (_: Malformed) {
        BusinessCommandParse.Malformed
    } catch (_: RuntimeException) {
        BusinessCommandParse.Malformed
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

    private fun int(node: JsonNode, name: String, min: Int): Int {
        val value = node.get(name)
        if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.asInt() < min) throw Malformed()
        return value.asInt()
    }

    private fun long(node: JsonNode, name: String): Long {
        val value = node.get(name)
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0) throw Malformed()
        return value.asLong()
    }

    /** 문자열 배열(길이 ≤ [max], 중복 없음 — 위반은 구조 위반). */
    private fun strings(node: JsonNode?, max: Int): List<String> {
        if (node == null || !node.isArray || node.size() > max) throw Malformed()
        val values = (0 until node.size()).map { i -> node.get(i).also { if (!it.isString) throw Malformed() }.asString() }
        if (values.toSet().size != values.size) throw Malformed()
        return values
    }

    /** 값 규칙 판정 누적기(path·code만 — 값 없음, path 사전순). */
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

        fun <T> optValue(path: String, raw: String?, errorOf: (String) -> BusinessFieldErrorCode?, build: (String) -> T): T? = raw?.let { value(path, it, errorOf, build) }

        fun date(path: String, raw: String): LocalDate? = value(path, raw, { r ->
            when {
                r.isEmpty() -> BusinessFieldErrorCode.REQUIRED
                !DATE_TEXT.matches(r) || runCatching { LocalDate.parse(r, DATE) }.isFailure -> BusinessFieldErrorCode.FORMAT
                else -> null
            }
        }) { r -> LocalDate.parse(r, DATE) }

        fun <T> finish(build: () -> T): BusinessCommandParse<T> =
            if (errors.isEmpty()) BusinessCommandParse.Parsed(build()) else BusinessCommandParse.Invalid(errors.sortedBy { it.path })
    }
}
