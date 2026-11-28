package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation
import com.paycore.admin.merchantops.application.provided.ContractIntakeRequest
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 계약 접수 건 명령 본문 엄격 해석(MERCHANT-FLOW-001 §4B.11 표 2b 행 — [BusinessProfileBodyParser]와 같은 규칙, MASTER `ContractIntakeRequestParser` 1단계(구조)와 같은 판정):
 * JSON 객체 하나, **모든 key 필수**(선택 값은 `null`), 모르는 key·중복 key·뒤따르는 토큰·자료형 강제 변환 거부. `expectedKind` ∈ NEW·CHANGE, `target` ∈ DOCUMENT_RECEIVED·CONSULTING·REJECTED,
 * `expectedVersion` = JSON 정수 ≥ 0, 목록(희망 서비스 ≤ 2·범위 ≤ 1)은 문자열·중복 없음. 대상 businessId·intakeSeq는 경로 값(intakeSeq = 1..999999999 십진).
 * 멱등 키는 본문 `submissionId`(32 hex — 사업자 명령 관례, ADMIN이 MASTER 키 `mo1.<계정>.<submissionId>`로 바꾼다).
 * 값 규칙은 Use Case(`ContractIntakeInputRules`)가 본다(역할이 형식보다 먼저 — 감사 순서). 실패하면 null(본문 원문·값은 넘기지 않는다).
 */
object ContractIntakeBodyParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    // FLOW-2d(D-GOV-024 Q-FLOW-40): 계약 담당자는 계정 키 대신 이 세션의 불투명 참조 `contractManagerRef`(접수 생성·항목 수정). 푸는 것은 호출자가 준 [resolve](세션 대응표).
    private val OPEN = setOf("submissionId", "expectedKind", "receivedOn", "intakeChannel", "contractManagerRef", "desiredServices", "reason")
    private val ITEMS = setOf("submissionId", "expectedVersion", "receivedOn", "intakeChannel", "contractManagerRef", "desiredServices", "reason")
    private val MOVE = setOf("submissionId", "expectedVersion", "target", "reason")
    private val COMPLETE = setOf("submissionId", "expectedVersion", "documentRef", "startDate", "endDate", "services", "reason")
    private val CONTRACT_EVENT = setOf("submissionId", "expectedEventCount", "reason", "evidenceRef")
    private val KINDS = setOf("NEW", "CHANGE")
    private val TARGETS = setOf("DOCUMENT_RECEIVED", "CONSULTING", "REJECTED")
    private val INTAKE_SEQ = Regex("^[1-9][0-9]{0,8}$")
    private const val MAX_DESIRED = 2
    private const val MAX_SERVICES = 1

    /** [resolve] = 담당자 참조 → 계정 키(풀 수 없으면 null — Use Case가 `contractManager NOT_ALLOWED`). 담당자가 없는 명령에서는 부르지 않는다. */
    fun parse(operation: ContractIntakeOperation, businessId: String, intakeSeq: String?, body: ByteArray, resolve: (String) -> String? = { null }): ContractIntakeRequest? = try {
        val root: JsonNode? = mapper.readTree(body)
        if (root == null || !root.isObject) {
            null
        } else {
            when (operation) {
                ContractIntakeOperation.OPEN -> {
                    exact(root, OPEN)
                    val kind = text(root, "expectedKind").also { if (it !in KINDS) throw Malformed() }
                    ContractIntakeRequest.Open(
                        businessId, text(root, "submissionId"), kind, text(root, "receivedOn"), text(root, "intakeChannel"), resolve(text(root, "contractManagerRef")),
                        strings(root.get("desiredServices"), MAX_DESIRED), optText(root, "reason"),
                    )
                }
                ContractIntakeOperation.UPDATE_ITEMS -> {
                    exact(root, ITEMS)
                    ContractIntakeRequest.UpdateItems(
                        businessId, seq(intakeSeq), text(root, "submissionId"), version(root), text(root, "receivedOn"), text(root, "intakeChannel"),
                        resolve(text(root, "contractManagerRef")), strings(root.get("desiredServices"), MAX_DESIRED), optText(root, "reason"),
                    )
                }
                ContractIntakeOperation.MOVE -> {
                    exact(root, MOVE)
                    val target = text(root, "target").also { if (it !in TARGETS) throw Malformed() }
                    ContractIntakeRequest.Move(businessId, seq(intakeSeq), text(root, "submissionId"), version(root), target, optText(root, "reason"))
                }
                ContractIntakeOperation.COMPLETE -> {
                    exact(root, COMPLETE)
                    ContractIntakeRequest.Complete(
                        businessId, seq(intakeSeq), text(root, "submissionId"), version(root), text(root, "documentRef"), text(root, "startDate"), optText(root, "endDate"),
                        strings(root.get("services"), MAX_SERVICES), optText(root, "reason"),
                    )
                }
                // FLOW-2c 사업자 계약 정지·정지 해제·해지: 경로 contractSeq(intakeSeq와 같은 문법), 사건 수(정수 ≥ 0), 사유(문자열|null — null은 Use Case가 REQUIRED), 근거(문자열|null).
                ContractIntakeOperation.SUSPEND, ContractIntakeOperation.RESUME, ContractIntakeOperation.TERMINATE -> {
                    exact(root, CONTRACT_EVENT)
                    val count = root.get("expectedEventCount")
                    if (count == null || !count.isIntegralNumber || !count.canConvertToInt() || count.asInt() < 0) throw Malformed()
                    ContractIntakeRequest.ContractEvent(
                        operation, businessId, seq(intakeSeq), text(root, "submissionId"), count.asInt(), optText(root, "reason"), optText(root, "evidenceRef"),
                    )
                }
            }
        }
    } catch (_: Malformed) {
        null
    } catch (_: RuntimeException) {
        // JSON 문법·중복 key·뒤따르는 토큰(Jackson 예외) — 메시지(입력 조각 포함 가능)는 버린다.
        null
    }

    /** 경로 intakeSeq(십진 1..999999999 — 앞 0·부호 없음). */
    fun intakeSeqWellFormed(raw: String): Boolean = INTAKE_SEQ.matches(raw)

    private class Malformed : RuntimeException(null, null, false, false)

    private fun seq(raw: String?): Int = raw?.takeIf(::intakeSeqWellFormed)?.toInt() ?: throw Malformed()

    private fun version(root: JsonNode): Long {
        val v = root.get("expectedVersion")
        if (v == null || !v.isIntegralNumber || !v.canConvertToLong() || v.asLong() < 0) throw Malformed()
        return v.asLong()
    }

    private fun exact(node: JsonNode, names: Set<String>) {
        if (node.propertyNames().toSet() != names) throw Malformed()
    }

    private fun strings(node: JsonNode?, max: Int): List<String> {
        if (node == null || !node.isArray || node.size() > max) throw Malformed()
        val values = (0 until node.size()).map { i -> node.get(i).also { if (!it.isString) throw Malformed() }.asString() }
        if (values.toSet().size != values.size) throw Malformed()
        return values
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
