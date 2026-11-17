package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoMerchantValues
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.update
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * MERCHANT-CODE-001 MC-1-10(§3.5 C-23 — 리뷰 MAJOR-2): **MC-1 전에 seed된 DB**에서 seed를 다시 실행해도 `ContentConflict`가 없고 모든 단계가 재생이며, 그 가맹점 코드는 V13 채우기 값
 * 그대로(명령 값으로 바뀌지 않음)·배정 행 1·`admin_request` 행 +0.
 * MC-1 전 seed 결과의 재현(이 공유 DB 안에서): 지금 코드로 seed한 뒤 root로 ① `REGISTER_MERCHANT` 기록의 요청 지문을 MC-1 전 입력 `[businessId, displayName]`의 지문으로 되돌리고
 * ② 그 가맹점의 배정 행을 V13 채우기 행(`'m' + LEFT(SHA2(merchant_id,256),13)`, BACKFILL, created_at, `system:migration`)으로 바꾼다 — V12 seed 결과에 V13을 적용한 상태와 같은 행.
 * (V12 DB에 V13을 실제로 적용하는 경로는 MC-1-5 `MerchantCodeMigrationSpec`.) 대조군: 새 키 공간 seed는 명령 코드로 등록, 저장된 거절 단계(SUCCEEDED 아님)는 지금처럼 호출된다.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class MerchantCodeSeedRerunSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val container: MySQLContainer,
) : BehaviorSpec({

    beforeSpec { faults.reset(); clock.reset() }
    afterAny { faults.reset(); clock.reset() }
    afterSpec { faults.reset(); clock.reset() }

    val t0: Instant = Instant.parse("2026-10-09T15:00:00Z")

    fun command(brn: BusinessRegistrationNumber = OnboardingFixture.registrationNumber()) = DemoMerchantSeedCommand(
        keyPrefix = "mcs-" + UUID.randomUUID().toString().take(12),
        actor = ActorRef("system:demo-seed"),
        correlationId = "demo-seed-" + UUID.randomUUID().toString().take(13),
        effectiveFrom = t0,
        values = DemoMerchantValues(
            registrationNumber = brn,
            businessName = DisplayName("DEMO 사업자 (운영값 아님)"),
            // BUSINESS-PROFILE-001 C-24(BIZ-2): 단계 1 = CreateBusiness(REGISTERED) — 사업자 구분·내용은 운영 Demo 상수와 같은 값(운영값 아님). 행 작성만 확장(기대값 불변).
            businessType = com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues.VALUES.businessType,
            businessProfile = com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues.VALUES.businessProfile,
            merchantName = DisplayName("DEMO 가맹점 (운영값 아님)"),
            merchantCode = MerchantCodeFixtures.unique(),
            siteHost = OnboardingFixture.host(),
            evidence = EvidenceRef("DEMO-SEED-V1:NON-OPERATIONAL"),
            reason = DecisionReason("DEMO seed (not operational)"),
            currency = CurrencyCode("KRW"),
            perTransactionLimit = LimitValue.Limited(1_000_000),
            dailyLimit = LimitValue.Limited(5_000_000),
            feePolicyCode = PolicyCode("DEMO-FEE"),
            settlementPolicyCode = PolicyCode("DEMO-SETTLEMENT"),
            acquirerCode = AcquirerCode("SIMACQA"),
            routeRef = RouteRef("SIMVAN01"),
            institutionMerchantRef = InstitutionMerchantRef("SIMVAN-DEMO-0001"),
        ),
    )

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }
    fun codesOf(merchantId: String): List<String> = runtime(container).use { c ->
        c.prepareStatement("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq").use { ps ->
            ps.setString(1, merchantId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
    /**
     * 리뷰 m-3: Use Case가 실제로 실행됐는지 = 실행기의 멱등 예약(`reserveOrLock`)이 그 단계 키로 불렸는지. 장애 주입 대역의 ADMIN_RESERVE 지점(AdminKeyProbe가 이 스레드의 키를 남긴다)에서 센다.
     */
    fun countReservations(prefix: String, block: () -> Unit): Map<Int, Int> {
        val counts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        faults.after(MasterFaultPoint.ADMIN_RESERVE) { _ -> (1..18).firstOrNull { AdminKeyProbe.isStep(prefix, it) }?.let { counts.merge(it, 1, Int::plus) } }
        try { block() } finally { faults.reset() }
        return counts.toMap()
    }
    fun requestRows(prefix: String) = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "$prefix:%")
    fun backfill(merchantId: String) = "m" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(merchantId.toByteArray(Charsets.UTF_8))).take(13)

    Given("MC-1-10 a database seeded before MC-1 (pre-MC-1 REGISTER_MERCHANT fingerprint + V13 backfill row), then the seed runs again") {
        val c = command()
        val first = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        val merchantId = first.merchantId.value
        val businessId = runtime(container).use { conn ->
            conn.prepareStatement("SELECT business_id FROM merchant WHERE merchant_id = ?").use { ps -> ps.setString(1, merchantId); ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        }
        val legacyFingerprint = AdminCommandExecutor.fingerprintOf(AdminOperation.REGISTER_MERCHANT, listOf(businessId, c.values.merchantName.value))
        MasterDbAccounts.root(container).use { root ->
            root.update(
                "UPDATE admin_request SET request_fingerprint = ? WHERE operation = ? AND idempotency_key = ?",
                legacyFingerprint, AdminOperation.REGISTER_MERCHANT.code, DemoSeedStep.REGISTER_MERCHANT.key(c.keyPrefix).value,
            ) shouldBe 1
            root.update("DELETE FROM merchant_code_assignment WHERE merchant_id = ?", merchantId) shouldBe 1
            root.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "SELECT merchant_id, 1, CONCAT('m', LEFT(SHA2(merchant_id, 256), 13)), NULL, 'BACKFILL', NULL, NULL, created_at, 'system:migration' FROM merchant WHERE merchant_id = ?",
                merchantId,
            ) shouldBe 1
        }
        val rowsBefore = requestRows(c.keyPrefix)
        var rerun: DemoMerchantSeedResult? = null
        val calls = countReservations(c.keyPrefix) { rerun = seed.seed(c) }

        Then("MC-1-10 no ContentConflict: Done with every step replayed and the same references") {
            val done = rerun.shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            // C-27(rev 0.4.1): SUCCEEDED 단계는 Use Case를 부르지 않는다(REGISTER_MERCHANT 포함). T0를 싣는 단계(04·11·12·15·16)만 호출되어 같은 지문으로 재생된다.
            calls shouldBe mapOf(4 to 1, 11 to 1, 12 to 1, 15 to 1, 16 to 1)
            done.replayed shouldBe true
            listOf(done.merchantId, done.merchantAccountId, done.siteId) shouldBe listOf(first.merchantId, first.merchantAccountId, first.siteId)
        }

        Then("MC-1-10 the merchant keeps its backfill code (not the command's code), one assignment row, and admin_request gains no row") {
            codesOf(merchantId) shouldBe listOf(backfill(merchantId))
            codesOf(merchantId).single() shouldNotBe c.values.merchantCode.value
            requestRows(c.keyPrefix) shouldBe rowsBefore
            rowsBefore shouldBe 18
        }

        Then("MC-1-10 control: the REGISTER_MERCHANT record really carries the pre-MC-1 fingerprint (calling the use case would conflict)") {
            onboarding.registerMerchant(
                RegisterMerchantCommand(
                    AdminRequest(DemoSeedStep.REGISTER_MERCHANT.key(c.keyPrefix), c.actor, c.correlationId),
                    BusinessId(businessId), c.values.merchantName, c.values.merchantCode,
                ),
            ) shouldBe AdminCommandResult.IdempotencyKeyReused
        }
    }

    Given("MC-1-10 control: a fresh key space") {
        val c = command()
        val done = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        Then("MC-1-10 the merchant is registered with the command's code (new DB behaviour — the production constant is demoshop01)") {
            codesOf(done.merchantId.value) shouldBe listOf(c.values.merchantCode.value)
            DemoMerchantSeedValues.VALUES.merchantCode.value shouldBe "demoshop01"
        }
    }

    Given("MC-1-10 control: a step whose record is not SUCCEEDED (stored rejection)") {
        val brn = OnboardingFixture.registrationNumber()
        onboarding.registerBusiness(RegisterBusinessCommand(OnboardingFixture.request(), brn, DisplayName("existing"))).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        val c = command(brn)
        val first = seed.seed(c)
        var again: DemoMerchantSeedResult? = null
        val calls = countReservations(c.keyPrefix) { again = seed.seed(c) }
        Then("MC-1-10 the rerun still calls the use case for that step and gets the same stored rejection (no reuse of a non-SUCCEEDED record)") {
            first shouldBe DemoMerchantSeedResult.StepRejected(DemoSeedStep.REGISTER_BUSINESS, MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE)
            again shouldBe DemoMerchantSeedResult.StepRejected(DemoSeedStep.REGISTER_BUSINESS, MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE)
            requestRows(c.keyPrefix) shouldBe 1
            // 리뷰 m-3: 기록이 SUCCEEDED가 아니므로 재실행이 01 단계 Use Case를 실제로 다시 실행했다(멱등 예약 1회 → 저장된 거절 재생).
            calls shouldBe mapOf(1 to 1)
        }
    }
})
