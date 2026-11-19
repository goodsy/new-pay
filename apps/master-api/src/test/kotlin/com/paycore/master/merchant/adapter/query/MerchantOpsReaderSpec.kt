package com.paycore.master.merchant.adapter.query

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.JooqStatementRecorder
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.required.MerchantDetailReader
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.scalar
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import javax.sql.DataSource

/**
 * MERCHANT-OPS-001 §3.1–3.3·AC 3–6(실제 MySQL 8.4 — jOOQ 조회 adapter): 목록 필터·keyset, 상세 모양·한 RO·RR TX·비 UTC JVM, 이력 범위·cursor, **비노출 대조군**(기관 가맹점 번호가
 * DB에는 실제로 있는데 view에는 없다). 시험 데이터는 공식 use case로만 만든다(Named Fixture — Demo 값).
 */
@MasterDbTest
class MerchantOpsReaderSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val query: MerchantOpsQuery,
    private val detailReader: MerchantDetailReader,
    private val recorder: JooqStatementRecorder,
    private val dataSource: DataSource,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun fullMerchant() = AuthorizationTermsCommandFixture.ready(terms, EntitlementCommandFixture.entitled(onboarding, entitlement))

    fun registrationNumberOf(businessId: BusinessId): String = OnboardingFixture.runtime(container).use {
        it.scalar("SELECT registration_number FROM business WHERE business_id = ?", businessId.value) as String
    }

    fun <T> withJvmTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
            dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        }
    }

    Given("a fully onboarded Merchant (MID, CARD activation, approved site review, KRW, fee/settlement policy, CSA limit, ACTIVE card route)") {
        Then("the detail returns every block with masked registration number and the route without the institution merchant reference") {
            val r = fullMerchant()
            val e = r.entitled
            val brn = registrationNumberOf(e.ready.businessId)
            val v = query.detail(e.merchantId).shouldNotBeNull()
            v.merchant.merchantId shouldBe e.merchantId.value
            v.merchant.lifecycle shouldBe "ACTIVE"
            v.merchant.createdAt shouldBe OnboardingFixture.t0
            v.business.businessId shouldBe e.ready.businessId.value
            v.business.registrationNumberMasked shouldBe BusinessRegistrationNumber(brn).masked()
            v.sites.map { it.merchantSiteId }.toSet() shouldBe setOf(e.ready.siteId.value, e.ready.secondSiteId.value)
            v.sites.single { it.merchantSiteId == e.ready.siteId.value }.review!!.decision shouldBe "APPROVED"
            v.sites.single { it.merchantSiteId == e.ready.secondSiteId.value }.review shouldBe null
            val contract = v.contracts.single()
            contract.contractId shouldBe e.ready.contractId.value
            contract.revisions.single().approvedAt.shouldNotBeNull()
            // MERCHANT-OPS-002 B2-2(D-MOPS-008 ③): 기존 contract_revision.registered_by(Revision 1 = 계약 등록자 — 새 열 없음).
            contract.revisions.single().registeredBy shouldBe OnboardingFixture.operator.value
            contract.agreements.single().agreementId shouldBe e.ready.agreementId.value
            val account = v.accounts.single()
            listOf(account.merchantAccountId, account.isDefault, account.lifecycle) shouldBe listOf(e.mid.value, true, "ACTIVE")
            account.assignments.single().role shouldBe "PRIMARY"
            account.paymentMethods.single().let { listOf(it.activationId, it.paymentMethod, it.status) } shouldBe listOf(e.activationId.value, "CARD", "ACTIVE")
            account.currencies.single().currencyCode shouldBe "KRW"
            // MERCHANT-OPS-002 B3-1(§8.3 B3-1-7, MAJOR-1 표시용): 등록자(기존 열 — Activation registered_by, Site created_by)와 계정별 Site 적용 확인(근거·사유 없음).
            account.paymentMethods.single().registeredBy shouldBe OnboardingFixture.operator.value
            v.sites.single { it.merchantSiteId == e.ready.siteId.value }.registeredBy shouldBe OnboardingFixture.operator.value
            account.siteConfirmations.single().let { listOf(it.merchantSiteId, it.paymentMethod, it.activationId, it.revoked) } shouldBe
                listOf(e.ready.siteId.value, "CARD", e.activationId.value, false)
            account.cardRoutes.single().let { listOf(it.activationId, it.acquirerCode, it.routeRef, it.status) } shouldBe
                listOf(r.route.value, AuthorizationTermsCommandFixture.acquirer.value, AuthorizationTermsCommandFixture.routeRef.value, "ACTIVE")
            v.limits.single().let { listOf(it.policyId, it.scopeKind, it.perTxAmount, it.dailyAmount) } shouldBe
                listOf(r.limit.value, "CSA_DEFAULT", 1_000_000L, 5_000_000L)
            // 전파 상태는 별도 Port(기존 EntitlementPropagationReader)가 채운다 — MID가 있어 발행 기록이 있다.
            v.propagation.shouldNotBeNull()

            // 비노출 대조군: 같은 DB에 기관 가맹점 번호가 실제로 있다(Route 행과 이력 detail) — 그런데 view 어디에도 없다.
            val institutionRef = AuthorizationTermsCommandFixture.institutionRef.value
            OnboardingFixture.runtime(container).use { c ->
                c.scalar("SELECT institution_merchant_ref FROM card_route_activation WHERE activation_id = ?", r.route.value) shouldBe institutionRef
                (c.scalar("SELECT CAST(detail AS CHAR) FROM master_change_history WHERE aggregate_id = ? ORDER BY id LIMIT 1", r.route.value) as String) shouldContain institutionRef
            }
            v.toString() shouldNotContain institutionRef
            v.toString() shouldNotContain brn
        }

        Then("an unknown Merchant has no detail and no history") {
            query.detail(MerchantId("mer_00000000000000000000000000")) shouldBe null
            query.history(MerchantId("mer_00000000000000000000000000"), 0) shouldBe null
        }
    }

    Given("the detail statements (review m-2: no torn detail)") {
        Then("every statement of one detail read runs in one read-only REPEATABLE READ transaction") {
            val e = fullMerchant().entitled
            recorder.observations.clear()
            recorder.recording = true
            try {
                detailReader.read(e.merchantId).shouldNotBeNull()
            } finally {
                recorder.recording = false
            }
            // merchant+business 1, sites 2(심사·Site), contracts 4(범위 Site·revision·CSA·계약), accounts 6(Default·연결·개통·통화·Route·MID), 한도 1
            // 기대값 변경(MERCHANT-OPS-002 B3-1 §8.3 B3-1-7): accounts에 Site 적용 확인 1문장 = 15. 모든 문장이 같은 읽기 전용 REPEATABLE READ TX라는 단언은 그대로다.
            // 기대값 변경(MERCHANT-CODE-001 MC-1 §3.8): 코드 배정 이력 1문장 + 계약 승인 사실 존재(merchantCodeChangeable advisory) 1문장 = 17 — 같은 TX 단언은 그대로.
            recorder.observations shouldBe List(17) {
                JooqStatementRecorder.Observation(inTransaction = true, readOnly = true, isolation = Connection.TRANSACTION_REPEATABLE_READ)
            }
        }
    }

    Given("a JVM whose default time zone is Asia/Seoul (TZ-FIX-001)") {
        Then("detail, list and history instants are the stored UTC instants") {
            val e = fullMerchant().entitled
            withJvmTimeZone("Asia/Seoul") {
                ZoneId.systemDefault() shouldNotBe ZoneId.of("UTC")
                query.detail(e.merchantId)!!.merchant.createdAt shouldBe OnboardingFixture.t0
                query.search(MerchantSearchFilter(merchantId = e.merchantId)).items.single().createdAt shouldBe OnboardingFixture.t0
                query.history(e.merchantId, 0)!!.items.first().occurredAt shouldBe OnboardingFixture.t0
            }
        }
    }

    Given("a business with three merchants and another business") {
        Then("exact filters, the masked number, MID counts and keyset paging (same page shape for found and not found)") {
            val full = fullMerchant().entitled
            val business = full.ready.businessId
            val extra = (1..2).map {
                MerchantId(succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Extra $it"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique()))))
            }
            val brn = registrationNumberOf(business)
            val all = query.search(MerchantSearchFilter(businessId = business))
            all.items.map { it.merchantId } shouldBe (extra + full.merchantId).map { it.value }.sorted()
            all.nextCursor shouldBe null
            all.items.single { it.merchantId == full.merchantId.value }.midCount shouldBe 1L
            all.items.filter { it.merchantId != full.merchantId.value }.map { it.midCount }.toSet() shouldBe setOf(0L)
            all.items.map { it.registrationNumberMasked }.toSet() shouldBe setOf(BusinessRegistrationNumber(brn).masked())

            val first = query.search(MerchantSearchFilter(businessId = business, limit = 2))
            first.items.size shouldBe 2
            first.nextCursor shouldBe first.items.last().merchantId
            val second = query.search(MerchantSearchFilter(businessId = business, limit = 2, after = MerchantId(first.nextCursor!!)))
            second.items.map { it.merchantId } shouldBe all.items.drop(2).map { it.merchantId }
            second.nextCursor shouldBe null

            query.search(MerchantSearchFilter(registrationNumber = BusinessRegistrationNumber(brn))).items.map { it.merchantId } shouldBe all.items.map { it.merchantId }
            query.search(MerchantSearchFilter(merchantId = full.merchantId, lifecycle = Lifecycle.ACTIVE)).items.single().merchantName shouldBe "Demo Merchant"
            query.search(MerchantSearchFilter(merchantId = full.merchantId, lifecycle = Lifecycle.TERMINATED)).items shouldBe emptyList()

            val other = BusinessId(succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Other")))
            val unknown = (0..9).map { ((brn[it] - '0' + 5) % 10).digitToChar() }.joinToString("")
            val notFound = query.search(MerchantSearchFilter(registrationNumber = BusinessRegistrationNumber(unknown)))
            notFound.items shouldBe emptyList()
            notFound.nextCursor shouldBe null
            query.search(MerchantSearchFilter(businessId = other)).items shouldBe emptyList()
        }
    }

    Given("MERCHANT-NAME-SEARCH-001 MNS-1 merchant name prefix search (displayNamePrefix)") {
        // 공유 DB이므로 시험마다 고유 상호 접두를 쓴다(BusinessReaderSpec 관례). 데이터는 공식 use case로만 만든다(Demo 값).
        fun prefix() = "N" + UUID.randomUUID().toString().take(8) + "-"
        fun register(business: BusinessId, name: String): String =
            succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName(name), MerchantCodeFixtures.unique())))
        fun all(filter: MerchantSearchFilter): List<String> = buildList {
            var f = filter
            while (true) {
                val page = query.search(f)
                addAll(page.items.map { it.merchantId })
                f = f.copy(after = MerchantId(page.nextCursor ?: break))
            }
        }
        fun byName(text: String, f: (MerchantSearchFilter) -> MerchantSearchFilter = { it }) = all(f(MerchantSearchFilter(displayNamePrefix = DisplayName(text))))

        Then("MNS-1-R1 literal prefix: a middle match is excluded, case-sensitive (utf8mb4_0900_bin), %, _ and ! are escaped") {
            val p = prefix()
            val business = OnboardingFixture.registeredBusinessId(onboarding)
            val upper = register(business, "${p}Apple")
            val lower = register(business, "${p}apple")
            val middle = register(business, "X${p}Apple")
            val percent = register(business, "${p}50%할인")
            val percentDecoy = register(business, "${p}50X할인")
            val underscore = register(business, "${p}5_0")
            val underscoreDecoy = register(business, "${p}5a0")
            val bang = register(business, "${p}!표")
            byName("${p}Apple") shouldBe listOf(upper)
            byName("${p}apple") shouldBe listOf(lower)
            byName("${p}A") shouldBe listOf(upper)
            byName("${p}50%") shouldBe listOf(percent)
            byName("${p}5_") shouldBe listOf(underscore)
            byName("${p}!") shouldBe listOf(bang)
            // 리뷰 NIT-2: 백슬래시(MySQL LIKE 기본 escape 문자)도 ESCAPE '!' 아래에서는 글자 그대로다.
            val backslash = register(business, "${p}b\\c")
            val backslashDecoy = register(business, "${p}bc")
            byName("${p}b\\") shouldBe listOf(backslash)
            byName("${p}b\\c") shouldBe listOf(backslash)
            byName(p) shouldBe listOf(upper, lower, percent, percentDecoy, underscore, underscoreDecoy, bang, backslash, backslashDecoy).sorted()
            byName(p) shouldNotContain middle
            byName("X$p") shouldBe listOf(middle)
        }

        Then("MNS-1-R2 keyset with limit 2 walks every match once in merchant_id order; the last page has no next cursor") {
            val p = prefix()
            val business = OnboardingFixture.registeredBusinessId(onboarding)
            val ids = (1..5).map { register(business, "${p}가게$it") }.sorted()
            val pages = buildList {
                var f = MerchantSearchFilter(displayNamePrefix = DisplayName(p), limit = 2)
                while (true) {
                    val page = query.search(f)
                    add(page)
                    f = f.copy(after = MerchantId(page.nextCursor ?: break))
                }
            }
            pages.map { it.items.size } shouldBe listOf(2, 2, 1)
            pages.dropLast(1).forEach { it.nextCursor shouldBe it.items.last().merchantId }
            pages.last().nextCursor shouldBe null
            pages.flatMap { page -> page.items.map { it.merchantId } } shouldBe ids
            pages.flatMap { it.items }.map { it.matchedRetiredCode }.toSet() shouldBe setOf(null)
        }

        Then("MNS-1-R3 AND with businessId, registrationNumber, lifecycle and merchantId; found and not found have the same page shape") {
            val p = prefix()
            val numberA = OnboardingFixture.registrationNumber()
            val numberB = OnboardingFixture.registrationNumber()
            val a = OnboardingFixture.registeredBusinessId(onboarding, numberA)
            val b = OnboardingFixture.registeredBusinessId(onboarding, numberB)
            val a1 = register(a, "${p}가1")
            val a2 = register(a, "${p}가2")
            val b1 = register(b, "${p}가3")
            val b2 = register(b, "${p}나")
            byName(p) shouldBe listOf(a1, a2, b1, b2).sorted()
            byName(p) { it.copy(businessId = a) } shouldBe listOf(a1, a2).sorted()
            byName("${p}가") { it.copy(registrationNumber = numberB) } shouldBe listOf(b1)
            byName(p) { it.copy(lifecycle = Lifecycle.ACTIVE) } shouldBe listOf(a1, a2, b1, b2).sorted()
            byName(p) { it.copy(lifecycle = Lifecycle.TERMINATED) } shouldBe emptyList()
            byName("${p}나") { it.copy(merchantId = MerchantId(a1)) } shouldBe emptyList()
            byName("${p}가") { it.copy(merchantId = MerchantId(a1)) } shouldBe listOf(a1)

            val notFound = query.search(MerchantSearchFilter(displayNamePrefix = DisplayName("${p}없음")))
            notFound.items shouldBe emptyList()
            notFound.nextCursor shouldBe null
            val found = query.search(MerchantSearchFilter(displayNamePrefix = DisplayName("${p}나")))
            found.items.single().let { listOf(it.merchantId, it.merchantName, it.businessId, it.registrationNumberMasked, it.matchedRetiredCode) } shouldBe
                listOf(b2, "${p}나", b.value, numberB.masked(), null)
            found.nextCursor shouldBe null
        }

        Then("MNS-1-R4 index ix_merchant_name_id (display_name, merchant_id) exists and is a possible key of the name search (plan recorded)") {
            MasterDbAccounts.root(container).use { c ->
                c.column(
                    "SELECT column_name FROM information_schema.statistics WHERE table_schema = 'paycore_master' AND table_name = 'merchant' " +
                        "AND index_name = 'ix_merchant_name_id' ORDER BY seq_in_index",
                )
            } shouldBe listOf("display_name", "merchant_id")

            val p = prefix()
            val business = OnboardingFixture.registeredBusinessId(onboarding)
            (1..3).forEach { register(business, "${p}계획$it") }
            recorder.inlinedStatements.clear()
            recorder.recording = true
            try {
                query.search(MerchantSearchFilter(displayNamePrefix = DisplayName(p), limit = 2)).items.size shouldBe 2
            } finally {
                recorder.recording = false
            }
            val sql = recorder.inlinedStatements.single { it.contains("`merchant`") }
            sql.lowercase() shouldContain "escape '!'"
            val plan = MasterDbAccounts.root(container).use { c ->
                c.prepareStatement("ANALYZE TABLE `paycore_master`.`merchant`").use { it.execute() }
                c.prepareStatement("USE `paycore_master`").use { it.execute() }
                c.prepareStatement("EXPLAIN $sql").use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(mapOf("table" to rs.getString("table"), "type" to rs.getString("type"), "possible_keys" to rs.getString("possible_keys"),
                                    "key" to rs.getString("key"), "rows" to rs.getString("rows"), "Extra" to rs.getString("Extra")))
                            }
                        }
                    }
                }
            }
            // 증거 기록(행 수가 적으면 optimizer가 key를 바꾼다 — possible_keys 포함만 단언, L- filesort는 LIVE Gate EXPLAIN).
            println("MNS-1-R4 EXPLAIN plan: $plan")
            withClue("$sql\n$plan") {
                plan.single { it["table"] == "merchant" }["possible_keys"].orEmpty().split(",") shouldContain "ix_merchant_name_id"
            }
        }
    }

    Given("the change history of a fully onboarded Merchant") {
        Then("only this merchant's aggregates, without detail, ordered by seq with an exclusive cursor") {
            val e = fullMerchant().entitled
            val otherMerchant = fullMerchant().entitled
            val page = query.history(e.merchantId, 0).shouldNotBeNull()
            val types = page.items.map { it.aggregateType }.toSet()
            types shouldContainAll setOf("Business", "Merchant", "MerchantSite", "Contract", "ContractServiceAgreement", "MerchantAccount", "SiteAssignment",
                "PaymentMethodActivation", "CurrencyEnablement", "PolicyAssignment", "TransactionLimitPolicy", "CardRouteActivation")
            page.items.map { it.aggregateId } shouldNotContain otherMerchant.merchantId.value
            page.items.map { it.aggregateId } shouldNotContain otherMerchant.mid.value
            page.items.map { it.seq } shouldBe page.items.map { it.seq }.sorted()
            page.toString() shouldNotContain AuthorizationTermsCommandFixture.institutionRef.value
            val after = query.history(e.merchantId, page.items.first().seq).shouldNotBeNull()
            after.items.map { it.seq } shouldBe page.items.drop(1).map { it.seq }
        }
    }
})
