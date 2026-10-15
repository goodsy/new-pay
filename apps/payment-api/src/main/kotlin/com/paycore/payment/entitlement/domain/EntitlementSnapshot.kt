package com.paycore.payment.entitlement.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.HexFormat

/*
 * PAYMENT 쪽 권리 Snapshot(MASTER-SNAPSHOT-001 명세 §3, §5). MASTER가 발행한 통합 계약(schemaVersion 1·2 — MASTER-SNAPSHOT-002)을 PAYMENT 자체 타입으로 해석한다 —
 * MASTER 코드에 의존하지 않는다(앱 간 직접 의존 금지, 계약 일치는 golden 시험 S7). PAYMENT는 이 투영을 원본으로 취급하지 않는다(판정 입력).
 */

/**
 * 해석하는 Snapshot 계약 버전. 1 = 권리(MASTER-SNAPSHOT-001), 2 = 1 + 승인 시작 조건(MASTER-SNAPSHOT-002 명세 §4). 다른 버전은 원문만 보존하고
 * 판정에 쓰지 않는다(fail-closed, 명세 §3.5). v1 head로는 결제 생성 판정만 하고 승인 시작은 TERMS_NOT_PROPAGATED(fail-closed).
 */
val SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS: Set<Int> = setOf(1, 2)

/** 승인 시작 조건을 싣는 첫 schemaVersion. */
const val TERMS_SNAPSHOT_SCHEMA_VERSION: Int = 2

fun isSupportedSnapshotSchema(schemaVersion: Int?): Boolean = schemaVersion in SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS

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

    fun overlaps(other: Interval): Boolean = (other.to == null || from.isBefore(other.to)) && (to == null || other.from.isBefore(to))
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

/** 한도 값(명시 무제한만 무제한, D-AUTH-006). */
sealed interface LimitBoundEntry {
    data object Unlimited : LimitBoundEntry

    data class Limited(val amountMinor: Long) : LimitBoundEntry {
        init {
            if (amountMinor < 0) throw SnapshotContractViolation("limit amount must not be negative")
        }
    }
}

enum class LimitScopeKind { CSA_DEFAULT, MID_OVERRIDE }

/** 한도 정책 한 건(MASTER 원본 행 = 버전). [agreementId]는 CSA_DEFAULT에만. */
data class LimitPolicyEntry(
    val policyId: String,
    val scope: LimitScopeKind,
    val agreementId: String?,
    val currency: String,
    val perTransaction: LimitBoundEntry,
    val daily: LimitBoundEntry,
    val period: Interval,
) {
    init {
        if ((scope == LimitScopeKind.CSA_DEFAULT) != (agreementId != null)) throw SnapshotContractViolation("only a CSA default carries its agreement")
    }
}

enum class PolicyKind { FEE_SCHEDULE, SETTLEMENT_POLICY }

/** 정책 내용 상태(D-MS2-003). 둘 다 "내용 없는 버전"(TEST/Simulator 전용, LIVE Gate 차단 대상). */
enum class PolicyContentStatus { UNDEFINED, DEMO }

data class PolicyAssignmentEntry(
    val assignmentId: String,
    val kind: PolicyKind,
    val agreementId: String,
    val currency: String,
    val policyVersionId: String,
    val policyCode: String,
    val versionNo: Long,
    val contentStatus: PolicyContentStatus,
    val period: Interval,
) {
    init {
        if (versionNo < 1) throw SnapshotContractViolation("policy version numbers start at 1")
    }
}

/**
 * 카드사(매입사) 개통 한 건(D-MS2-004). [institutionMerchantRef]는 내부 식별자 등급(D-MS2-005) — [toString]에 원문을 넣지 않는다(로그·예외에 새지 않게).
 */
data class CardRouteEntry(
    val activationId: String,
    val paymentMethod: String,
    val acquirerCode: String,
    val routeRef: String,
    val institutionMerchantRef: String,
    val status: ActivationStatus,
) {
    override fun toString(): String =
        "CardRouteEntry(activationId=$activationId, paymentMethod=$paymentMethod, acquirerCode=$acquirerCode, routeRef=$routeRef, status=$status)"
}

/** schemaVersion 2의 MID별 승인 시작 조건(MASTER-SNAPSHOT-002 §4.1). */
data class AuthorizationTermsEntry(
    val limitPolicies: List<LimitPolicyEntry>,
    val policyAssignments: List<PolicyAssignmentEntry>,
    val cardRoutes: List<CardRouteEntry>,
)

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
    /** schemaVersion 2에서만 있다(1이면 null — 승인 시작 조건 미전파). */
    val terms: AuthorizationTermsEntry? = null,
) {
    init {
        terms?.let { checkTerms(it) }
    }

    /**
     * 수신 구조 불변식(명세 §4.1 — MASTER 결과를 믿지 않고 다시 확인, 위반은 4xx·저장 없음): 같은 (범위, 대상, 통화) 한도 구간 중첩 없음, CSA 기본의
     * CSA = 발급 CSA, 같은 (종류, 통화) Assignment 중첩 없음·CSA = 발급 CSA, 같은 (수단, 매입사) 카드사 개통 하나, 식별자 유일.
     */
    private fun checkTerms(t: AuthorizationTermsEntry) {
        if (t.limitPolicies.map { it.policyId }.toSet().size != t.limitPolicies.size) throw SnapshotContractViolation("limit policy ids are unique")
        t.limitPolicies.forEach { p ->
            if (p.scope == LimitScopeKind.CSA_DEFAULT && p.agreementId != issuingAgreementId) {
                throw SnapshotContractViolation("a CSA default limit belongs to the issuing agreement")
            }
        }
        t.limitPolicies.groupBy { it.scope to it.currency }.values.forEach { same ->
            if (overlapping(same.map { it.period })) throw SnapshotContractViolation("limit policies of the same level overlap")
        }
        if (t.policyAssignments.map { it.assignmentId }.toSet().size != t.policyAssignments.size) throw SnapshotContractViolation("assignment ids are unique")
        if (t.policyAssignments.any { it.agreementId != issuingAgreementId }) throw SnapshotContractViolation("policy assignments belong to the issuing agreement")
        t.policyAssignments.groupBy { it.kind to it.currency }.values.forEach { same ->
            if (overlapping(same.map { it.period })) throw SnapshotContractViolation("policy assignments of the same kind overlap")
        }
        if (t.cardRoutes.map { it.activationId }.toSet().size != t.cardRoutes.size) throw SnapshotContractViolation("card route ids are unique")
        if (t.cardRoutes.map { it.paymentMethod to it.acquirerCode }.toSet().size != t.cardRoutes.size) {
            throw SnapshotContractViolation("one card route per method and acquirer")
        }
        if (t.cardRoutes.any { it.status == ActivationStatus.TERMINATED }) throw SnapshotContractViolation("terminated card routes are not carried")
    }

    private fun overlapping(periods: List<Interval>): Boolean = periods.indices.any { i ->
        (i + 1 until periods.size).any { j -> periods[i].overlaps(periods[j]) }
    }
}

/** lease 필드(전송 payload에만 있고 정규 내용·contentHash에는 없다). */
data class Lease(val issuedAt: Instant, val leaseSeq: Long, val leaseUntil: Instant) {
    init {
        if (leaseSeq < 0) throw SnapshotContractViolation("leaseSeq must not be negative")
        if (!leaseUntil.isAfter(issuedAt)) throw SnapshotContractViolation("lease must end after issuance")
    }
}

/**
 * 권리 내용(schemaVersion 1·2, 2는 MID별 승인 시작 조건 [AccountEntry.terms] 포함). [contentHash]는 받은 값이 아니라 **이 내용으로 다시 계산한 값**이다(정규 JSON — MASTER와 같은 규칙).
 * 구성 시 구조 불변식(revision ≥ 1, MID 1개 이상, 식별자 유일)을 검사한다.
 */
data class EntitlementContent(
    val merchantId: String,
    val businessId: String,
    val revision: Long,
    val party: Party,
    val sites: List<SiteEntry>,
    val accounts: List<AccountEntry>,
    /** 이 내용의 계약 버전(1 또는 2). 2이면 모든 MID에 승인 시작 조건이 있고, 1이면 없다. */
    val schemaVersion: Int = 1,
) {
    init {
        if (!isSupportedSnapshotSchema(schemaVersion)) throw SnapshotContractViolation("unsupported snapshot schema version")
        if (accounts.any { (it.terms != null) != (schemaVersion >= TERMS_SNAPSHOT_SCHEMA_VERSION) }) {
            throw SnapshotContractViolation("authorization terms are carried exactly from schema version 2")
        }
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
            mapOf<String, Any?>(
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
            ) + (a.terms?.let(::termsMap) ?: emptyMap())
        },
    )

    private fun termsMap(t: AuthorizationTermsEntry): Map<String, Any?> = mapOf(
        "limitPolicies" to t.limitPolicies.sortedBy { it.policyId }.map {
            mapOf(
                "policyId" to it.policyId, "scope" to it.scope.name, "agreementId" to it.agreementId, "currency" to it.currency,
                "perTransaction" to boundMap(it.perTransaction), "daily" to boundMap(it.daily), "from" to it.period.from, "to" to it.period.to,
            )
        },
        "policyAssignments" to t.policyAssignments.sortedBy { it.assignmentId }.map {
            mapOf(
                "assignmentId" to it.assignmentId, "kind" to it.kind.name, "agreementId" to it.agreementId, "currency" to it.currency,
                "policyVersionId" to it.policyVersionId, "policyCode" to it.policyCode, "versionNo" to it.versionNo,
                "contentStatus" to it.contentStatus.name, "from" to it.period.from, "to" to it.period.to,
            )
        },
        "cardRoutes" to t.cardRoutes.sortedBy { it.activationId }.map {
            mapOf(
                "activationId" to it.activationId, "paymentMethod" to it.paymentMethod, "acquirerCode" to it.acquirerCode, "routeRef" to it.routeRef,
                "institutionMerchantRef" to it.institutionMerchantRef, "status" to it.status.name,
            )
        },
    )

    private fun boundMap(b: LimitBoundEntry): Map<String, Any?> = when (b) {
        LimitBoundEntry.Unlimited -> mapOf("kind" to "UNLIMITED", "amountMinor" to null)
        is LimitBoundEntry.Limited -> mapOf("kind" to "LIMITED", "amountMinor" to b.amountMinor)
    }

    companion object {
        fun contentHashOf(canonical: String): String =
            "ec1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))

        /**
         * JSON 값 트리(adapter가 Jackson 노드를 [JsonValue]로 옮긴다)에서 [schemaVersion](1·2) 내용을 해석한다. 모르는 enum 값·빠진 필드·형식 오류는
         * [SnapshotContractViolation]. 추가 필드는 무시하지 않고 거절한다 — 내용 hash가 우리가 모르는 필드를 덮지 않게(fail-closed). v1 내용에 v2
         * 필드가 있거나 v2 내용에 v2 필드가 없어도 계약 위반이다.
         */
        fun fromJson(node: JsonValue, schemaVersion: Int, allowLeaseFields: Boolean): EntitlementContent {
            if (!isSupportedSnapshotSchema(schemaVersion)) throw SnapshotContractViolation("unsupported snapshot schema version")
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
                accounts = o.array("accounts").map { account(it.obj("account"), schemaVersion) },
                schemaVersion = schemaVersion,
            )
        }

        fun leaseFromJson(node: JsonValue): Lease {
            val o = node.obj("snapshot")
            return Lease(o.instant("issuedAt"), o.long("leaseSeq"), o.instant("leaseUntil"))
        }

        private val ACCOUNT_KEYS_V1 = setOf(
            "mid", "lifecycle", "blocked", "issuingAgreementId", "serviceKind", "assignments", "contractWindows", "activations", "siteConfirmations",
            "currencies",
        )
        private val TERMS_KEYS = setOf("limitPolicies", "policyAssignments", "cardRoutes")

        private fun account(a: JsonValue.JObject, schemaVersion: Int): AccountEntry {
            val withTerms = schemaVersion >= TERMS_SNAPSHOT_SCHEMA_VERSION
            a.requireOnly(if (withTerms) ACCOUNT_KEYS_V1 + TERMS_KEYS else ACCOUNT_KEYS_V1, "account")
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
                terms = if (withTerms) terms(a) else null,
            )
        }

        private fun terms(a: JsonValue.JObject) = AuthorizationTermsEntry(
            limitPolicies = a.array("limitPolicies").map { v ->
                v.obj("limitPolicy").let {
                    it.requireOnly(setOf("policyId", "scope", "agreementId", "currency", "perTransaction", "daily", "from", "to"), "limitPolicy")
                    LimitPolicyEntry(
                        it.string("policyId"), it.enum("scope"), it.stringOrNull("agreementId"), currencyCode(it.string("currency")),
                        bound(it.field("perTransaction").obj("perTransaction")), bound(it.field("daily").obj("daily")),
                        Interval(it.instant("from"), it.instantOrNull("to")),
                    )
                }
            },
            policyAssignments = a.array("policyAssignments").map { v ->
                v.obj("policyAssignment").let {
                    it.requireOnly(
                        setOf("assignmentId", "kind", "agreementId", "currency", "policyVersionId", "policyCode", "versionNo", "contentStatus", "from", "to"),
                        "policyAssignment",
                    )
                    PolicyAssignmentEntry(
                        it.string("assignmentId"), it.enum("kind"), it.string("agreementId"), currencyCode(it.string("currency")), it.string("policyVersionId"),
                        it.string("policyCode"), it.long("versionNo"), it.enum("contentStatus"), Interval(it.instant("from"), it.instantOrNull("to")),
                    )
                }
            },
            cardRoutes = a.array("cardRoutes").map { v ->
                v.obj("cardRoute").let {
                    it.requireOnly(setOf("activationId", "paymentMethod", "acquirerCode", "routeRef", "institutionMerchantRef", "status"), "cardRoute")
                    CardRouteEntry(
                        it.string("activationId"), it.string("paymentMethod"), it.string("acquirerCode"), it.string("routeRef"),
                        it.string("institutionMerchantRef"), it.enum("status"),
                    )
                }
            },
        )

        private fun bound(o: JsonValue.JObject): LimitBoundEntry {
            o.requireOnly(setOf("kind", "amountMinor"), "limit")
            return when (o.string("kind")) {
                "UNLIMITED" -> if (o.field("amountMinor") == JsonValue.JNull) LimitBoundEntry.Unlimited else throw SnapshotContractViolation("unlimited has no amount")
                "LIMITED" -> LimitBoundEntry.Limited(o.long("amountMinor"))
                else -> throw SnapshotContractViolation("limit kind has an unknown value")
            }
        }

        private fun currencyCode(value: String): String {
            if (value.length != 3 || !value.all { it in 'A'..'Z' }) throw SnapshotContractViolation("currency must be 3 upper-case letters")
            return value
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
        val supported = isSupportedSnapshotSchema(schemaVersion)
        if (supported && (content == null || lease == null || declaredContentHash == null)) {
            throw SnapshotContractViolation("a supported snapshot carries content, lease and content hash")
        }
        if (content != null) {
            if (content.merchantId != merchantId || content.revision != revision || content.schemaVersion != schemaVersion) {
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

private fun JsonValue.JObject.stringOrNull(name: String): String? = when (val v = field(name)) {
    JsonValue.JNull -> null
    is JsonValue.JString -> v.value.takeIf { it.isNotEmpty() } ?: throw SnapshotContractViolation("$name must be a non-empty string or null")
    else -> throw SnapshotContractViolation("$name must be a string or null")
}

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
