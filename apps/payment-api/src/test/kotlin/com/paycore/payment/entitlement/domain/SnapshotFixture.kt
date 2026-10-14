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
        IncomingSnapshot(eventId, content.merchantId, content.revision, SUPPORTED_SNAPSHOT_SCHEMA_VERSION, content, content.contentHash, lease)

    fun unsupported(merchantId: String, revision: Long, eventId: String = UUID.randomUUID().toString()) =
        IncomingSnapshot(eventId, merchantId, revision, SUPPORTED_SNAPSHOT_SCHEMA_VERSION + 1, null, null, null)

    /** [content]를 적용한 head(빈 head에 APPLIED). */
    fun headOf(content: EntitlementContent, lease: Lease = leaseAt()): EntitlementHead =
        checkNotNull(SnapshotApplicationRule.decide(EntitlementHead.empty(content.merchantId), incoming(content, lease)).newHead)

    fun question(ids: Ids, site: String = ids.siteId, method: String = "CARD", currency: String = "KRW") = EligibilityQuestion(ids.mid, site, method, currency)
}
