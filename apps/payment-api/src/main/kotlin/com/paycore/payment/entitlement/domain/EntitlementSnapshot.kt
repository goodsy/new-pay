package com.paycore.payment.entitlement.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.HexFormat

/*
 * PAYMENT 쪽 권리 Snapshot(MASTER-SNAPSHOT-001 명세 §3, §5). MASTER가 발행한 통합 계약(schemaVersion 1)을 PAYMENT 자체 타입으로 해석한다 —
 * MASTER 코드에 의존하지 않는다(앱 간 직접 의존 금지, 계약 일치는 golden 시험 S7). PAYMENT는 이 투영을 원본으로 취급하지 않는다(판정 입력).
 */

/** 해석하는 Snapshot 계약 버전. 다른 버전은 원문만 보존하고 판정에 쓰지 않는다(fail-closed, 명세 §3.5). */
const val SUPPORTED_SNAPSHOT_SCHEMA_VERSION: Int = 1

/** 계약 위반(구조·값 형식·hash 불일치). 수신 adapter는 4xx로 거절하고 저장하지 않는다(MASTER는 격리). 메시지에 입력값을 넣지 않는다. */
class SnapshotContractViolation(message: String) : RuntimeException(message, null, false, false)

enum class Lifecycle { ACTIVE, TERMINATED }
enum class AssignmentRole { PRIMARY, ADDITIONAL }
enum class AssignmentStatus { ACTIVE, SUSPENDED, ENDED }
enum class ActivationStatus { UNDER_REVIEW, ACTIVE, SUSPENDED, TERMINATED }
enum class SiteReviewStatus { PENDING, APPROVED, REJECTED, SUSPENDED }

data class Party(val businessLifecycle: Lifecycle, val merchantLifecycle: Lifecycle, val businessBlocked: Boolean, val merchantBlocked: Boolean)

data class SiteEntry(val siteId: String, val lifecycle: Lifecycle, val reviewStatus: SiteReviewStatus)

/** 시각 구간 [from, to). to가 없으면 끝이 없다. */
data class Interval(val from: Instant, val to: Instant?) {
    init {
        if (to != null && !to.isAfter(from)) throw SnapshotContractViolation("interval must end after it starts")
    }

    fun contains(at: Instant): Boolean = !at.isBefore(from) && (to == null || at.isBefore(to))
}

data class AssignmentEntry(val assignmentId: String, val siteId: String, val role: AssignmentRole, val status: AssignmentStatus, val period: Interval)

data class ContractWindowEntry(val siteId: String, val period: Interval)

data class ActivationEntry(val paymentMethod: String, val status: ActivationStatus)

data class SiteConfirmationEntry(val siteId: String, val paymentMethod: String)

data class CurrencyEntry(val currency: String, val minorUnit: Int, val period: Interval) {
    init {
        if (currency.length != 3 || !currency.all { it in 'A'..'Z' }) throw SnapshotContractViolation("currency must be 3 upper-case letters")
        if (minorUnit !in 0..MAX_MINOR_UNIT) throw SnapshotContractViolation("minorUnit out of range")
    }

    private companion object {
        const val MAX_MINOR_UNIT = 4
    }
}

data class AccountEntry(
    val mid: String,
    val lifecycle: Lifecycle,
    val blocked: Boolean,
    val issuingAgreementId: String,
    val serviceKind: String,
    val assignments: List<AssignmentEntry>,
    val contractWindows: List<ContractWindowEntry>,
    val activations: List<ActivationEntry>,
    val siteConfirmations: List<SiteConfirmationEntry>,
    val currencies: List<CurrencyEntry>,
)

/** lease 필드(전송 payload에만 있고 정규 내용·contentHash에는 없다). */
data class Lease(val issuedAt: Instant, val leaseSeq: Long, val leaseUntil: Instant) {
    init {
        if (leaseSeq < 0) throw SnapshotContractViolation("leaseSeq must not be negative")
        if (!leaseUntil.isAfter(issuedAt)) throw SnapshotContractViolation("lease must end after issuance")
    }
}

/**
 * 권리 내용(schemaVersion 1). [contentHash]는 받은 값이 아니라 **이 내용으로 다시 계산한 값**이다(정규 JSON — MASTER와 같은 규칙).
 * 구성 시 구조 불변식(revision ≥ 1, MID 1개 이상, 식별자 유일)을 검사한다.
 */
data class EntitlementContent(
    val merchantId: String,
    val businessId: String,
    val revision: Long,
    val party: Party,
    val sites: List<SiteEntry>,
    val accounts: List<AccountEntry>,
) {
    init {
        if (merchantId.isEmpty() || businessId.isEmpty()) throw SnapshotContractViolation("identifiers must not be empty")
        if (revision < 1) throw SnapshotContractViolation("revision starts at 1")
        if (accounts.isEmpty()) throw SnapshotContractViolation("a snapshot carries at least one MID")
        if (sites.map { it.siteId }.toSet().size != sites.size) throw SnapshotContractViolation("site ids are unique")
        if (accounts.map { it.mid }.toSet().size != accounts.size) throw SnapshotContractViolation("MIDs are unique")
        accounts.forEach { a ->
            if (a.activations.map { it.paymentMethod }.toSet().size != a.activations.size) throw SnapshotContractViolation("one activation per method")
        }
    }

    /** CP계약 §13 snapshotVersion(Payment에 보존). */
    val snapshotVersion: String get() = "es1:$merchantId:$revision"

    val canonical: String by lazy { CanonicalJson.write(contentMap()) }

    val contentHash: String by lazy { contentHashOf(canonical) }

    fun account(mid: String): AccountEntry? = accounts.firstOrNull { it.mid == mid }

    private fun contentMap(): Map<String, Any?> = mapOf(
        "merchantId" to merchantId,
        "businessId" to businessId,
        "revision" to revision,
        "party" to mapOf(
            "businessLifecycle" to party.businessLifecycle.name,
            "merchantLifecycle" to party.merchantLifecycle.name,
            "businessBlocked" to party.businessBlocked,
            "merchantBlocked" to party.merchantBlocked,
        ),
        "sites" to sites.sortedBy { it.siteId }.map { mapOf("siteId" to it.siteId, "lifecycle" to it.lifecycle.name, "reviewStatus" to it.reviewStatus.name) },
        "accounts" to accounts.sortedBy { it.mid }.map { a ->
            mapOf(
                "mid" to a.mid,
                "lifecycle" to a.lifecycle.name,
                "blocked" to a.blocked,
                "issuingAgreementId" to a.issuingAgreementId,
                "serviceKind" to a.serviceKind,
                "assignments" to a.assignments.sortedBy { it.assignmentId }.map {
                    mapOf(
                        "assignmentId" to it.assignmentId, "siteId" to it.siteId, "role" to it.role.name, "status" to it.status.name,
                        "effectiveFrom" to it.period.from, "effectiveTo" to it.period.to,
                    )
                },
                "contractWindows" to a.contractWindows.sortedWith(compareBy({ it.siteId }, { it.period.from })).map {
                    mapOf("siteId" to it.siteId, "from" to it.period.from, "to" to it.period.to)
                },
                "activations" to a.activations.sortedBy { it.paymentMethod }.map { mapOf("paymentMethod" to it.paymentMethod, "status" to it.status.name) },
                "siteConfirmations" to a.siteConfirmations.sortedWith(compareBy({ it.siteId }, { it.paymentMethod })).map {
                    mapOf("siteId" to it.siteId, "paymentMethod" to it.paymentMethod)
                },
                "currencies" to a.currencies.sortedWith(compareBy({ it.currency }, { it.period.from })).map {
                    mapOf("currency" to it.currency, "minorUnit" to it.minorUnit, "from" to it.period.from, "to" to it.period.to)
                },
            )
        },
    )

    companion object {
        fun contentHashOf(canonical: String): String =
            "ec1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))

        /**
         * JSON 값 트리(adapter가 Jackson 노드를 [JsonValue]로 옮긴다)에서 schemaVersion 1 내용을 해석한다. 모르는 enum 값·빠진 필드·형식 오류는
         * [SnapshotContractViolation]. 추가 필드는 무시하지 않고 거절한다 — 내용 hash가 우리가 모르는 필드를 덮지 않게(fail-closed).
         */
        fun fromJson(node: JsonValue, allowLeaseFields: Boolean): EntitlementContent {
            val o = node.obj("snapshot")
            val known = CONTENT_KEYS + if (allowLeaseFields) LEASE_KEYS else emptySet()
            o.requireOnly(known, "snapshot")
            return EntitlementContent(
                merchantId = o.string("merchantId"),
                businessId = o.string("businessId"),
                revision = o.long("revision"),
                party = o.field("party").obj("party").let { p ->
                    p.requireOnly(setOf("businessLifecycle", "merchantLifecycle", "businessBlocked", "merchantBlocked"), "party")
                    Party(p.enum("businessLifecycle"), p.enum("merchantLifecycle"), p.bool("businessBlocked"), p.bool("merchantBlocked"))
                },
                sites = o.array("sites").map { s ->
                    s.obj("site").let {
                        it.requireOnly(setOf("siteId", "lifecycle", "reviewStatus"), "site")
                        SiteEntry(it.string("siteId"), it.enum("lifecycle"), it.enum("reviewStatus"))
                    }
                },
                accounts = o.array("accounts").map { account(it.obj("account")) },
            )
        }

        fun leaseFromJson(node: JsonValue): Lease {
            val o = node.obj("snapshot")
            return Lease(o.instant("issuedAt"), o.long("leaseSeq"), o.instant("leaseUntil"))
        }

        private fun account(a: JsonValue.JObject): AccountEntry {
            a.requireOnly(
                setOf("mid", "lifecycle", "blocked", "issuingAgreementId", "serviceKind", "assignments", "contractWindows", "activations",
                    "siteConfirmations", "currencies"),
                "account",
            )
            return AccountEntry(
                mid = a.string("mid"),
                lifecycle = a.enum("lifecycle"),
                blocked = a.bool("blocked"),
                issuingAgreementId = a.string("issuingAgreementId"),
                serviceKind = a.string("serviceKind"),
                assignments = a.array("assignments").map { v ->
                    v.obj("assignment").let {
                        it.requireOnly(setOf("assignmentId", "siteId", "role", "status", "effectiveFrom", "effectiveTo"), "assignment")
                        AssignmentEntry(it.string("assignmentId"), it.string("siteId"), it.enum("role"), it.enum("status"),
                            Interval(it.instant("effectiveFrom"), it.instantOrNull("effectiveTo")))
                    }
                },
                contractWindows = a.array("contractWindows").map { v ->
                    v.obj("contractWindow").let {
                        it.requireOnly(setOf("siteId", "from", "to"), "contractWindow")
                        ContractWindowEntry(it.string("siteId"), Interval(it.instant("from"), it.instantOrNull("to")))
                    }
                },
                activations = a.array("activations").map { v ->
                    v.obj("activation").let {
                        it.requireOnly(setOf("paymentMethod", "status"), "activation")
                        ActivationEntry(it.string("paymentMethod"), it.enum("status"))
                    }
                },
                siteConfirmations = a.array("siteConfirmations").map { v ->
                    v.obj("siteConfirmation").let {
                        it.requireOnly(setOf("siteId", "paymentMethod"), "siteConfirmation")
                        SiteConfirmationEntry(it.string("siteId"), it.string("paymentMethod"))
                    }
                },
                currencies = a.array("currencies").map { v ->
                    v.obj("currency").let {
                        it.requireOnly(setOf("currency", "minorUnit", "from", "to"), "currency")
                        val minor = it.long("minorUnit")
                        if (minor !in 0L..Int.MAX_VALUE.toLong()) throw SnapshotContractViolation("minorUnit out of range")
                        CurrencyEntry(it.string("currency"), minor.toInt(), Interval(it.instant("from"), it.instantOrNull("to")))
                    }
                },
            )
        }

        private val CONTENT_KEYS = setOf("merchantId", "businessId", "revision", "party", "sites", "accounts")
        private val LEASE_KEYS = setOf("contentHash", "issuedAt", "leaseSeq", "leaseUntil")
    }
}

/** 받은 Snapshot 한 건(지원 버전이면 내용·lease·받은 hash, 아니면 원문만). */
data class IncomingSnapshot(
    val eventId: String,
    val merchantId: String,
    val revision: Long,
    val schemaVersion: Int,
    val content: EntitlementContent?,
    val declaredContentHash: String?,
    val lease: Lease?,
) {
    init {
        if (revision < 1) throw SnapshotContractViolation("revision starts at 1")
        val supported = schemaVersion == SUPPORTED_SNAPSHOT_SCHEMA_VERSION
        if (supported && (content == null || lease == null || declaredContentHash == null)) {
            throw SnapshotContractViolation("a supported snapshot carries content, lease and content hash")
        }
        if (content != null) {
            if (content.merchantId != merchantId || content.revision != revision) {
                throw SnapshotContractViolation("envelope aggregate does not match the snapshot")
            }
            if (content.contentHash != declaredContentHash) throw SnapshotContractViolation("content hash does not match the content")
        }
    }

    val isSupported: Boolean get() = content != null
}

/** 최소 JSON 값 트리(domain은 Jackson을 모른다 — adapter가 옮겨 준다). */
sealed interface JsonValue {
    data class JObject(val fields: Map<String, JsonValue>) : JsonValue
    data class JArray(val items: List<JsonValue>) : JsonValue
    data class JString(val value: String) : JsonValue
    data class JNumber(val value: Long) : JsonValue
    data class JBool(val value: Boolean) : JsonValue
    data object JNull : JsonValue

    fun obj(what: String): JObject = this as? JObject ?: throw SnapshotContractViolation("$what must be an object")
}

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun JsonValue.JObject.field(name: String): JsonValue = fields[name] ?: throw SnapshotContractViolation("missing field $name")

private fun JsonValue.JObject.requireOnly(known: Set<String>, what: String) {
    if (!known.containsAll(fields.keys)) throw SnapshotContractViolation("unexpected field in $what")
}

private fun JsonValue.JObject.string(name: String): String =
    (field(name) as? JsonValue.JString)?.value?.takeIf { it.isNotEmpty() } ?: throw SnapshotContractViolation("$name must be a non-empty string")

private fun JsonValue.JObject.long(name: String): Long = (field(name) as? JsonValue.JNumber)?.value ?: throw SnapshotContractViolation("$name must be an integer")

private fun JsonValue.JObject.bool(name: String): Boolean = (field(name) as? JsonValue.JBool)?.value ?: throw SnapshotContractViolation("$name must be a boolean")

private fun JsonValue.JObject.array(name: String): List<JsonValue> = (field(name) as? JsonValue.JArray)?.items ?: throw SnapshotContractViolation("$name must be an array")

private inline fun <reified E : Enum<E>> JsonValue.JObject.enum(name: String): E {
    val raw = string(name)
    return enumValues<E>().firstOrNull { it.name == raw } ?: throw SnapshotContractViolation("$name has an unknown value")
}

private fun JsonValue.JObject.instant(name: String): Instant = parseInstant(string(name), name)

private fun JsonValue.JObject.instantOrNull(name: String): Instant? = when (val v = field(name)) {
    JsonValue.JNull -> null
    is JsonValue.JString -> parseInstant(v.value, name)
    else -> throw SnapshotContractViolation("$name must be a timestamp or null")
}

/** 정규 형식(UTC ms `…T…:….SSSZ`)만 받는다 — 다른 표기는 같은 시각이라도 정규 내용 hash가 달라진다. */
private fun parseInstant(raw: String, name: String): Instant = try {
    Instant.from(TIMESTAMP.parse(raw)).also { if (TIMESTAMP.format(it) != raw) throw SnapshotContractViolation("$name must be canonical UTC milliseconds") }
} catch (e: DateTimeParseException) {
    throw SnapshotContractViolation("$name must be a UTC millisecond timestamp")
}

/**
 * 정규 JSON 작성기 — MASTER `CanonicalJson`과 같은 규칙(키 사전순, 공백 없음, UTC ms 시각, `"`·`\`·U+0000–U+001F만 escape). 두 구현이 같은 결과를
 * 내는지는 양쪽 시험 자원의 golden 파일(같은 내용)로 확인한다.
 */
object CanonicalJson {
    fun write(value: Map<String, Any?>): String = StringBuilder().also { append(it, value) }.toString()

    fun timestamp(at: Instant): String = TIMESTAMP.format(at)

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Instant -> string(out, timestamp(value))
            is Map<*, *> -> {
                out.append('{')
                value.entries.map { (k, v) -> (k as String) to v }.sortedBy { it.first }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k)
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported canonical JSON value type: ${value::class.simpleName}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
