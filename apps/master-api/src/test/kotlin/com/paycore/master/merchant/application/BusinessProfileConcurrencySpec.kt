package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BUSINESS-PROFILE-001 BIZ-1-8(TC-07 — 같은 번호 동시 생성, 실제 MySQL 다중 연결)·BIZ-1-9(TC-14 — 같은 version 동시 수정, 수정 vs 가맹점 등록 교착 없음).
 * 경합 진 쪽은 1062(번호 UNIQUE — 업무 유일키) → 실행기 새 TX 재판정 → 저장된 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`(503 아님)로 수렴한다.
 * 동시 수정은 business FOR UPDATE 직렬화 + 요청 version 명시 비교로 1건만 성공한다. 각 시나리오 3회 반복.
 */
@MasterDbTest
class BusinessProfileConcurrencySpec(
    private val service: BusinessProfileCommandUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    afterEach { faults.reset() }

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    fun label(r: Result<AdminCommandResult>): String = r.fold(
        onSuccess = {
            when (it) {
                is AdminCommandResult.Succeeded -> "OK"
                is AdminCommandResult.Rejected -> it.rejection.name
                AdminCommandResult.IdempotencyKeyReused -> "KEY_REUSED"
            }
        },
        onFailure = { "FAILURE:" + it::class.simpleName },
    )

    fun lockWaiters(): Int = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    fun awaitLockWaiter(): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters() >= 1) return true
            Thread.sleep(10)
        }
        return false
    }

    Given("BIZ-1-8 TC-07 the same registration number created concurrently with different keys") {
        listOf(2, 4, 8).forEach { n ->
            Then("$n connections: exactly one SUCCEEDED, the others a stored BUSINESS_REGISTRATION_NUMBER_DUPLICATE referencing the winner; one business row") {
                repeat(3) { round ->
                    val number = BusinessFixtures.registrationNumber()
                    val results = concurrently(n) { i ->
                        service.create(
                            CreateBusinessCommand(
                                request(), if (i % 2 == 0) BusinessRegistrationStatus.REGISTERED else BusinessRegistrationStatus.DRAFT, BusinessType.INDIVIDUAL, number,
                                DisplayName("동시 상호 $i"), if (i % 2 == 0) BusinessFixtures.individualProfile() else BusinessProfile.EMPTY,
                            ),
                        )
                    }
                    withClue("n=$n round=$round ${results.map(::label)}") {
                        val winner = results.mapNotNull { it.getOrNull() as? AdminCommandResult.Succeeded }.single().reference
                        results.map(::label).sorted() shouldBe (listOf("OK") + List(n - 1) { "BUSINESS_REGISTRATION_NUMBER_DUPLICATE" }).sorted()
                        results.mapNotNull { (it.getOrNull() as? AdminCommandResult.Rejected)?.reference }.toSet() shouldBe setOf(winner)
                        count("SELECT COUNT(*) FROM business WHERE registration_number = ?", number.value) shouldBe 1
                        count(
                            "SELECT COUNT(*) FROM business_registration r JOIN business b ON b.business_id = r.business_id WHERE b.registration_number = ?", number.value,
                        ) shouldBe 1
                        count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", winner) shouldBe 1
                    }
                }
            }
        }
    }

    Given("BIZ-1-9 TC-14 two updates with the same expected version") {
        Then("one succeeds and the other is BUSINESS_VERSION_CONFLICT (no overwrite); exactly one history row — the loser really waited on the business row lock") {
            repeat(3) { round ->
                val id = succeeded(service.create(CreateBusinessCommand(request(), BusinessRegistrationStatus.REGISTERED, BusinessType.INDIVIDUAL,
                    BusinessFixtures.registrationNumber(), DisplayName("원래 상호"), BusinessFixtures.individualProfile())))
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                faults.after(MasterFaultPoint.BUSINESS_LOCK_WITH_PROFILE) {
                    if (locked.count > 0) {
                        locked.countDown()
                        release.await(20, TimeUnit.SECONDS)
                    }
                }
                // i=0이 business 행 잠금을 쥔 채 멈추고(hook), i=1이 실제 DB 잠금 대기(performance_schema)에 들어간 것을 i=2(관찰자)가 본 뒤 놓는다.
                val all = concurrently(3) { i ->
                    when (i) {
                        2 -> {
                            locked.await(20, TimeUnit.SECONDS)
                            check(awaitLockWaiter()) { "the second update never waited on the business row lock" }
                            release.countDown()
                            null
                        }
                        else -> {
                            if (i == 1) locked.await(20, TimeUnit.SECONDS)
                            service.update(
                                UpdateBusinessCommand(request(), BusinessId(id), 0, BusinessChangeReason("상호 변경 $i"), BusinessType.INDIVIDUAL, DisplayName("새 상호 $i"),
                                    BusinessFixtures.individualProfile()),
                            )
                        }
                    }
                }
                all[2].getOrThrow()
                val results = all.take(2).map { r -> r.map { checkNotNull(it) } }
                withClue("round=$round ${results.map(::label)}") {
                    results.map(::label) shouldBe listOf("OK", "BUSINESS_VERSION_CONFLICT")
                    count("SELECT version FROM business WHERE business_id = ?", id) shouldBe 1
                    count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ? AND change_type = 'BusinessProfileUpdated'", id) shouldBe 1
                    val winner = results.indexOfFirst { (it.getOrNull() as? AdminCommandResult.Succeeded) != null }
                    runtime(container).use { it.scalar("SELECT display_name FROM business WHERE business_id = ?", id) } shouldBe "새 상호 $winner"
                }
            }
        }

        Then("a profile update and a merchant registration on the same business do not deadlock (same lock order: business first)") {
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 새 가맹점 등록은 REGISTERED 사업자만(D-BIZ-001 — requireRegistered 활성화). 교착 검사 대상을
            // DRAFT 사업자에서 REGISTERED 사업자로 바꾸고, 수정은 같은 잠금 경로의 일반정보 변경(담당자 이메일 — 사유 불필요)으로 둔다. 단언(OK, OK — 교착 없음)은 그대로.
            repeat(3) { round ->
                val id = succeeded(service.create(CreateBusinessCommand(request(), BusinessRegistrationStatus.REGISTERED, BusinessType.INDIVIDUAL,
                    BusinessFixtures.registrationNumber(), DisplayName("가맹점 있는 사업자"), BusinessFixtures.individualProfile())))
                OnboardingFixture.contracted(BusinessId(id)) // FLOW-3 입력 보강: 유효 사업자 계약(접수 명령은 사업자 version을 올리지 않는다 — 수정 기대 version 0 그대로)
                val results = concurrently(2) { i ->
                    if (i == 0) {
                        service.update(UpdateBusinessCommand(request(), BusinessId(id), 0, null, BusinessType.INDIVIDUAL, DisplayName("가맹점 있는 사업자"),
                            BusinessFixtures.individualProfile().copy(contacts = listOf(BusinessFixtures.contact(email = "changed@example.test")))))
                    } else {
                        onboarding.registerMerchant(RegisterMerchantCommand(request(), BusinessId(id), DisplayName("동시 가맹점"), MerchantCodeFixtures.unique()))
                    }
                }
                withClue("round=$round ${results.map(::label)}") { results.map(::label) shouldBe listOf("OK", "OK") }
            }
        }
    }
})
