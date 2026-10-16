package com.paycore.master.merchant.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
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
