package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfileRules
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.scalar
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

/**
 * BUSINESS-PROFILE-001 BIZ-2-9(C-24 — Demo seed 단계 1 전환, 실제 MySQL): 새 DB(새 키 공간) seed → 사업자 **REGISTERED**·REGISTERED 필수값 충족·단계 1 operation
 * `CreateBusiness`, seed 재실행 = 재생(행 +0), **옛 `RegisterBusiness` 기록이 있는 DB** 재실행 → 단계 1은 그 기록 재사용(`ContentConflict` 없음)·사업자 DRAFT·구분 NULL 유지.
 * 옛 seed DB의 재현: 같은 키 공간의 단계 1 키로 옛 seed가 남긴 행·기록을 SQL로 넣은 상태(BIZ-4 C-27 — 옛 등록 경로 제거 뒤. V14 → V15 실제 migration 재현은 BusinessLegacySeedMigrationSpec).
 * 운영 Demo 값(`DemoMerchantSeedValues.VALUES`)은 운영값이 아니다(D-DEMO-001). 공유 DB라 시험마다 번호·코드·host·키 공간을 새로 만든다.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class BusinessDemoSeedSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val query: BusinessProfileQueryUseCase,
    private val contracts: com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : BehaviorSpec({

    beforeSpec { faults.reset(); clock.reset() }
    afterAny { faults.reset(); clock.reset() }

    val t0: Instant = Instant.parse("2026-10-09T15:00:00Z")

    fun command() = DemoMerchantSeedCommand(
        keyPrefix = "bds-" + UUID.randomUUID().toString().take(12),
        actor = ActorRef("system:demo-seed"),
        correlationId = "demo-seed-" + UUID.randomUUID().toString().take(13),
        effectiveFrom = t0,
        // 운영 Demo 상수 그대로 + 공유 DB 충돌을 피하는 값만 교체(번호·코드·host).
        values = DemoMerchantSeedValues.VALUES.copy(
            registrationNumber = BusinessFixtures.registrationNumber(), merchantCode = MerchantCodeFixtures.unique(), siteHost = OnboardingFixture.host(),
        ),
    )
    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun stepOneOperations(prefix: String): List<String> = runtime(container).use { c ->
        c.prepareStatement("SELECT operation FROM admin_request WHERE idempotency_key = ? ORDER BY id").use { ps ->
            ps.setString(1, DemoSeedStep.REGISTER_BUSINESS.key(prefix).value)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
    fun businessOf(merchantId: String): String = runtime(container).use { it.scalar("SELECT business_id FROM merchant WHERE merchant_id = ?", merchantId) as String }

    Given("C-24 the production Demo business values") {
        Then("they are a REGISTERED individual business meeting every REGISTERED requirement (Demo values — not operational)") {
            val v = DemoMerchantSeedValues.VALUES
            v.businessType shouldBe BusinessType.INDIVIDUAL
            BusinessProfileRules.validate(v.businessType, v.registrationNumber, v.businessName, v.businessProfile, BusinessRegistrationStatus.REGISTERED).shouldBeEmpty()
            v.registrationNumber.value shouldBe "0000000000"
            v.businessName.value shouldBe "DEMO 사업자 (운영값 아님)"
            v.businessProfile.registration.openingDate.toString() shouldBe "2026-01-01"
            v.businessProfile.registration.phoneNumber!!.value shouldBe "0200000000"
            v.businessProfile.representatives.map { it.name.value to it.type.name } shouldBe listOf("DEMO 대표" to "SOLE")
            v.businessProfile.categories.map { it.type.name to it.name.value } shouldBe listOf("BUSINESS_TYPE" to "DEMO 업태", "BUSINESS_ITEM" to "DEMO 종목")
            v.businessProfile.addresses.map { listOf(it.type.name, it.postalCode.value, it.baseAddress.value, it.detailAddress?.value) } shouldBe
                listOf(listOf("REGISTERED", "00000", "DEMO 주소 (운영값 아님)", null))
            v.businessProfile.contacts.map { listOf(it.name.value, it.email.value, it.phoneNumber.value, it.primary) } shouldBe
                listOf(listOf("DEMO 담당", "demo@paycore.test", "01000000000", true))
        }
    }

    Given("BIZ-2-9 a new database (fresh key space)") {
        val c = command()
        val done = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        val businessId = businessOf(done.merchantId.value)

        Then("step 1 is CreateBusiness and the business is REGISTERED with the Demo profile (NOT_CHECKED)") {
            stepOneOperations(c.keyPrefix) shouldBe listOf("CreateBusiness")
            val d = query.detail(BusinessId(businessId)).shouldNotBeNull()
            d.business.registrationStatus shouldBe "REGISTERED"
            d.business.businessType shouldBe "INDIVIDUAL"
            d.business.verificationStatus shouldBe "NOT_CHECKED"
            d.business.merchantCount shouldBe 1L
            d.registration.phoneNumber shouldBe "0200000000"
            d.representatives.map { it.name } shouldBe listOf("DEMO 대표")
            d.contacts.single().primary shouldBe true
        }

        Then("FLOW-3 (fresh Demo — no migrated contract): the seed completes a business contract before registering; the intake's contract manager is exactly system:demo-seed and the section reads") {
            val section = query.contractIntakes(BusinessId(businessId)).shouldNotBeNull()
            section.businessContract.shouldNotBeNull().let { listOf(it.contractSeq, it.status, it.migrated) } shouldBe listOf(1, "ACTIVE", false)
            section.intakes.map { listOf(it.status, it.kind, it.migrated, it.contractManager) } shouldBe listOf(listOf("COMPLETED", "NEW", false, "system:demo-seed"))
            count("SELECT COUNT(*) FROM merchant_registration_basis WHERE merchant_id = ? AND contract_seq = 1 AND version_no = 1", done.merchantId.value) shouldBe 1L
        }

        Then("running the seed again replays every step (no new admin_request row)") {
            val rows = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}:%")
            // 리뷰 NIT(FLOW-3): 사업자 계약 준비 키(`<prefix>-bc…`)도 재실행에 늘지 않는다(가맹점 등록 성공이 기록돼 있으면 계약 준비를 건너뛴다).
            val contractKeys = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}-bc%")
            (contractKeys > 0L) shouldBe true
            val again = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            again.replayed shouldBe true
            again.merchantId shouldBe done.merchantId
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}:%") shouldBe rows
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}-bc%") shouldBe contractKeys
            stepOneOperations(c.keyPrefix) shouldBe listOf("CreateBusiness")
        }
    }

    Given("FLOW-3 review NIT: the seed prepared a business contract, failed before registering the merchant, and the contract was terminated before the re-run") {
        val c = command()
        faults.after(MasterFaultPoint.MERCHANT_INSERT) { throw IllegalStateException("injected merchant insert failure") }
        runCatching { seed.seed(c) }.getOrNull().shouldNotBeInstanceOfDone()
        faults.reset()
        val businessId = BusinessId(
            runtime(container).use { it.scalar("SELECT result_reference FROM admin_request WHERE idempotency_key = ?", DemoSeedStep.REGISTER_BUSINESS.key(c.keyPrefix).value) as String },
        )
        query.contractIntakes(businessId).shouldNotBeNull().businessContract.shouldNotBeNull().status shouldBe "ACTIVE"
        contracts.terminate(
            com.paycore.master.merchant.application.provided.BusinessContractEventCommand(
                OnboardingFixture.request(), businessId, 1, 0, com.paycore.master.merchant.domain.BusinessChangeReason("시험 해지"), null,
            ),
        ).shouldBeInstanceOf<com.paycore.master.merchant.application.provided.AdminCommandResult.Succeeded>()

        Then("the re-run prepares a new contract (#2) with fresh keys instead of replaying the first contract's keys, then registers the merchant") {
            val done = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            businessOf(done.merchantId.value) shouldBe businessId.value
            val section = query.contractIntakes(businessId).shouldNotBeNull()
            section.businessContract.shouldNotBeNull().let { listOf(it.contractSeq, it.status) } shouldBe listOf(2, "ACTIVE")
            count("SELECT COUNT(*) FROM merchant_registration_basis WHERE merchant_id = ? AND contract_seq = 2", done.merchantId.value) shouldBe 1L
        }
    }

    Given("BIZ-2-9 a database seeded before BIZ-2 (step 1 recorded as the legacy RegisterBusiness), after the BIZ-4 C-27 transition") {
        // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27 — D-BIZ-001·L-7): 옛 등록 경로가 제거돼 단계 1 기록은 옛 seed가 남긴 모양(V15 백필 DRAFT·구분 NULL 사업자 + 등록정보 1행 +
        // `admin_request` SUCCESS `RegisterBusiness`)을 SQL로 넣는다. 단계 1은 여전히 그 기록을 재사용하지만(ContentConflict 없음, CreateBusiness 기록 0), 전환 뒤 DRAFT 사업자에는 **새**
        // 가맹점을 만들 수 없으므로 단계 2가 저장된 `BUSINESS_NOT_REGISTERED`로 멈춘다(L-7 — 가맹점 단계까지 기록된 실제 옛 Demo DB의 재실행 성공은 BusinessLegacySeedMigrationSpec).
        val c = command()
        val legacy = "biz_" + com.paycore.master.merchant.domain.MasterFixture.ulid()
        com.paycore.master.MasterDbAccounts.root(container).use { root ->
            val at = java.sql.Timestamp.from(Instant.parse("2026-10-01T00:00:00.000Z"))
            root.prepareStatement(
                "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, registration_status, " +
                    "verification_status, updated_at, updated_by) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0, NULL, 'DRAFT', 'NOT_CHECKED', ?, ?)",
            ).use { ps ->
                ps.setString(1, legacy); ps.setString(2, c.values.registrationNumber.value); ps.setString(3, c.values.businessName.value)
                ps.setTimestamp(4, at); ps.setString(5, c.actor.value); ps.setTimestamp(6, at); ps.setString(7, c.actor.value)
                ps.executeUpdate()
            }
            root.prepareStatement("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')").use { ps -> ps.setString(1, legacy); ps.executeUpdate() }
            root.prepareStatement(
                "INSERT INTO admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind, result_code, result_reference, " +
                    "created_at, updated_at, version) VALUES ('RegisterBusiness', ?, ?, ?, ?, 'SUCCESS', NULL, ?, ?, ?, 1)",
            ).use { ps ->
                ps.setString(1, DemoSeedStep.REGISTER_BUSINESS.key(c.keyPrefix).value)
                ps.setString(2, "req_" + UUID.randomUUID().toString().replace("-", "").take(26))
                ps.setString(
                    3,
                    AdminCommandExecutor.legacyFingerprintOf(
                        com.paycore.master.merchant.application.provided.AdminOperation.REGISTER_BUSINESS, listOf(c.values.registrationNumber.value, c.values.businessName.value),
                    ),
                )
                ps.setString(4, c.actor.value); ps.setString(5, legacy); ps.setTimestamp(6, at); ps.setTimestamp(7, at)
                ps.executeUpdate()
            }
        }
        val first = seed.seed(c)
        val again = seed.seed(c)

        Then("step 1 reuses the recorded business (no ContentConflict, no CreateBusiness row); step 2 stops at the stored BUSINESS_NOT_REGISTERED (L-7); the business stays DRAFT with type NULL") {
            first shouldBe DemoMerchantSeedResult.StepRejected(DemoSeedStep.REGISTER_MERCHANT, com.paycore.master.merchant.domain.MasterRejection.BUSINESS_NOT_REGISTERED)
            stepOneOperations(c.keyPrefix) shouldBe listOf("RegisterBusiness")
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", legacy) shouldBe 0L
            val d = query.detail(BusinessId(legacy)).shouldNotBeNull()
            d.business.registrationStatus shouldBe "DRAFT"
            d.business.businessType.shouldBeNull()
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", c.values.registrationNumber.value) shouldBe 1L
        }

        Then("a further rerun replays the same stored rejection (no new rows)") {
            again shouldBe DemoMerchantSeedResult.StepRejected(DemoSeedStep.REGISTER_MERCHANT, com.paycore.master.merchant.domain.MasterRejection.BUSINESS_NOT_REGISTERED)
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}:%") shouldBe 2L
        }
    }
})

private fun DemoMerchantSeedResult?.shouldNotBeInstanceOfDone() {
    (this is DemoMerchantSeedResult.Done) shouldBe false
}
