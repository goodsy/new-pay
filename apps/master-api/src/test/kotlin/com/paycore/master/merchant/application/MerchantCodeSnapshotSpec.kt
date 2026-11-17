package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.ApprovedCommandFixture.merchantCodeChange
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.EntitlementCommandFixture.revisionOf
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.testcontainers.mysql.MySQLContainer

/**
 * MERCHANT-CODE-001 §6·§8 MC-4(MASTER 쪽, 실제 MySQL — runtime 계정):
 * - MC-4-1: Snapshot v4 = 현재 코드(seq 최대)를 싣는다 — 계약 승인 전 1회 변경한 코드가 첫 MID 발급 Snapshot에 실린다.
 * - MC-4-4: 코드 변경 명령은 같은 MASTER 로컬 TX에서 `entitlement_revision + 1`·Snapshot 발행(MID 없으면 발행 없음 — 다른 코드를 같은 revision으로 내보내지 않는다),
 *   MID 있는 가맹점의 변경은 이미 `MERCHANT_CODE_LOCKED`(대조: MID 발급은 승인 사실 없이 불가 — Fixture가 위반 조합을 만들지 못함).
 * - MC-4-5: 최근 issue가 v3인 가맹점의 lease 갱신 = v4 **승격 발행**(revision + 1, leaseSeq 0, HIGH) — 같은 revision의 v4 발행 0건((merchant, revision)별 schema_version 하나).
 */
@MasterDbTest
class MerchantCodeSnapshotSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val leases: EntitlementLeaseUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    afterEach { clock.reset() }

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun currentCode(m: MerchantId): String =
        text("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq DESC LIMIT 1", m.value)!!
    fun issueCount(m: MerchantId): Long = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", m.value)

    Given("MC-4-4: the code change command (before the first contract approval — no MID yet)") {
        Then("one MASTER local TX raises entitlement_revision by one and records it in the change history; no snapshot is issued (no MID)") {
            val ready = readyMerchant(onboarding, approved = false)
            val m = ready.merchantId
            val before = revisionOf(container, m)
            val issuesBefore = issueCount(m)
            val command = merchantCodeChange(m, currentCode(m), MerchantCodeFixtures.unique().value)
            onboarding.changeMerchantCodeWithEvidence(command) shouldBe AdminCommandResult.Succeeded(m.value, replayed = false)
            revisionOf(container, m) shouldBe before + 1
            count(
                "SELECT CAST(JSON_EXTRACT(detail, '$.entitlementRevision') AS SIGNED) FROM master_change_history WHERE change_type = 'MerchantCodeChanged' AND aggregate_id = ?",
                m.value,
            ) shouldBe before + 1
            issueCount(m) shouldBe issuesBefore
            // 재전송 재생은 revision을 다시 올리지 않는다(멱등 — 명령 하나 = 효과 하나).
            onboarding.changeMerchantCodeWithEvidence(command) shouldBe AdminCommandResult.Succeeded(m.value, replayed = true)
            revisionOf(container, m) shouldBe before + 1
        }
        Then("a rejected change leaves the revision unchanged") {
            val ready = readyMerchant(onboarding, approved = false)
            val m = ready.merchantId
            val before = revisionOf(container, m)
            onboarding.changeMerchantCodeWithEvidence(merchantCodeChange(m, "otherabc12", MerchantCodeFixtures.unique().value))
                .shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.MERCHANT_CODE_MISMATCH
            revisionOf(container, m) shouldBe before
        }
    }

    Given("MC-4-1: a code changed before the contract approval flows into the first MID's snapshot (v4)") {
        Then("the first issuance snapshot (schemaVersion 4) carries the current (changed) code at the top level and no internal key as the code") {
            val ready = readyMerchant(onboarding, approved = false)
            val m = ready.merchantId
            val newCode = MerchantCodeFixtures.unique().value
            succeeded(onboarding.changeMerchantCodeWithEvidence(merchantCodeChange(m, currentCode(m), newCode)))
            succeeded(onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), ready.contractId, 1)))
            succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            val snapshot = latestSnapshot(container, m)
            snapshot.get("merchantCode").stringValue() shouldBe newCode
            count("SELECT schema_version FROM entitlement_snapshot_issue WHERE merchant_id = ? ORDER BY revision DESC, lease_seq DESC LIMIT 1", m.value) shouldBe
                ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION.toLong()
            ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION shouldBe 4
        }
    }

    Given("MC-4-4 control: a merchant with a MID") {
        Then("the change is already MERCHANT_CODE_LOCKED in MASTER (revision and issues unchanged) — the code that left for the merchant never changes") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val m = e.merchantId
            val before = revisionOf(container, m)
            val issuesBefore = issueCount(m)
            onboarding.changeMerchantCodeWithEvidence(merchantCodeChange(m, currentCode(m), MerchantCodeFixtures.unique().value))
                .shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.MERCHANT_CODE_LOCKED
            revisionOf(container, m) shouldBe before
            issueCount(m) shouldBe issuesBefore
            latestSnapshot(container, m).get("merchantCode").stringValue() shouldBe currentCode(m)
        }
        Then("the fixture cannot build the violating combination: a MID without an approved revision is REVISION_NOT_APPROVED") {
            val ready = readyMerchant(onboarding, approved = false)
            onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand()) shouldBe
                AdminCommandResult.Rejected(MasterRejection.REVISION_NOT_APPROVED, null, replayed = false)
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", ready.merchantId.value) shouldBe 0
        }
    }

    Given("MC-4-5: a merchant whose latest issue is a schemaVersion 3 snapshot (issued before this upgrade)") {
        Then("the lease renewal promotes it to v4 with revision + 1, leaseSeq 0, HIGH; no v4 issue at the old revision; one schema_version per (merchant, revision)") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val m = e.merchantId
            val latest = issuesOf(container, m).last()
            // 업그레이드 전 발행을 흉내 낸다(시험 fixture — 운영 runtime 계정은 이 표를 UPDATE할 수 없다, root로만). 그 revision의 모든 발행을 v3로.
            MasterDbAccounts.root(container).use { c ->
                c.prepareStatement("UPDATE paycore_master.entitlement_snapshot_issue SET schema_version = 3 WHERE merchant_id = ? AND revision = ?").use { ps ->
                    ps.setString(1, m.value)
                    ps.setLong(2, latest.revision)
                    (ps.executeUpdate() >= 1) shouldBe true
                }
            }
            val promotions = registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            val promoted = issuesOf(container, m).last()
            listOf(promoted.revision, promoted.leaseSeq, promoted.priority) shouldBe listOf(latest.revision + 1, 0L, "HIGH")
            revisionOf(container, m) shouldBe latest.revision + 1
            count("SELECT schema_version FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision = ?", m.value, promoted.revision) shouldBe 4L
            count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision = ? AND schema_version = 4", m.value, latest.revision) shouldBe 0L
            count(
                "SELECT COUNT(*) FROM (SELECT revision FROM entitlement_snapshot_issue WHERE merchant_id = ? GROUP BY revision HAVING COUNT(DISTINCT schema_version) > 1) x",
                m.value,
            ) shouldBe 0L
            (registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0) shouldBe promotions + 1
            latestSnapshot(container, m).get("merchantCode").stringValue() shouldBe currentCode(m)
            // 다음 갱신은 같은 revision의 LOW 갱신(승격은 한 번).
            clock.set(OnboardingFixture.t0.plusMillis(3000))
            leases.renewDueLeases()
            issuesOf(container, m).last().let { listOf(it.revision, it.leaseSeq, it.priority) } shouldBe listOf(promoted.revision, 1L, "LOW")
        }
    }
})
