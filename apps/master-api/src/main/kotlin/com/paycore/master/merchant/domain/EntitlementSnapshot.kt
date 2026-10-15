package com.paycore.master.merchant.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/*
 * 권리 Snapshot(MASTER-SNAPSHOT-001 명세 §3). Merchant 하나의 **전체 권리 상태**를 버전(entitlement_revision)·lease와 함께 PAYMENT에 보낸다.
 * 통합 계약 schemaVersion 2(1 + 승인 시작 조건, MASTER-SNAPSHOT-002)이며 Domain Entity 직렬화가 아니다(A03). 판정은 PAYMENT가 한다(허용 §4.1 마지막 문단) — MASTER는 근거만 싣는다.
 *
 * 내용(content)은 **MASTER 원본 상태만의 함수**다: 발행 시각으로 지난 구간을 걸러내지 않는다(명세 §3.1 "to > issuedAt만" 문구와의 편차 —
 * 걸러내면 같은 revision의 lease 갱신이 다른 내용·다른 contentHash가 되어 PAYMENT가 INTEGRITY_CONFLICT로 막는다). 시각 경계 변화(n-5)는
 * 절대 시각 구간을 그대로 싣고 PAYMENT가 판정 시각으로 계산한다.
 */

/**
 * Snapshot 통합 계약 버전(봉투 schemaVersion). 2 = 1 + 승인 시작 조건(MASTER-SNAPSHOT-002 명세 §4: `accounts[].limitPolicies·policyAssignments·
 * cardRoutes`). MASTER는 현재 버전만 조립한다. PAYMENT는 1·2를 해석한다(배포 순서: PAYMENT 먼저 — 명세 §5.4).
 */
const val ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION: Int = 2

/** 발행 우선순위(명세 §3.4-1): revision이 바뀐 발행(변경)은 HIGH, 같은 revision의 lease 갱신은 LOW. 값은 Outbox priority 컬럼(큰 값 먼저). */
enum class SnapshotPriority(val value: Int) { LOW(0), HIGH(10) }

data class PartyState(
    val businessLifecycle: Lifecycle,
    val merchantLifecycle: Lifecycle,
    val businessBlocked: Boolean,
    val merchantBlocked: Boolean,
)

data class SiteState(val siteId: MerchantSiteId, val lifecycle: Lifecycle, val reviewStatus: SiteReviewStatus)

data class AssignmentState(
    val assignmentId: SiteAssignmentId,
    val siteId: MerchantSiteId,
    val role: SiteAssignmentRole,
    val status: SiteAssignmentStatus,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
)

/** 발급 계약의 적용 Revision(D-MD-011)이 [siteId]를 Scope로 포함하는 시각 구간 [from, to). */
data class ContractWindow(val siteId: MerchantSiteId, val from: Instant, val to: Instant?) {
    init {
        require(to == null || to.isAfter(from)) { "contract window must end after it starts" }
    }
}

data class ActivationState(val paymentMethod: PaymentMethod, val status: ActivationStatus)

data class SiteConfirmationState(val siteId: MerchantSiteId, val paymentMethod: PaymentMethod)

data class CurrencyState(val currency: CurrencyCode, val minorUnit: Int, val from: Instant, val to: Instant?)

/** 한도 정책 한 건(명세 §4.1). [agreementId]는 CSA_DEFAULT에만(= 발급 CSA), MID_OVERRIDE는 null. */
data class LimitPolicyState(
    val policyId: TransactionLimitPolicyId,
    val scope: LimitScopeKind,
    val agreementId: ContractServiceAgreementId?,
    val currency: CurrencyCode,
    val perTransaction: LimitValue,
    val daily: LimitValue,
    val from: Instant,
    val to: Instant?,
) {
    init {
        require((scope == LimitScopeKind.CSA_DEFAULT) == (agreementId != null)) { "only a CSA default carries its agreement" }
        require(to == null || to.isAfter(from)) { "limit policy must end after it starts" }
    }
}

/** 정책 Assignment 한 건(대상 정책 버전의 식별 정보 포함, 명세 §4.1). */
data class PolicyAssignmentState(
    val assignmentId: PolicyAssignmentId,
    val kind: PolicyKind,
    val agreementId: ContractServiceAgreementId,
    val currency: CurrencyCode,
    val policyVersionId: PolicyVersionId,
    val policyCode: PolicyCode,
    val versionNo: Int,
    val contentStatus: PolicyContentStatus,
    val from: Instant,
    val to: Instant?,
)

/** 종료 아닌 카드사(매입사) 개통 한 건(명세 §4.1, D-MS2-004·005). */
data class CardRouteState(
    val activationId: CardRouteActivationId,
    val paymentMethod: PaymentMethod,
    val acquirerCode: AcquirerCode,
    val routeRef: RouteRef,
    val institutionMerchantRef: InstitutionMerchantRef,
    val status: ActivationStatus,
)

data class AccountState(
    val mid: MerchantAccountId,
    val lifecycle: Lifecycle,
    val blocked: Boolean,
    val issuingAgreementId: ContractServiceAgreementId,
    val serviceKind: ServiceKind,
    val assignments: List<AssignmentState>,
    val contractWindows: List<ContractWindow>,
    val activations: List<ActivationState>,
    val siteConfirmations: List<SiteConfirmationState>,
    val currencies: List<CurrencyState>,
    val limitPolicies: List<LimitPolicyState>,
    val policyAssignments: List<PolicyAssignmentState>,
    val cardRoutes: List<CardRouteState>,
)

/** 한 번의 Snapshot 발행. [revision] ≥ 1, lease는 `issuedAt < leaseUntil`. */
data class EntitlementSnapshot(
    val merchantId: MerchantId,
    val businessId: BusinessId,
    val revision: Long,
    val issuedAt: Instant,
    val leaseSeq: Long,
    val leaseUntil: Instant,
    val party: PartyState,
    val sites: List<SiteState>,
    val accounts: List<AccountState>,
) {
    init {
        require(revision >= 1) { "snapshot revision starts at 1" }
        require(leaseSeq >= 0) { "leaseSeq must not be negative" }
        require(leaseUntil.isAfter(issuedAt)) { "lease must end after issuance" }
        require(accounts.isNotEmpty()) { "a snapshot is issued only for a merchant with at least one MID" }
    }

    /** PAYMENT Payment에 보존되는 판정 근거 버전(CP계약 §13 snapshotVersion, 64자 이하). */
    val snapshotVersion: String get() = "es1:${merchantId.value}:$revision"

    /** lease·발행 필드를 뺀 권리 내용의 정규 JSON(키 정렬, UTC ms 시각, 배열 정렬). */
    val canonicalContent: String by lazy { CanonicalJson.write(contentMap()) }

    /** `ec1:` + SHA-256(정규 내용). 같은 revision의 갱신 발행은 같은 값이어야 한다(PAYMENT 무결성 비교, 명세 §3.5). */
    val contentHash: String by lazy { contentHashOf(canonicalContent) }

    /** 전송 payload(내용 + contentHash + lease 필드)의 정규 JSON. */
    fun wirePayload(): String = CanonicalJson.write(
        contentMap() + mapOf("contentHash" to contentHash, "issuedAt" to issuedAt, "leaseSeq" to leaseSeq, "leaseUntil" to leaseUntil),
    )

    private fun contentMap(): Map<String, Any?> = mapOf(
        "merchantId" to merchantId.value,
        "businessId" to businessId.value,
        "revision" to revision,
        "party" to mapOf(
            "businessLifecycle" to party.businessLifecycle.name,
            "merchantLifecycle" to party.merchantLifecycle.name,
            "businessBlocked" to party.businessBlocked,
            "merchantBlocked" to party.merchantBlocked,
        ),
        "sites" to sites.sortedBy { it.siteId.value }.map {
            mapOf("siteId" to it.siteId.value, "lifecycle" to it.lifecycle.name, "reviewStatus" to it.reviewStatus.name)
        },
        "accounts" to accounts.sortedBy { it.mid.value }.map { a ->
            mapOf(
                "mid" to a.mid.value,
                "lifecycle" to a.lifecycle.name,
                "blocked" to a.blocked,
                "issuingAgreementId" to a.issuingAgreementId.value,
                "serviceKind" to a.serviceKind.name,
                "assignments" to a.assignments.sortedBy { it.assignmentId.value }.map {
                    mapOf(
                        "assignmentId" to it.assignmentId.value, "siteId" to it.siteId.value, "role" to it.role.name, "status" to it.status.name,
                        "effectiveFrom" to it.effectiveFrom, "effectiveTo" to it.effectiveTo,
                    )
                },
                "contractWindows" to a.contractWindows.sortedWith(compareBy({ it.siteId.value }, { it.from })).map {
                    mapOf("siteId" to it.siteId.value, "from" to it.from, "to" to it.to)
                },
                "activations" to a.activations.sortedBy { it.paymentMethod.name }.map {
                    mapOf("paymentMethod" to it.paymentMethod.name, "status" to it.status.name)
                },
                "siteConfirmations" to a.siteConfirmations.sortedWith(compareBy({ it.siteId.value }, { it.paymentMethod.name })).map {
                    mapOf("siteId" to it.siteId.value, "paymentMethod" to it.paymentMethod.name)
                },
                "currencies" to a.currencies.sortedWith(compareBy({ it.currency.value }, { it.from })).map {
                    mapOf("currency" to it.currency.value, "minorUnit" to it.minorUnit, "from" to it.from, "to" to it.to)
                },
                "limitPolicies" to a.limitPolicies.sortedBy { it.policyId.value }.map {
                    mapOf(
                        "policyId" to it.policyId.value, "scope" to it.scope.name, "agreementId" to it.agreementId?.value, "currency" to it.currency.value,
                        "perTransaction" to limitMap(it.perTransaction), "daily" to limitMap(it.daily), "from" to it.from, "to" to it.to,
                    )
                },
                "policyAssignments" to a.policyAssignments.sortedBy { it.assignmentId.value }.map {
                    mapOf(
                        "assignmentId" to it.assignmentId.value, "kind" to it.kind.name, "agreementId" to it.agreementId.value,
                        "currency" to it.currency.value, "policyVersionId" to it.policyVersionId.value, "policyCode" to it.policyCode.value,
                        "versionNo" to it.versionNo, "contentStatus" to it.contentStatus.name, "from" to it.from, "to" to it.to,
                    )
                },
                "cardRoutes" to a.cardRoutes.sortedBy { it.activationId.value }.map {
                    mapOf(
                        "activationId" to it.activationId.value, "paymentMethod" to it.paymentMethod.name, "acquirerCode" to it.acquirerCode.value,
                        "routeRef" to it.routeRef.value, "institutionMerchantRef" to it.institutionMerchantRef.value, "status" to it.status.name,
                    )
                },
            )
        },
    )

    private fun limitMap(v: LimitValue): Map<String, Any?> = when (v) {
        LimitValue.Unlimited -> mapOf("kind" to "UNLIMITED", "amountMinor" to null)
        is LimitValue.Limited -> mapOf("kind" to "LIMITED", "amountMinor" to v.amountMinor)
    }

    companion object {
        fun contentHashOf(canonicalContent: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalContent.toByteArray(StandardCharsets.UTF_8))
            return "ec1:" + HexFormat.of().formatHex(digest)
        }
    }
}

/**
 * 정규 JSON 작성기(관례, 명세 §10 contentHash): 객체 키는 사전순(UTF-16 code unit 순, 키는 ASCII), 공백 없음, 시각은 UTC ms ISO-8601
 * `uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`, 정수는 10진, null은 `null`, 문자열은 `"`·`\`·제어문자(U+0000–U+001F)만 escape(`\uXXXX` 소문자 hex)하고
 * 나머지는 그대로(UTF-8). PAYMENT는 같은 규칙으로 다시 계산해 비교한다(계약 golden — S7).
 */
object CanonicalJson {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

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

/**
 * 계약 적용 구간 계산(명세 §3.1 contractWindows, D-MD-011·015, n-5): 시각 t의 적용 Revision = t를 포함하는 승인 Revision 중 번호 최대.
 * 적용 여부는 승인 Revision 기간 경계에서만 바뀌므로 경계로 나눈 각 구간의 시작 시각에서 판정하고, Scope가 [site]를 포함하는 구간을 이어 붙인다.
 * 철회 기록(MASTER-LIFECYCLE-001)이 생기면 그 Revision을 제외하도록 이 함수를 확장한다.
 */
object ContractWindows {
    fun of(contract: Contract, site: MerchantSite): List<ContractWindow> {
        val approved = contract.revisions.filter { it.isApproved }
        val boundaries = approved.flatMap { listOfNotNull(it.effectivePeriod.from, it.effectivePeriod.to) }.distinct().sorted()
        val windows = mutableListOf<ContractWindow>()
        boundaries.forEachIndexed { i, start ->
            val end = boundaries.getOrNull(i + 1)
            val applicable = contract.applicableRevisionAt(start)
            if (applicable != null && applicable.scope.includes(site)) {
                val last = windows.lastOrNull()
                if (last != null && last.to == start) {
                    windows[windows.size - 1] = ContractWindow(site.id, last.from, end)
                } else {
                    windows += ContractWindow(site.id, start, end)
                }
            }
        }
        return windows
    }
}

/** Snapshot 조립 입력: 같은 MASTER TX에서 읽은 Merchant 전체 상태(MID 1개 이상). */
data class EntitlementSource(
    val business: Business,
    val merchant: Merchant,
    val sites: List<MerchantSite>,
    val siteReviews: Map<MerchantSiteId, SiteReviewStatus>,
    val accounts: List<AccountSource>,
)

/**
 * MID 하나의 조립 입력. 승인 시작 조건(MASTER-SNAPSHOT-002): [limitPolicies] = 그 Merchant의 한도 정책 전체(조립이 발급 CSA 기본 + 이 MID
 * Override만 고른다), [policyAssignments] = 이 MID의 Assignment, [policyVersions] = 그 Assignment가 가리키는 버전, [cardRoutes] = 이 MID의 카드사 개통.
 */
data class AccountSource(
    val account: MerchantAccount,
    val issuingContract: Contract,
    val assignments: List<SiteAssignment>,
    val activations: List<PaymentMethodActivation>,
    val confirmations: List<SiteActivationConfirmation>,
    val currencies: List<CurrencyEnablement>,
    val limitPolicies: List<TransactionLimitPolicy> = emptyList(),
    val policyAssignments: List<PolicyAssignment> = emptyList(),
    val policyVersions: List<PolicyVersion> = emptyList(),
    val cardRoutes: List<CardRouteActivation> = emptyList(),
)

/**
 * Snapshot 조립(domain service, 명세 §4.6). 판정을 하지 않고 MASTER 원본 상태를 계약 모양으로 옮긴다:
 * - 개통: MID의 결제수단마다 TERMINATED가 아닌 Activation(하나 — DB UNIQUE). 없으면(전부 종료) 생략 → PAYMENT 미개통.
 * - Site 적용 확인: 그 현재 Activation에 묶이고 철회되지 않은 확인만(종료된 옛 Activation의 확인은 재개통에 이어지지 않는다).
 * - 차단 기록(D-MD-006)은 MASTER-LIFECYCLE-001 표가 생기기 전까지 false.
 * - 한도 정책: 이 MID 발급 CSA의 CSA 기본 + 이 MID의 Override(지난 구간 포함 — 내용은 원본 상태만의 함수, MS1 §15-1). 해석(Override 우선)은 PAYMENT.
 * - 카드사 개통: 종료 아닌 행만(종료 행은 판정에 쓸 일이 없다).
 */
object EntitlementSnapshotAssembler {
    fun assemble(source: EntitlementSource, issuedAt: Instant, leaseSeq: Long, leaseDuration: Duration): EntitlementSnapshot {
        val merchant = source.merchant
        require(source.business.id == merchant.businessId) { "business must be the merchant's business" }
        require(source.sites.all { it.merchantId == merchant.id }) { "sites must belong to the merchant" }
        require(source.accounts.all { it.account.merchantId == merchant.id }) { "MIDs must belong to the merchant" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "lease duration must be positive" }
        return EntitlementSnapshot(
            merchantId = merchant.id,
            businessId = merchant.businessId,
            revision = merchant.entitlementRevision,
            issuedAt = issuedAt,
            leaseSeq = leaseSeq,
            leaseUntil = issuedAt.plus(leaseDuration),
            party = PartyState(source.business.lifecycle, merchant.lifecycle, businessBlocked = false, merchantBlocked = false),
            sites = source.sites.map { SiteState(it.id, it.lifecycle, source.siteReviews[it.id] ?: SiteReviewStatus.PENDING) },
            accounts = source.accounts.map { accountState(it, source.sites) },
        )
    }

    private fun accountState(a: AccountSource, sites: List<MerchantSite>): AccountState {
        val account = a.account
        require(a.issuingContract.id == account.issuingBasis.contractId) { "issuing contract mismatch" }
        val agreement = checkNotNull(a.issuingContract.agreement(account.issuingBasis.agreementId)) { "issuing agreement must exist" }
        val current = a.activations.filter { it.merchantAccountId == account.id && !it.isTerminated }
        require(current.map { it.paymentMethod }.toSet().size == current.size) { "one non-terminated activation per method" }
        val currentIds = current.map { it.id }.toSet()
        return AccountState(
            mid = account.id,
            lifecycle = account.lifecycle,
            blocked = false,
            issuingAgreementId = agreement.id,
            serviceKind = agreement.serviceKind,
            assignments = a.assignments.map {
                AssignmentState(it.id, it.merchantSiteId, it.role, it.status, it.period.from, it.period.to)
            },
            contractWindows = sites.flatMap { ContractWindows.of(a.issuingContract, it) },
            activations = current.map { ActivationState(it.paymentMethod, it.status) },
            siteConfirmations = a.confirmations.filter { it.isValid && it.activationId in currentIds }
                .map { SiteConfirmationState(it.merchantSiteId, it.paymentMethod) }.distinct(),
            currencies = a.currencies.map { CurrencyState(it.unit.code, it.unit.minorUnit, it.period.from, it.period.to) },
            limitPolicies = a.limitPolicies.filter {
                when (val scope = it.scope) {
                    is LimitScope.CsaDefault -> scope.agreementId == agreement.id
                    is LimitScope.MidOverride -> scope.merchantAccountId == account.id
                }
            }.map {
                LimitPolicyState(
                    it.id, it.scope.kind, (it.scope as? LimitScope.CsaDefault)?.agreementId, it.currency, it.perTransaction, it.daily,
                    it.period.from, it.period.to,
                )
            },
            policyAssignments = a.policyAssignments.map { pa ->
                require(pa.merchantAccountId == account.id && pa.agreementId == agreement.id) { "policy assignments must be of this MID and its issuing CSA" }
                val version = checkNotNull(a.policyVersions.singleOrNull { it.id == pa.policyVersionId }) { "assigned policy version must be loaded" }
                PolicyAssignmentState(
                    pa.id, pa.kind, pa.agreementId, pa.currency, version.id, version.code, version.versionNo, version.contentStatus, pa.period.from, pa.period.to,
                )
            },
            cardRoutes = a.cardRoutes.filter { it.merchantAccountId == account.id && !it.isTerminated }.map {
                CardRouteState(it.id, it.paymentMethod, it.acquirerCode, it.routeRef, it.institutionMerchantRef, it.status)
            },
        )
    }
}
