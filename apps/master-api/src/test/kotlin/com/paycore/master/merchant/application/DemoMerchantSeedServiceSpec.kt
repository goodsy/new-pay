package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoMerchantValues
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.application.provided.EffectiveFromRefusal
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.RouteRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

/**
 * DEMO-SEED-001 seed Use Case(명세 §4.2·§4.3, I-5~I-14·I-20)를 실제 MySQL 8.4.11(runtime 계정)에서 확인한다. 공유 DB이므로 시험마다 키 공간
 * (keyPrefix)·등록번호·host를 새로 만든다 — 운영 명령은 고정 키 공간 하나다. 기대값은 구현 상수와 독립 리터럴(Test Fixture Policy).
 * 시계는 [MutableMasterClock] 기본값 2026-10-09T02:00Z(11:00 KST) → 다음 Seoul 자정 2026-10-09T15:00Z.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class DemoMerchantSeedServiceSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val requests: AdminRequestStore,
    private val transactions: PlatformTransactionManager,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : BehaviorSpec({

    // 시각·장애 주입은 공유 빈이다. 컨테이너(Given/When) 본문이 실패해도 다음 시험·다른 Spec에 남지 않도록 시험 종류와 무관하게(afterAny) 되돌리고,
    // Spec 끝(afterSpec)과 시작(beforeSpec)에서도 한 번 더 되돌린다 — 실패 하나가 다른 Spec의 시계 기준을 바꿔 연쇄 실패를 만들지 않게.
    beforeSpec {
        faults.reset()
        clock.reset()
    }
    afterAny {
        faults.reset()
        clock.reset()
    }
    afterSpec {
        faults.reset()
        clock.reset()
    }

    val t0: Instant = Instant.parse("2026-10-09T15:00:00Z")

    fun command(prefix: String = "dst-" + UUID.randomUUID().toString().take(12), effectiveFrom: Instant = t0, brn: BusinessRegistrationNumber = OnboardingFixture.registrationNumber()) =
        DemoMerchantSeedCommand(
            keyPrefix = prefix,
            actor = ActorRef("system:demo-seed"),
            correlationId = "demo-seed-" + UUID.randomUUID().toString().take(13),
            effectiveFrom = effectiveFrom,
            values = DemoMerchantValues(
                registrationNumber = brn,
                businessName = DisplayName("DEMO 사업자 (운영값 아님)"),
                merchantName = DisplayName("DEMO 가맹점 (운영값 아님)"),
                // MERCHANT-CODE-001 MC-1: 공유 DB — 시험마다 다른 코드(등록번호·host와 같은 이유).
                merchantCode = com.paycore.master.merchant.domain.MerchantCodeFixtures.unique(),
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

    fun count(sql: String, vararg args: String): Long = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun requestRows(prefix: String) = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ?", "$prefix:%")
    fun merchantsNamed(brn: BusinessRegistrationNumber) = count(
        "SELECT COUNT(*) FROM merchant m JOIN business b ON b.business_id = m.business_id WHERE b.registration_number = ?", brn.value,
    )
    fun historyOf(prefix: String) = count(
        "SELECT COUNT(*) FROM master_change_history h WHERE h.actor_ref = 'system:demo-seed' AND h.request_record_id IN " +
            "(SELECT r.request_record_id FROM admin_request r WHERE r.idempotency_key LIKE ?)",
        "$prefix:%",
    )

    fun done(result: DemoMerchantSeedResult) = result.shouldBeInstanceOf<DemoMerchantSeedResult.Done>()

    Given("a fresh key space and T0 = the next Seoul midnight") {
        val c = command()
        val result = done(seed.seed(c))

        Then("all 18 steps are recorded once with the system actor and the MID exists (I-14)") {
            result.replayed shouldBe false
            result.effectiveFrom shouldBe t0
            requestRows(c.keyPrefix) shouldBe 18
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ? AND result_kind = 'SUCCESS'", "${c.keyPrefix}:%") shouldBe 18
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key LIKE ? AND actor_ref = 'system:demo-seed'", "${c.keyPrefix}:%") shouldBe 18
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", result.merchantId.value) shouldBe 1
            historyOf(c.keyPrefix) shouldNotBe 0L
        }

        Then("exactly 10 entitlement snapshots are issued for the merchant (I-9 — snapshot issue rows only)") {
            issuesOf(container, result.merchantId) shouldHaveSize 10
            count(
                "SELECT COUNT(*) FROM outbox_event WHERE event_category = 'ENTITLEMENT_SNAPSHOT' AND event_id IN " +
                    "(SELECT outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ?)",
                result.merchantId.value,
            ) shouldBe 10
        }

        Then("the latest snapshot carries every authorization-start condition with Demo markers (I-9, I-13)") {
            val account = latestSnapshot(container, result.merchantId).get("accounts").single()
            account.get("mid").stringValue() shouldBe result.merchantAccountId.value
            account.get("activations").single().let { (it.get("paymentMethod").stringValue() to it.get("status").stringValue()) shouldBe ("CARD" to "ACTIVE") }
            account.get("siteConfirmations").single().get("siteId").stringValue() shouldBe result.siteId.value
            account.get("currencies").single().let {
                (it.get("currency").stringValue() to it.get("from").stringValue()) shouldBe ("KRW" to "2026-10-09T15:00:00.000Z")
            }
            account.get("limitPolicies").single().let {
                it.get("scope").stringValue() shouldBe "CSA_DEFAULT"
                it.get("perTransaction").get("amountMinor").longValue() shouldBe 1_000_000L
                it.get("daily").get("amountMinor").longValue() shouldBe 5_000_000L
                it.get("from").stringValue() shouldBe "2026-10-09T15:00:00.000Z"
            }
            account.get("policyAssignments").items().map { it.get("kind").stringValue() to it.get("contentStatus").stringValue() } shouldContainExactlyInAnyOrder
                listOf("FEE_SCHEDULE" to "DEMO", "SETTLEMENT_POLICY" to "DEMO")
            account.get("cardRoutes").single().let {
                listOf("paymentMethod", "acquirerCode", "routeRef", "status").map { f -> it.get(f).stringValue() } shouldBe
                    listOf("CARD", "SIMACQA", "SIMVAN01", "ACTIVE")
            }
            account.get("assignments").single().get("role").stringValue() shouldBe "PRIMARY"
            account.get("contractWindows").single().get("from").stringValue() shouldBe "2026-10-09T15:00:00.000Z"
        }

        When("the same command runs again (I-6)") {
            val issuesBefore = issuesOf(container, result.merchantId).size
            val historyBefore = historyOf(c.keyPrefix)
            val again = done(seed.seed(c))

            Then("every step replays: same references, no new rows") {
                again.replayed shouldBe true
                (again.merchantId to again.merchantAccountId) shouldBe (result.merchantId to result.merchantAccountId)
                again.siteId shouldBe result.siteId
                requestRows(c.keyPrefix) shouldBe 18
                issuesOf(container, result.merchantId).size shouldBe issuesBefore
                historyOf(c.keyPrefix) shouldBe historyBefore
                merchantsNamed(c.values.registrationNumber) shouldBe 1
            }
        }

        When("the same key space runs with a different T0 (I-7)") {
            val other = c.copy(effectiveFrom = Instant.parse("2026-10-10T15:00:00Z"))
            val conflict = seed.seed(other)

            Then("it is a content conflict at the first step carrying T0 and nothing new is stored") {
                conflict shouldBe DemoMerchantSeedResult.ContentConflict(DemoSeedStep.REGISTER_CONTRACT)
                requestRows(c.keyPrefix) shouldBe 18
                merchantsNamed(c.values.registrationNumber) shouldBe 1
            }
        }

        When("T0 has passed and every T0-bearing step is recorded (I-11 rerun)") {
            clock.set(Instant.parse("2026-10-20T00:00:00Z"))
            val replay = seed.seed(c)

            Then("the rerun replays with no pre-check refusal") {
                done(replay).replayed shouldBe true
            }
        }
    }

    Given("T0 pre-checks on a fresh key space (I-11, I-20)") {
        When("T0 is already in the past") {
            val c = command(effectiveFrom = Instant.parse("2026-10-08T15:00:00Z"))
            val r = seed.seed(c)
            Then("EFFECTIVE_FROM_PASSED and no admin_request row is created") {
                r shouldBe DemoMerchantSeedResult.EffectiveFromRefused(EffectiveFromRefusal.EFFECTIVE_FROM_PASSED)
                requestRows(c.keyPrefix) shouldBe 0
                merchantsNamed(c.values.registrationNumber) shouldBe 0
            }
        }
        When("T0 is less than 10 minutes ahead") {
            clock.set(Instant.parse("2026-10-09T14:51:00Z"))
            val c = command()
            val r = seed.seed(c)
            Then("EFFECTIVE_FROM_TOO_SOON and no admin_request row is created") {
                r shouldBe DemoMerchantSeedResult.EffectiveFromRefused(EffectiveFromRefusal.EFFECTIVE_FROM_TOO_SOON)
                requestRows(c.keyPrefix) shouldBe 0
            }
        }
        When("T0 is exactly 10 minutes ahead") {
            clock.set(Instant.parse("2026-10-09T14:50:00Z"))
            val c = command()
            Then("the seed runs") { done(seed.seed(c)) }
        }
        When("T0 is not a Seoul midnight") {
            val c = command(effectiveFrom = Instant.parse("2026-10-09T16:00:00Z"))
            Then("the use case refuses the argument before any write") {
                runCatching { seed.seed(c) }.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
                requestRows(c.keyPrefix) shouldBe 0
            }
        }
        When("a rerun has unrecorded T0-bearing steps and T0 has passed (m1)") {
            val c = command()
            // 앞 세 단계(T0 무관)만 기록된 상태: 04 단계 예약 직후 실패를 주입한다(그 TX는 rollback — 04 기록 없음).
            val brnTaken = c.values.registrationNumber
            faults.after(MasterFaultPoint.ADMIN_RESERVE) { _ -> if (AdminKeyProbe.isStep(c.keyPrefix, 4)) throw IllegalStateException("injected at step 04") }
            val first = runCatching { seed.seed(c) }
            faults.reset()
            clock.set(Instant.parse("2026-10-10T00:00:00Z"))
            val before = requestRows(c.keyPrefix)
            val r = seed.seed(c)
            Then("EFFECTIVE_FROM_PASSED with the DB unchanged") {
                first.isFailure shouldBe true
                before shouldBe 3
                r shouldBe DemoMerchantSeedResult.EffectiveFromRefused(EffectiveFromRefusal.EFFECTIVE_FROM_PASSED)
                requestRows(c.keyPrefix) shouldBe before
                merchantsNamed(brnTaken) shouldBe 1
            }
        }
    }

    Given("the progress read port (m2, I-20)") {
        val c = command()
        done(seed.seed(c))
        Then("find returns the stored result without creating rows, and null for an unknown key") {
            val template = TransactionTemplate(transactions).apply { isReadOnly = true }
            val found = template.execute {
                requests.find(AdminRequestScope(DemoSeedStep.ISSUE_MID.operation, DemoSeedStep.ISSUE_MID.key(c.keyPrefix)))
            }
            found shouldNotBe null
            found!!.result.shouldBeInstanceOf<com.paycore.master.merchant.application.required.StoredAdminResult.Succeeded>()
            val missing = template.execute { requests.find(AdminRequestScope(DemoSeedStep.ISSUE_MID.operation, DemoSeedStep.ISSUE_MID.key("dst-none-x"))) }
            missing shouldBe null
            requestRows("dst-none-x") shouldBe 0
        }
    }

    Given("a business with the same registration number already exists (I-12)") {
        val brn = OnboardingFixture.registrationNumber()
        onboarding.registerBusiness(RegisterBusinessCommand(OnboardingFixture.request(), brn, DisplayName("existing"))).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        val c = command(brn = brn)
        Then("the seed stops at step 01 with the stored rejection") {
            val r = seed.seed(c).shouldBeInstanceOf<DemoMerchantSeedResult.StepRejected>()
            r.step shouldBe DemoSeedStep.REGISTER_BUSINESS
            r.rejection shouldBe MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE
            requestRows(c.keyPrefix) shouldBe 1
        }
    }

    Given("two concurrent runs of the same command (I-8)") {
        val c = command()
        val results = concurrently(2) { seed.seed(c) }
        Then("exactly one merchant and MID exist and every result is Done or RetryLater") {
            results.forEach { it.isSuccess shouldBe true }
            val values = results.map { it.getOrThrow() }
            values.all { it is DemoMerchantSeedResult.Done || it is DemoMerchantSeedResult.RetryLater } shouldBe true
            values.count { it is DemoMerchantSeedResult.Done } shouldNotBe 0
            merchantsNamed(c.values.registrationNumber) shouldBe 1
            val finished = done(seed.seed(c))
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", finished.merchantId.value) shouldBe 1
            values.filterIsInstance<DemoMerchantSeedResult.Done>().forEach { it.merchantAccountId shouldBe finished.merchantAccountId }
        }
    }

    Given("a failure inside step 12's transaction after its business writes were flushed (I-5)") {
        val c = command()
        faults.after(MasterFaultPoint.ADMIN_COMPLETE) { _ -> if (AdminKeyProbe.isStep(c.keyPrefix, 12)) throw IllegalStateException("injected in step 12") }
        val first = runCatching { seed.seed(c) }
        faults.reset()
        Then("the rerun completes and the final rows equal a clean run") {
            first.isFailure shouldBe true
            requestRows(c.keyPrefix) shouldBe 11
            val r = done(seed.seed(c))
            requestRows(c.keyPrefix) shouldBe 18
            issuesOf(container, r.merchantId) shouldHaveSize 10
            merchantsNamed(c.values.registrationNumber) shouldBe 1
        }
    }
})

/**
 * 시험 전용 관찰값: 장애 주입 대역([MasterFaultInjectionConfig]의 AdminRequestStore)이 이 스레드에서 마지막으로 예약·완료한 멱등키를 남긴다.
 * hook이 "지금 몇 번째 seed 단계의 TX인가"를 판단하는 데 쓴다(production 코드와 무관).
 */
object AdminKeyProbe {
    val current: ThreadLocal<String?> = ThreadLocal()

    fun isStep(prefix: String, step: Int): Boolean = current.get()?.startsWith("$prefix:" + step.toString().padStart(2, '0') + "-") == true
}
