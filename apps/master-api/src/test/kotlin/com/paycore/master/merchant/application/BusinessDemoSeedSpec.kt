package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
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
 * `CreateBusiness`, seed 재실행 = 재생(행 +0), **옛 `RegisterBusiness` 기록이 있는 DB** 재실행 → 그 기록 재사용(`ContentConflict`·`StepRejected` 없음)·사업자 DRAFT·구분 NULL 유지.
 * 옛 seed DB의 재현(명세 BIZ-2-9의 "V14 상태에서 seed 후 V15 적용"): 지금 코드는 V14 schema에 쓸 수 없으므로, 같은 키 공간의 단계 1 키로 옛 등록 경로
 * (`RegisterBusiness` — 과도기 저장이 V15가 V14 행을 바꾼 모양과 같은 DRAFT·구분 NULL·등록정보 1행임은 BIZ-1 X-10 시험이 확인)를 먼저 실행한 상태다.
 * 운영 Demo 값(`DemoMerchantSeedValues.VALUES`)은 운영값이 아니다(D-DEMO-001). 공유 DB라 시험마다 번호·코드·host·키 공간을 새로 만든다.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class BusinessDemoSeedSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val query: BusinessProfileQueryUseCase,
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

        Then("running the seed again replays every step (no new admin_request row)") {
            val rows = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}:%")
            val again = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            again.replayed shouldBe true
            again.merchantId shouldBe done.merchantId
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "${c.keyPrefix}:%") shouldBe rows
            stepOneOperations(c.keyPrefix) shouldBe listOf("CreateBusiness")
        }
    }

    Given("BIZ-2-9 a database seeded before BIZ-2 (step 1 recorded as the legacy RegisterBusiness)") {
        val c = command()
        val legacy = onboarding.registerBusiness(
            RegisterBusinessCommand(AdminRequest(DemoSeedStep.REGISTER_BUSINESS.key(c.keyPrefix), c.actor, c.correlationId), c.values.registrationNumber, c.values.businessName),
        ).shouldBeInstanceOf<AdminCommandResult.Succeeded>().reference
        val first = seed.seed(c)
        val again = seed.seed(c)

        Then("the seed reuses the recorded business (no ContentConflict, no StepRejected, no CreateBusiness row); the business stays DRAFT with type NULL") {
            val done = first.shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            businessOf(done.merchantId.value) shouldBe legacy
            stepOneOperations(c.keyPrefix) shouldBe listOf("RegisterBusiness")
            val d = query.detail(BusinessId(legacy)).shouldNotBeNull()
            d.business.registrationStatus shouldBe "DRAFT"
            d.business.businessType.shouldBeNull()
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", c.values.registrationNumber.value) shouldBe 1L
        }

        Then("a further rerun replays (Done, replayed)") {
            again.shouldBeInstanceOf<DemoMerchantSeedResult.Done>().replayed shouldBe true
        }
    }
})
