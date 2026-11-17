package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MERCHANT-CODE-001 MC-1-2·MC-1-3(§3.3·§3.6, 실제 MySQL 8.4.11 runtime 계정): 가맹점 등록이 `merchant` + 배정 seq 1·이력·Outbox(코드 포함)를 같은 TX에 남기고,
 * 같은 코드 두 번째 등록은 저장된 `MERCHANT_CODE_TAKEN`(reference 없음·행 +0), 예약어는 저장된 `MERCHANT_CODE_RESERVED`, 같은 키·다른 코드 409, 같은 내용 재전송 재생.
 * DB 경합(MC-1-3, 리뷰 m-2): 두 연결이 같은 코드로 다른 가맹점 등록 → 성공 1·TAKEN 1(3회), 배정 행 1. 장애 주입 대역으로 **두 TX가 모두 사전 조회(빈 결과)를 통과한 뒤에야**
 * INSERT하게 강제해 진 쪽이 반드시 실제 1062(`StoreConstraint.MERCHANT_CODE`)를 겪고 새 TX 재판정의 사전 조회로 TAKEN에 수렴함을 단언한다(503 아님).
 */
@MasterDbTest
class MerchantCodeRegistrationSpec(
    private val service: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    beforeSpec { faults.reset() }
    afterAny { faults.reset() }

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun business(): BusinessId = BusinessId(succeeded(OnboardingFixture.registeredBusiness(service, OnboardingFixture.registrationNumber(), "Code Business")))
    fun assignments(code: MerchantCode) = count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_code = ?", code.value)
    fun merchantsOf(business: BusinessId) = count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", business.value)

    Given("MC-1-2 a merchant registered with a fresh code") {
        val business = business()
        val code = MerchantCodeFixtures.unique()
        val command = RegisterMerchantCommand(request(), business, DisplayName("Code Merchant"), code)
        val first = service.registerMerchant(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        val merchantId = first.reference

        Then("MC-1-2 merchant and its seq-1 REGISTRATION assignment are stored together with the operator and the decision time") {
            count(
                "SELECT COUNT(*) FROM merchant_code_assignment a JOIN merchant m ON m.merchant_id = a.merchant_id WHERE a.merchant_id = ? AND a.assignment_seq = 1 " +
                    "AND a.merchant_code = ? AND a.source = 'REGISTRATION' AND a.replaces_code IS NULL AND a.approval_id IS NULL AND a.reason_code IS NULL " +
                    "AND a.assigned_by = m.created_by AND a.assigned_at = m.created_at",
                merchantId, code.value,
            ) shouldBe 1
            count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_id = ?", merchantId) shouldBe 1
        }

        Then("MC-1-2 the MerchantRegistered history detail and Outbox payload carry the code") {
            text(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, '$.merchantCode')) FROM master_change_history WHERE aggregate_id = ? AND change_type = 'MerchantRegistered'", merchantId,
            ) shouldBe code.value
            text(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.merchantCode')) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'MerchantRegistered'", merchantId,
            ) shouldBe code.value
        }

        Then("MC-1-2 the same bytes again replay the stored result with no new rows") {
            val again = service.registerMerchant(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            (again.reference to again.replayed) shouldBe (merchantId to true)
            merchantsOf(business) shouldBe 1
            assignments(code) shouldBe 1
        }

        Then("MC-1-2 the same key with a different code is a content conflict (409) and stores nothing") {
            val other = MerchantCodeFixtures.unique()
            service.registerMerchant(command.copy(merchantCode = other)) shouldBe AdminCommandResult.IdempotencyKeyReused
            assignments(other) shouldBe 0
            merchantsOf(business) shouldBe 1
        }

        Then("MC-1-2 a second merchant asking for the same code is the stored MERCHANT_CODE_TAKEN without a reference and adds no rows (replayed on the same key)") {
            val otherBusiness = business()
            val taken = RegisterMerchantCommand(request(), otherBusiness, DisplayName("Copycat"), code)
            val rejected = service.registerMerchant(taken).shouldBeInstanceOf<AdminCommandResult.Rejected>()
            listOf(rejected.rejection, rejected.reference, rejected.replayed) shouldBe listOf(MasterRejection.MERCHANT_CODE_TAKEN, null, false)
            merchantsOf(otherBusiness) shouldBe 0
            assignments(code) shouldBe 1
            service.registerMerchant(taken).shouldBeInstanceOf<AdminCommandResult.Rejected>().replayed shouldBe true
        }
    }

    Given("MC-1-2 a reserved code") {
        val business = business()
        val command = RegisterMerchantCommand(request(), business, DisplayName("Reserved"), MerchantCode("adminshop"))

        Then("MC-1-2 it is the stored MERCHANT_CODE_RESERVED (no merchant, no assignment) and replays on the same key") {
            service.registerMerchant(command).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.MERCHANT_CODE_RESERVED
            merchantsOf(business) shouldBe 0
            assignments(MerchantCode("adminshop")) shouldBe 0
            service.registerMerchant(command).shouldBeInstanceOf<AdminCommandResult.Rejected>().let { (it.rejection to it.replayed) shouldBe (MasterRejection.MERCHANT_CODE_RESERVED to true) }
        }
    }

    Given("MC-1-3 two connections register different merchants with the same code at the same time (3 rounds, both forced past the pre-lookup)") {
        Then("MC-1-3 every round: both pre-lookups see no owner, exactly one INSERT fails with 1062 MERCHANT_CODE, the loser is the stored MERCHANT_CODE_TAKEN (never 503), one row") {
            repeat(3) { round ->
                val code = MerchantCodeFixtures.unique()
                val businesses = listOf(business(), business())
                // 처음 두 사전 조회(각 TX의 첫 시도)만 장벽에서 서로를 기다린다 — 둘 다 "주인 없음"을 본 뒤 INSERT. 재시도 TX의 사전 조회는 통과(커밋된 행을 본다).
                val barrier = CyclicBarrier(2)
                val firstPasses = AtomicInteger()
                val emptyFirstLookups = AtomicInteger()
                faults.after(MasterFaultPoint.MERCHANT_CODE_OWNER) { owner ->
                    if (firstPasses.incrementAndGet() <= 2) {
                        if (owner == null) emptyFirstLookups.incrementAndGet()
                        barrier.await(20, TimeUnit.SECONDS)
                    }
                }
                val results = concurrently(2) { i -> service.registerMerchant(RegisterMerchantCommand(request(), businesses[i], DisplayName("Race $round-$i"), code)) }
                val values = results.map { it.getOrThrow() }
                emptyFirstLookups.get() shouldBe 2
                faults.failures.filter { it.first == MasterFaultPoint.MERCHANT_INSERT }.map { (it.second as PersistenceFailure.UniqueConflict).constraint } shouldBe
                    listOf(StoreConstraint.MERCHANT_CODE)
                values.count { it is AdminCommandResult.Succeeded } shouldBe 1
                values.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection to it.reference } shouldBe listOf(MasterRejection.MERCHANT_CODE_TAKEN to null)
                assignments(code) shouldBe 1
                businesses.sumOf { merchantsOf(it) } shouldBe 1
                faults.reset()
            }
        }
    }
})
