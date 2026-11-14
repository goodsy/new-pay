package com.paycore.master.merchant.adapter.query

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.RandomOnboardingFixture
import com.paycore.master.merchant.application.provided.AssignmentView
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MerchantStructureQuery
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PaymentMethod
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT_SITE_ASSIGNMENT
import com.paycore.master.merchant.application.JooqStatementRecorder
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import java.sql.Connection
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import javax.sql.DataSource

/**
 * CQRS 조회(jOOQ adapter.query, 생성 코드는 MASTER 업무 schema에서 생성)가 Command 측(JPA)이 쓴 구조를 DTO로 그대로 읽는지 확인한다.
 * 대량 입력은 RandomOnboardingFixture(Fixture Monkey, seed 고정) 뒤에서 만든다.
 */
@MasterDbTest
class MerchantStructureQuerySpec(
    private val service: MerchantOnboardingUseCase,
    private val query: MerchantStructureQuery,
    private val recorder: JooqStatementRecorder,
    private val dsl: DSLContext,
    private val dataSource: DataSource,
) : BehaviorSpec({

    /** JVM 기본 시간대를 UTC가 아닌 값으로 바꾼 채 실행한다(UTC 호스트에서도 §9.1 회귀를 잡기 위해, review m-1). pool은 새 연결로 갈아끼운다. */
    fun <T> withJvmTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        try {
            ZoneId.systemDefault() shouldNotBe ZoneId.of("UTC")
            return block()
        } finally {
            TimeZone.setDefault(original)
            dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        }
    }

    Given("a stored PRIMARY link read on a JVM whose default time zone is Asia/Seoul (review m-1, ARCHITECTURE-CONVENTIONS §9.1)") {
        Then("the query returns the stored UTC instant, while the old reading (generated LocalDateTime + ZoneOffset.UTC) is 9 hours off") {
            val ready = readyMerchant(service)
            val mid = succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            withJvmTimeZone("Asia/Seoul") {
                query.find(ready.merchantId)!!.accounts.single().assignments.single().effectiveFrom shouldBe OnboardingFixture.t0
                // 대조군: 이전 방식은 Asia/Seoul JVM에서 틀린다(이 시험이 회귀를 잡는다는 증거).
                val old = dsl.select(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_FROM).from(MERCHANT_ACCOUNT_SITE_ASSIGNMENT)
                    .where(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ACCOUNT_ID.eq(mid)).fetchOne()!!.value1()!!.toInstant(ZoneOffset.UTC)
                old shouldBe OnboardingFixture.t0.plusSeconds(9 * 3600L)
            }
        }
    }

    Given("the six statements of one structure read (merchant, sites, default, links, methods, MIDs — review m-2)") {
        Then("all run inside one read-only REPEATABLE READ transaction (one consistent InnoDB snapshot)") {
            val ready = readyMerchant(service)
            succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            recorder.observations.clear()
            recorder.recording = true
            try {
                query.find(ready.merchantId)
            } finally {
                recorder.recording = false
            }
            recorder.observations shouldBe List(6) {
                JooqStatementRecorder.Observation(inTransaction = true, readOnly = true, isolation = Connection.TRANSACTION_REPEATABLE_READ)
            }
        }
    }

    Given("a Merchant with an issued first Default MID and a registered CARD activation") {
        Then("the query returns sites, the Default MID with its issuing basis, the open PRIMARY link and the registered method") {
            val ready = readyMerchant(service)
            val mid = succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            succeeded(service.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), MerchantAccountId(mid), PaymentMethod.CARD)))

            val view = query.find(ready.merchantId)!!
            view.businessId shouldBe ready.businessId.value
            // Site 등록 2 + 계약 승인 1 + 발급 1 + 개통 등록 1(MASTER-SNAPSHOT-001 review M-2: Site 등록도 권리 입력 변경 — 이전 기대값 3에서 변경)
            view.entitlementRevision shouldBe 5L
            view.sites.map { it.merchantSiteId }.toSet() shouldBe setOf(ready.siteId.value, ready.secondSiteId.value)
            val account = view.accounts.single()
            listOf(account.merchantAccountId, account.isDefault, account.issuingContractId, account.issuingAgreementId, account.issuingRevisionNo) shouldBe
                listOf(mid, true, ready.contractId.value, ready.agreementId.value, 1)
            account.assignments shouldBe listOf(
                AssignmentView(account.assignments.single().assignmentId, ready.siteId.value, "PRIMARY", "ACTIVE", OnboardingFixture.t0, null),
            )
            account.registeredPaymentMethods shouldBe listOf("CARD")
        }

        Then("an unknown Merchant yields no view") {
            query.find(MerchantId("mer_00000000000000000000000000")) shouldBe null
        }
    }

    Given("12 generated Merchants with 1..3 sites each (Fixture Monkey behind RandomOnboardingFixture, fixed seed)") {
        Then("each structure reads back exactly what the commands stored (no MID yet, all sites ACTIVE)") {
            val business = BusinessId(
                succeeded(service.registerBusiness(RegisterBusinessCommand(request(), OnboardingFixture.registrationNumber(), DisplayName("Bulk")))),
            )
            RandomOnboardingFixture.validMerchants(count = 12, seed = 20261009L).forEach { shape ->
                val merchant = MerchantId(succeeded(service.registerMerchant(RegisterMerchantCommand(request(), business, shape.displayName, com.paycore.master.merchant.domain.MerchantCodeFixtures.unique()))))
                val hosts = (1..shape.siteCount).map { OnboardingFixture.host() }
                hosts.forEach { succeeded(service.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, it))) }

                val view = query.find(merchant)!!
                view.sites.map { it.channelIdentifier }.toSet() shouldBe hosts.toSet()
                view.sites.map { it.lifecycle }.toSet() shouldBe setOf("ACTIVE")
                view.accounts shouldBe emptyList()
                // Site 등록마다 +1(review M-2). MID가 없으니 Snapshot 발행은 없다.
                view.entitlementRevision shouldBe shape.siteCount.toLong()
            }
        }
    }
})
