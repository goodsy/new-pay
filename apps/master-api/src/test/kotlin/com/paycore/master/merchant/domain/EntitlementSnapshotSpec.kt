package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.demoLease
import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.reason
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * Snapshot 조립·정규 JSON·contentHash(명세 §3, §10)와 계약 적용 구간(n-5: 명령 없이 시각 경계에서 바뀌는 적용 Revision을 절대 시각 구간으로 싣는다).
 */
class EntitlementSnapshotSpec : BehaviorSpec({

    Given("an issued, approved and confirmed merchant (official paths)") {
        Then("the snapshot carries version es1:<merchant>:<revision>, the lease and the merchant's full rights state") {
            val source = EntitlementFixture.source()
            val snapshot = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
            val account = snapshot.accounts.single()
            mapOf(
                "version" to snapshot.snapshotVersion,
                "leaseUntil" to snapshot.leaseUntil,
                "site" to snapshot.sites.single(),
                "assignment" to account.assignments.single().role,
                "window" to account.contractWindows.single(),
                "activation" to account.activations.single(),
                "confirmation" to account.siteConfirmations.single().siteId,
                "currency" to account.currencies.single().currency.value,
                "party" to snapshot.party,
            ) shouldBe mapOf(
                "version" to "es1:${source.merchant.id.value}:${source.merchant.entitlementRevision}",
                "leaseUntil" to t0.plus(demoLease),
                "site" to SiteState(source.sites.single().id, Lifecycle.ACTIVE, SiteReviewStatus.APPROVED, source.sites.single().channel),
                "assignment" to SiteAssignmentRole.PRIMARY,
                "window" to ContractWindow(source.sites.single().id, t0, null),
                "activation" to ActivationState(PaymentMethod.CARD, ActivationStatus.ACTIVE),
                "confirmation" to source.sites.single().id,
                "currency" to "KRW",
                "party" to PartyState(Lifecycle.ACTIVE, Lifecycle.ACTIVE, businessBlocked = false, merchantBlocked = false),
            )
            snapshot.snapshotVersion.length shouldBe snapshot.snapshotVersion.length.coerceAtMost(64)
        }

        Then("schemaVersion 3 carries each site's channel: a WEB site's lower-case host, an APP site's type only (D-AUTHN-002, AUTHN-001 §9.1)") {
            val source = EntitlementFixture.source()
            val snapshot = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
            val site = source.sites.single()
            site.channel.type shouldBe ChannelType.WEB
            snapshot.canonicalContent shouldContain "\"channel\":{\"host\":\"${site.channel.identifier}\",\"type\":\"WEB\"}"
            val app = SiteState(site.id, Lifecycle.ACTIVE, SiteReviewStatus.APPROVED, SiteChannel(ChannelType.APP, "com.example.shop_app"))
            val withApp = snapshot.copy(sites = listOf(app))
            withApp.canonicalContent shouldContain "\"channel\":{\"type\":\"APP\"}"
            withApp.canonicalContent shouldNotContain "com.example.shop_app"
            // 기대값 변경(MERCHANT-CODE-001 §6·MC-4): 현재 조립 버전 3 → 4(v3 + merchantCode). channel 규칙은 그대로.
            ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION shouldBe 4
        }

        Then("MERCHANT-CODE-001 MC-4-1: schemaVersion 4 carries the merchant's current code (top level) — never its internal key as a code, rights content unchanged") {
            val source = EntitlementFixture.source()
            val snapshot = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
            snapshot.merchantCode shouldBe source.merchant.currentCode
            snapshot.canonicalContent shouldContain "\"merchantCode\":\"${source.merchant.currentCode.value}\""
            ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION shouldBe 4
            // 코드만 다르면 내용 hash가 다르다(같은 revision·다른 코드 = PAYMENT INTEGRITY_CONFLICT의 근거 — 변경 명령은 revision +1, §6).
            val other = snapshot.copy(merchantCode = MerchantCode("othershop01"))
            other.contentHash shouldNotBe snapshot.contentHash
            other.canonicalContent.replace("othershop01", source.merchant.currentCode.value) shouldBe snapshot.canonicalContent
        }

        Then("a terminated site is still carried with its channel (content is a function of the source state only, MS1 §15-1)") {
            val source = EntitlementFixture.source()
            val snapshot = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
            val terminated = snapshot.sites.single().copy(lifecycle = Lifecycle.TERMINATED)
            snapshot.copy(sites = listOf(terminated)).canonicalContent shouldContain "\"lifecycle\":\"TERMINATED\""
            snapshot.copy(sites = listOf(terminated)).canonicalContent shouldContain "\"type\":\"WEB\""
        }

        Then("a site without review decisions is PENDING in the snapshot (payment blocked until APPROVED)") {
            val snapshot = EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(reviews = emptyMap()), t0, 0, demoLease)
            snapshot.sites.single().reviewStatus shouldBe SiteReviewStatus.PENDING
        }

        Then("lease renewal (later issuedAt, next leaseSeq) keeps the content hash; the wire payload differs only in lease fields") {
            val source = EntitlementFixture.source()
            val first = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
            val renewal = EntitlementSnapshotAssembler.assemble(source, t0.plusSeconds(3600 * 24 * 400L), 1, demoLease)
            renewal.contentHash shouldBe first.contentHash
            renewal.canonicalContent shouldBe first.canonicalContent
            renewal.wirePayload() shouldNotBe first.wirePayload()
            first.contentHash shouldStartWith "ec1:"
        }

        Then("a content change (activation suspended) changes the content hash") {
            val active = EntitlementFixture.active()
            val before = EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(active), t0, 0, demoLease)
            active.activation.suspend(reason, t0, operator)
            val after = EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(active), t0, 0, demoLease)
            after.contentHash shouldNotBe before.contentHash
            after.accounts.single().activations.single().status shouldBe ActivationStatus.SUSPENDED
        }

        Then("revoked confirmations and confirmations of a terminated activation are not carried; the terminated activation is omitted") {
            val active = EntitlementFixture.active()
            active.confirmation.revoke(reason, evidence, t0, operator)
            EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(active), t0, 0, demoLease)
                .accounts.single().siteConfirmations.shouldBeEmpty()

            val other = EntitlementFixture.active()
            other.activation.terminate(reason, t0, operator)
            val account = EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(other), t0, 0, demoLease).accounts.single()
            account.activations.shouldBeEmpty()
            account.siteConfirmations.shouldBeEmpty()
        }

        Then("ended assignments and past windows are kept (content depends on MASTER state only, not on the issue time)") {
            val source = EntitlementFixture.source()
            val late = EntitlementSnapshotAssembler.assemble(source, t0.plusSeconds(10L * 365 * 24 * 3600), 0, demoLease)
            late.accounts.single().assignments.size shouldBe 1
            late.accounts.single().contractWindows.size shouldBe 1
        }
    }

    Given("contract windows of the issuing contract (D-MD-011·015, n-5)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val a = MasterFixture.site(merchant)
        val b = MasterFixture.site(merchant)
        val t1 = t0.plusSeconds(3600)
        val t2 = t0.plusSeconds(7200)

        Then("a later approved SITES revision supersedes the MERCHANT revision at its start: site b leaves the scope at that boundary") {
            val contract = MasterFixture.approvedCardContract(merchant, business)
            contract.registerRevision(ContractScope.Sites(merchant.id, setOf(a.id)), EffectivePeriod(t1), t0, operator)
            contract.approve(2, t0, operator)
            ContractWindows.of(contract, a) shouldBe listOf(ContractWindow(a.id, t0, null))
            ContractWindows.of(contract, b) shouldBe listOf(ContractWindow(b.id, t0, t1))
        }

        Then("an unapproved revision does not count; a future-only approved revision gives a future window (pre-issuance, D-MD-015)") {
            val future = MasterFixture.contract(merchant, business, EffectivePeriod(t1, t2))
            ContractWindows.of(future, a).shouldBeEmpty()
            future.approve(1, t0, operator)
            ContractWindows.of(future, a) shouldBe listOf(ContractWindow(a.id, t1, t2))
        }

        Then("adjacent revisions both including the site merge into one window") {
            val contract = MasterFixture.contract(merchant, business, EffectivePeriod(t0, t1))
            contract.approve(1, t0, operator)
            contract.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t1, t2), t0, operator)
            contract.approve(2, t0, operator)
            ContractWindows.of(contract, a) shouldBe listOf(ContractWindow(a.id, t0, t2))
        }
    }

    Given("the canonical JSON writer") {
        Then("keys are sorted, timestamps are UTC milliseconds, strings escape quotes, backslashes and control characters only") {
            CanonicalJson.write(
                mapOf("b" to 1L, "a" to listOf(true, null, "x\"y\\z\u0001é"), "c" to java.time.Instant.parse("2026-10-09T01:02:03.400Z")),
            ) shouldBe "{\"a\":[true,null,\"x\\\"y\\\\z\\u0001é\"],\"b\":1,\"c\":\"2026-10-09T01:02:03.400Z\"}"
            shouldThrow<IllegalArgumentException> { CanonicalJson.write(mapOf("x" to 1.5)) }
        }
    }

    Given("snapshot invariants") {
        Then("revision 0, an empty account list or a lease that does not end after issuance cannot form a snapshot") {
            val s = EntitlementSnapshotAssembler.assemble(EntitlementFixture.source(), t0, 0, demoLease)
            shouldThrow<IllegalArgumentException> { s.copy(revision = 0) }
            shouldThrow<IllegalArgumentException> { s.copy(accounts = emptyList()) }
            shouldThrow<IllegalArgumentException> { s.copy(leaseUntil = s.issuedAt) }
        }
    }
})
