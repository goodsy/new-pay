package com.paycore.payment.entitlement.domain

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * PAYMENT 권리 Snapshot Named Fixture(Test Fixture Policy, src/test 전용). 정상 Fixture는 **결제 가능한 권리 상태**(Site 심사 APPROVED, 유효
 * PRIMARY, 계약 구간, CARD ACTIVE + Site 확인, KRW 활성)이며, 거절 조건은 시험 본문에서 이름 있는 변경(`copy`)으로 하나씩 만든다 — 관련 값을
 * 독립 random으로 만들지 않는다. 식별자만 시험마다 새로 만든다(같은 컨텍스트의 다른 시험과 섞이지 않게).
 */
object SnapshotFixture {
    private val random = SecureRandom()
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    val t0: Instant = Instant.parse("2026-10-09T02:00:00.000Z")

    /** Demo lease(시험 값 — 운영값 아님). */
    val lease: Duration = Duration.ofSeconds(60)
    val skew: Duration = Duration.ofMillis(100)

    fun ulid(): String = (1..26).map { CROCKFORD[random.nextInt(CROCKFORD.length)] }.joinToString("")

    data class Ids(
        val merchantId: String,
        val businessId: String,
        val mid: String,
        val siteId: String,
        val otherSiteId: String,
        val assignmentId: String,
        val agreementId: String,
    )

    fun ids() = Ids("mer_${ulid()}", "biz_${ulid()}", "mid_${ulid()}", "site_${ulid()}", "site_${ulid()}", "asg_${ulid()}", "csa_${ulid()}")

    /** 결제 가능한 권리 내용(PRIMARY Site = siteId, 두 번째 Site는 연결 없음·심사 PENDING). */
    fun entitled(ids: Ids = ids(), revision: Long = 1, from: Instant = t0): EntitlementContent = EntitlementContent(
        merchantId = ids.merchantId,
        businessId = ids.businessId,
        revision = revision,
        party = Party(Lifecycle.ACTIVE, Lifecycle.ACTIVE, businessBlocked = false, merchantBlocked = false),
        sites = listOf(
            SiteEntry(ids.siteId, Lifecycle.ACTIVE, SiteReviewStatus.APPROVED),
            SiteEntry(ids.otherSiteId, Lifecycle.ACTIVE, SiteReviewStatus.PENDING),
        ),
        accounts = listOf(
            AccountEntry(
                mid = ids.mid,
                lifecycle = Lifecycle.ACTIVE,
                blocked = false,
                issuingAgreementId = ids.agreementId,
                serviceKind = "CARD_PG_PAYMENT",
                assignments = listOf(AssignmentEntry(ids.assignmentId, ids.siteId, AssignmentRole.PRIMARY, AssignmentStatus.ACTIVE, Interval(from, null))),
                contractWindows = listOf(ContractWindowEntry(ids.siteId, Interval(from, null)), ContractWindowEntry(ids.otherSiteId, Interval(from, null))),
                activations = listOf(ActivationEntry("CARD", ActivationStatus.ACTIVE)),
                siteConfirmations = listOf(SiteConfirmationEntry(ids.siteId, "CARD")),
                currencies = listOf(CurrencyEntry("KRW", 0, Interval(from, null))),
            ),
        ),
    )

    fun EntitlementContent.mapAccount(change: (AccountEntry) -> AccountEntry) = copy(accounts = accounts.map(change))

    fun leaseAt(issuedAt: Instant = t0, seq: Long = 0, duration: Duration = lease) = Lease(issuedAt, seq, issuedAt.plus(duration))

    fun incoming(content: EntitlementContent, lease: Lease = leaseAt(), eventId: String = UUID.randomUUID().toString()) =
        IncomingSnapshot(eventId, content.merchantId, content.revision, content.schemaVersion, content, content.contentHash, lease)

    /** PAYMENT가 해석하지 않는 schemaVersion(지원 집합 {1, 2, 3, 4} 밖 — MERCHANT-CODE-001 MC-4가 4를 지원하므로 첫 미지원 값은 5). */
    // 기대값 변경(MERCHANT-CODE-001 §6·MC-4): 4 → 5 — PAYMENT가 v4를 해석하게 되어 "해석 못 하는 첫 버전"이 5가 됐다(미지원 버전 fail-closed 규칙은 그대로).
    const val UNSUPPORTED_SCHEMA_VERSION: Int = 5

    fun unsupported(merchantId: String, revision: Long, eventId: String = UUID.randomUUID().toString()) =
        IncomingSnapshot(eventId, merchantId, revision, UNSUPPORTED_SCHEMA_VERSION, null, null, null)

    /** [content]를 적용한 head(빈 head에 APPLIED). */
    fun headOf(content: EntitlementContent, lease: Lease = leaseAt()): EntitlementHead =
        checkNotNull(SnapshotApplicationRule.decide(EntitlementHead.empty(content.merchantId), incoming(content, lease)).newHead)

    fun question(ids: Ids, site: String = ids.siteId, method: String = "CARD", currency: String = "KRW") = EligibilityQuestion(ids.mid, site, method, currency)

    // ── MASTER-SNAPSHOT-002 승인 시작 조건(schemaVersion 2). 값은 Demo — 운영값 아님. ─────────────────────────────

    /** Seoul 자정 경계(UTC 15:00) — t0(2026-10-09T02:00Z = 11:00 KST) 다음 자정과 그다음 자정. */
    val seoulMidnight1: Instant = Instant.parse("2026-10-09T15:00:00.000Z")
    val seoulMidnight2: Instant = Instant.parse("2026-10-10T15:00:00.000Z")

    const val ACQUIRER = "SIMCARD01"
    const val ROUTE_REF = "SIMVAN01"
    const val INSTITUTION_REF = "SIMMERCH0001"

    data class TermsIds(val csaDefault: String, val feeAssignment: String, val settlementAssignment: String, val feeVersion: String, val settlementVersion: String, val route: String)

    fun termsIds() = TermsIds("tlp_${ulid()}", "pas_${ulid()}", "pas_${ulid()}", "pv_${ulid()}", "pv_${ulid()}", "cra_${ulid()}")

    /** Demo CSA 기본 한도(KRW 건당 1,000,000 / 일 5,000,000) — t0부터(시험 편의상 경계 규칙은 MASTER 등록이 강제, 여기서는 구간만). */
    fun csaDefault(ids: Ids, policyId: String, from: Instant = t0, to: Instant? = null, perTx: LimitBoundEntry = LimitBoundEntry.Limited(1_000_000), daily: LimitBoundEntry = LimitBoundEntry.Limited(5_000_000)) =
        LimitPolicyEntry(policyId, LimitScopeKind.CSA_DEFAULT, ids.agreementId, "KRW", perTx, daily, Interval(from, to))

    fun midOverride(policyId: String, from: Instant, to: Instant? = null, perTx: LimitBoundEntry = LimitBoundEntry.Limited(300_000), daily: LimitBoundEntry = LimitBoundEntry.Unlimited) =
        LimitPolicyEntry(policyId, LimitScopeKind.MID_OVERRIDE, null, "KRW", perTx, daily, Interval(from, to))

    fun assignment(ids: Ids, assignmentId: String, kind: PolicyKind, versionId: String, from: Instant = t0, to: Instant? = null) = PolicyAssignmentEntry(
        assignmentId, kind, ids.agreementId, "KRW", versionId, if (kind == PolicyKind.FEE_SCHEDULE) "FEE-DEMO" else "STL-DEMO", 1, PolicyContentStatus.DEMO, Interval(from, to),
    )

    fun route(activationId: String, status: ActivationStatus = ActivationStatus.ACTIVE, acquirer: String = ACQUIRER) =
        CardRouteEntry(activationId, "CARD", acquirer, ROUTE_REF, INSTITUTION_REF, status)

    /** 승인 시작 가능한 v2 내용: [entitled] + CSA 기본 한도 1개 + 수수료·정산 Assignment + ACTIVE 카드사 개통 1개. */
    fun entitledWithTerms(ids: Ids = ids(), terms: TermsIds = termsIds(), revision: Long = 1, from: Instant = t0): EntitlementContent =
        entitled(ids, revision, from).let { v1 ->
            v1.copy(schemaVersion = TERMS_SNAPSHOT_SCHEMA_VERSION, accounts = v1.accounts.map { withTerms(it, ids, terms, from) })
        }

    private fun withTerms(account: AccountEntry, ids: Ids, terms: TermsIds, from: Instant) =
            account.copy(
                terms = AuthorizationTermsEntry(
                    limitPolicies = listOf(csaDefault(ids, terms.csaDefault, from)),
                    policyAssignments = listOf(
                        assignment(ids, terms.feeAssignment, PolicyKind.FEE_SCHEDULE, terms.feeVersion, from),
                        assignment(ids, terms.settlementAssignment, PolicyKind.SETTLEMENT_POLICY, terms.settlementVersion, from),
                    ),
                    cardRoutes = listOf(route(terms.route)),
                ),
            )

    fun EntitlementContent.mapTerms(change: (AuthorizationTermsEntry) -> AuthorizationTermsEntry) =
        mapAccount { it.copy(terms = change(checkNotNull(it.terms))) }

    fun termsQuestion(ids: Ids, acquirer: String = ACQUIRER, currency: String = "KRW") = AuthorizationTermsQuestion(ids.mid, ids.siteId, "CARD", currency, acquirer)

    // ── AUTHN-001 schemaVersion 3(v2 + sites[].channel, D-AUTHN-002). host는 Demo 값 — 운영 가맹점 도메인 아님. ─────────────────────

    const val WEB_HOST = "shop.example.test"

    /** 결제창을 열 수 있는 v3 내용: [entitledWithTerms] + PRIMARY Site(siteId)는 WEB [webHost], 두 번째 Site는 APP. */
    fun entitledWithChannels(ids: Ids = ids(), terms: TermsIds = termsIds(), revision: Long = 1, from: Instant = t0, webHost: String = WEB_HOST): EntitlementContent =
        entitledWithTerms(ids, terms, revision, from).let { v2 ->
            v2.copy(
                schemaVersion = CHANNEL_SNAPSHOT_SCHEMA_VERSION,
                sites = v2.sites.map { site ->
                    site.copy(channel = if (site.siteId == ids.siteId) SiteChannelEntry.Web(webHost) else SiteChannelEntry.App)
                },
            )
        }

    fun EntitlementContent.mapSite(siteId: String, change: (SiteEntry) -> SiteEntry) = copy(sites = sites.map { if (it.siteId == siteId) change(it) else it })

    // ── MERCHANT-CODE-001 MC-4 schemaVersion 4(v3 + 최상위 merchantCode — 출력 전용 표시값). 코드는 Demo 값 — 운영 가맹점 코드 아님. ─────────────────────

    const val MERCHANT_CODE = "fixshop01"

    /** v4 내용: [entitledWithChannels] + 가맹점 코드 [code]. 권리 판정 입력은 v3와 같다(코드는 금액·권리 의미 없음). */
    fun entitledWithCode(ids: Ids = ids(), terms: TermsIds = termsIds(), revision: Long = 1, from: Instant = t0, code: String = MERCHANT_CODE): EntitlementContent =
        entitledWithChannels(ids, terms, revision, from).copy(schemaVersion = MERCHANT_CODE_SNAPSHOT_SCHEMA_VERSION, merchantCode = code)
}
