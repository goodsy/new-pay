package com.paycore.master.merchant.adapter.query

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.BusinessCommandFixture
import com.paycore.master.merchant.application.JooqStatementRecorder
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.BusinessSearchFilter
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessPiiMask
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import org.testcontainers.mysql.MySQLContainer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * BUSINESS-PROFILE-001 BIZ-2-7(§6.2~§6.5 — jOOQ 조회 adapter, 실제 MySQL 8.4): 목록 keyset(`business_id` 내림차순·`limit + 1`·next)·필터(상호 앞부분 — `%`·`_`·`!` 이스케이프,
 * 구분·상태·KST 기간 경계)·**가맹점 0개 사업자 포함**(Named Fixture `withoutMerchants`/`withMerchant` — 가맹점 검색에는 없음 대조), **SELECT 목록 단언**(원문 번호·대표자 원문·연락처 없음
 * — SQL 포획), 대표자 SQL 마스킹 = `BusinessPiiMask.name`, 번호 조회 0/1 + `registrationStatus`, 상세(현재 행만·표시 순서·원문·한 RO RR TX), 이력(쪽 20·nextAfter·사유·마스킹 변경·
 * 옛 경로 행). 데이터는 공식 Use Case로만 만든다. 공유 DB이므로 시험마다 고유 상호 접두를 쓴다.
 */
@MasterDbTest
class BusinessReaderSpec(
    private val query: BusinessProfileQueryUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val merchantOps: MerchantOpsQuery,
    private val recorder: JooqStatementRecorder,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : BehaviorSpec({

    /** 옛 `RegisterBusiness`가 남긴 사업자 한 건(V15 백필 모양 — DRAFT·구분 NULL·등록정보 1행·`BusinessRegistered` 이력, 과거 데이터 재현 — BIZ-4 C-27). */
    fun insertLegacyRegistered(): BusinessId {
        val id = "biz_" + com.paycore.master.merchant.domain.MasterFixture.ulid()
        val number = BusinessFixtures.registrationNumber().value
        com.paycore.master.MasterDbAccounts.root(container).use { c ->
            fun exec(sql: String, vararg args: Any?) = c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }
            val at = "2026-10-09 02:00:00.000"
            exec(
                "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, registration_status, " +
                    "verification_status, updated_at, updated_by) VALUES (?, ?, '옛 경로', 'ACTIVE', ?, 'operator-1', 0, NULL, 'DRAFT', 'NOT_CHECKED', ?, 'operator-1')",
                id, number, at, at,
            )
            exec("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')", id)
            exec(
                "INSERT INTO master_change_history (request_record_id, operation, aggregate_type, aggregate_id, change_type, actor_ref, occurred_at, detail) " +
                    "VALUES (?, 'RegisterBusiness', 'Business', ?, 'BusinessRegistered', 'operator-1', ?, JSON_OBJECT('businessId', ?, 'registrationNumberMasked', ?, " +
                    "'displayName', '옛 경로', 'lifecycle', 'ACTIVE'))",
                "req-legacy-" + java.util.UUID.randomUUID(), id, at, id, number.take(3) + "*******",
            )
        }
        return BusinessId(id)
    }

    afterAny { clock.reset(); recorder.recording = false }

    fun prefix() = "R" + UUID.randomUUID().toString().take(8) + "-"
    fun all(filter: BusinessSearchFilter): List<String> = buildList {
        var f = filter
        while (true) {
            val page = query.search(f)
            addAll(page.items.map { it.businessId })
            f = f.copy(after = BusinessId(page.next ?: break))
        }
    }
    fun <T> sql(block: () -> T): Pair<T, List<String>> {
        recorder.statements.clear()
        recorder.observations.clear()
        recorder.recording = true
        try {
            return block() to recorder.statements.toList()
        } finally {
            recorder.recording = false
        }
    }

    /** [column]의 모든 등장이 `LEFT(`·`CHAR_LENGTH(` 안(마스킹 함수 인자)인가 — 그 밖 등장(원문 SELECT)은 [allowedAfter]로 끝나는 비교만 허용. */
    fun onlyMasked(statement: String, column: String, allowedAfter: String? = null): Boolean {
        val token = "`$column`"
        var i = statement.indexOf(token)
        while (i >= 0) {
            val before = statement.substring(maxOf(0, i - 80), i)
            val masked = Regex("(?i)(left|char_length)\\((`[A-Za-z0-9_]+`\\.)*$").containsMatchIn(before)
            val compared = allowedAfter != null && statement.startsWith(allowedAfter, i + token.length)
            if (!masked && !compared) return false
            i = statement.indexOf(token, i + token.length)
        }
        return true
    }

    Given("BIZ-2-7 the list (keyset business_id desc, filters, businesses without merchants)") {
        Then("keyset paging walks every match once in business_id descending order with limit + 1 / next; businesses without merchants are listed (D-BIZ-004)") {
            val p = prefix()
            val ids = (1..5).map { BusinessCommandFixture.withoutMerchants(businesses, name = "${p}사업자$it").businessId.value }
            val (withMerchant, merchantId) = BusinessCommandFixture.withMerchant(businesses, onboarding, name = "${p}가맹점 있음")
            val first = query.search(BusinessSearchFilter(displayNamePrefix = DisplayName(p), limit = 2))
            first.items.size shouldBe 2
            first.next shouldBe first.items.last().businessId
            val walked = all(BusinessSearchFilter(displayNamePrefix = DisplayName(p), limit = 2))
            walked shouldBe (ids + withMerchant.businessId.value).sortedDescending()
            query.search(BusinessSearchFilter(displayNamePrefix = DisplayName(p), limit = 50)).next.shouldBeNull()
            val items = query.search(BusinessSearchFilter(displayNamePrefix = DisplayName(p), limit = 50)).items.associateBy { it.businessId }
            items.getValue(withMerchant.businessId.value).merchantCount shouldBe 1L
            items.getValue(ids.first()).merchantCount shouldBe 0L
            // 대조군: 가맹점 검색은 가맹점 있는 사업자만 보인다(JOIN business) — 사업자 목록은 가맹점 0개도 포함.
            merchantOps.search(MerchantSearchFilter(businessId = BusinessId(ids.first()))).items.shouldBeEmpty()
            merchantOps.search(MerchantSearchFilter(businessId = withMerchant.businessId)).items.map { it.merchantId } shouldBe listOf(merchantId.value)
        }

        Then("items: masked number, type, status, NOT_CHECKED, first current representative masked in SQL (= BusinessPiiMask.name), current representative count") {
            val p = prefix()
            val corp = BusinessCommandFixture.withoutMerchants(businesses, name = "${p}법인", type = BusinessType.CORPORATION, profile = BusinessFixtures.corporationProfile())
            val draft = BusinessCommandFixture.withoutMerchants(businesses, name = "${p}임시", status = BusinessRegistrationStatus.DRAFT)
            val items = query.search(BusinessSearchFilter(displayNamePrefix = DisplayName(p))).items.associateBy { it.businessId }
            items.getValue(corp.businessId.value).let {
                it.registrationNumberMasked shouldBe corp.number.value.take(3) + "*******"
                it.businessType shouldBe "CORPORATION"
                it.registrationStatus shouldBe "REGISTERED"
                it.verificationStatus shouldBe "NOT_CHECKED"
                it.representativeNameMasked shouldBe BusinessPiiMask.name("이공동")
                it.representativeNameMasked shouldBe "이**"
                it.representativeCount shouldBe 2L
            }
            items.getValue(draft.businessId.value).let {
                it.registrationStatus shouldBe "DRAFT"
                it.representativeNameMasked.shouldBeNull()
                it.representativeCount shouldBe 0L
            }
            // 1글자·서로게이트 첫 글자도 Domain 마스킹과 같다.
            listOf("김", "😀대표").forEach { name ->
                val b = BusinessCommandFixture.withoutMerchants(
                    businesses, name = "${p}마스킹$name",
                    profile = BusinessFixtures.individualProfile().copy(representatives = listOf(BusinessFixtures.sole(name))),
                )
                query.search(BusinessSearchFilter(displayNamePrefix = DisplayName("${p}마스킹$name"))).items.single { it.businessId == b.businessId.value }
                    .representativeNameMasked shouldBe BusinessPiiMask.name(name)
            }
        }

        Then("C-5 prefix filter is a literal prefix: %, _ and ! are escaped; type and status filters are exact") {
            val p = prefix()
            fun make(suffix: String, status: BusinessRegistrationStatus = BusinessRegistrationStatus.REGISTERED, type: BusinessType = BusinessType.INDIVIDUAL) =
                BusinessCommandFixture.withoutMerchants(
                    businesses, name = p + suffix, status = status, type = type,
                    profile = if (status == BusinessRegistrationStatus.DRAFT) BusinessProfile.EMPTY else BusinessFixtures.individualProfile(),
                ).businessId.value
            val percent = make("50%할인")
            make("50X할인")
            val underscore = make("5_0")
            make("5a0")
            val bang = make("!표")
            val draftCorp = make("법인임시", BusinessRegistrationStatus.DRAFT, BusinessType.CORPORATION)
            fun names(prefixText: String, f: (BusinessSearchFilter) -> BusinessSearchFilter = { it }) =
                all(f(BusinessSearchFilter(displayNamePrefix = DisplayName(p + prefixText))))
            names("50%") shouldBe listOf(percent)
            names("5_") shouldBe listOf(underscore)
            names("!") shouldBe listOf(bang)
            all(BusinessSearchFilter(displayNamePrefix = DisplayName(p), registrationStatus = BusinessRegistrationStatus.DRAFT)) shouldBe listOf(draftCorp)
            all(BusinessSearchFilter(displayNamePrefix = DisplayName(p), businessType = BusinessType.CORPORATION)) shouldBe listOf(draftCorp)
            all(BusinessSearchFilter(displayNamePrefix = DisplayName(p))).size shouldBe 6
        }

        Then("KST date range: createdTo includes the whole KST day, createdFrom starts at KST 00:00 (UTC boundary 15:00Z)") {
            val p = prefix()
            clock.set(Instant.parse("2026-10-09T14:59:59.999Z")) // KST 2026-10-09 23:59:59.999
            val late = BusinessCommandFixture.withoutMerchants(businesses, name = "${p}늦은").businessId.value
            clock.set(Instant.parse("2026-10-09T15:00:00.000Z")) // KST 2026-10-10 00:00
            val next = BusinessCommandFixture.withoutMerchants(businesses, name = "${p}다음날").businessId.value
            fun range(from: String?, to: String?) = all(
                BusinessSearchFilter(displayNamePrefix = DisplayName(p), createdFrom = from?.let(LocalDate::parse), createdTo = to?.let(LocalDate::parse)),
            )
            range("2026-10-09", "2026-10-09") shouldBe listOf(late)
            range("2026-10-10", null) shouldBe listOf(next)
            range(null, "2026-10-09") shouldBe listOf(late)
            range("2026-10-09", "2026-10-10").toSet() shouldBe setOf(late, next)
        }

        Then("§6.2 SELECT list (SQL capture): the number only as LEFT(…, 3), the representative name only inside the mask; no contact, address, corporate number or email column") {
            val p = prefix()
            BusinessCommandFixture.withoutMerchants(businesses, name = "${p}선택목록", profile = BusinessFixtures.canaryProfile())
            val (page, statements) = sql { query.search(BusinessSearchFilter(displayNamePrefix = DisplayName(p))) }
            page.items.size shouldBe 1
            val search = statements.single { it.contains("`business`") }
            withClue(search) {
                onlyMasked(search, "registration_number") shouldBe true
                onlyMasked(search, "name") shouldBe true
                listOf("email", "phone_number", "base_address", "detail_address", "corporate_registration_number", "business_contact", "business_address")
                    .forEach { search shouldNotContain "`$it`" }
            }
        }
    }

    Given("BIZ-2-7 the registration number lookup (§6.3 — merged MERCHANT-BIZ-LOOKUP-001)") {
        Then("found and not found have the same shape (0/1 items) with registrationStatus (D-BIZ-006 ③); the number is only bound, never selected raw") {
            val b = BusinessCommandFixture.withoutMerchants(businesses, name = "번호조회 대상", status = BusinessRegistrationStatus.DRAFT)
            val (found, statements) = sql { query.lookup(b.number) }
            found.map { listOf(it.businessId, it.businessName, it.registrationNumberMasked, it.registrationStatus) } shouldBe
                listOf(listOf(b.businessId.value, "번호조회 대상", b.number.value.take(3) + "*******", "DRAFT"))
            statements.single().let { s -> withClue(s) { onlyMasked(s, "registration_number", allowedAfter = " = ?") shouldBe true } }
            query.lookup(BusinessFixtures.registrationNumber()).shouldBeEmpty()
        }
    }

    Given("BIZ-2-7 the detail (§6.4)") {
        Then("current rows only in display order (business types → items, REGISTERED → ACTUAL), raw personal data, masked number, merchantCount, version — one read-only REPEATABLE READ TX") {
            val b = BusinessCommandFixture.withoutMerchants(businesses, name = "상세 법인", type = BusinessType.CORPORATION, profile = BusinessFixtures.corporationProfile())
            // 대표자 한 명 교체(종료 행은 상세에 나오지 않는다 — 현재 행만).
            val changed = BusinessFixtures.corporationProfile().copy(representatives = listOf(BusinessFixtures.joint("이공동"), BusinessFixtures.joint("정새로")))
            succeeded(
                businesses.update(
                    UpdateBusinessCommand(request(), b.businessId, 0, BusinessChangeReason("공동대표 교체"), BusinessType.CORPORATION, b.name, changed),
                ),
            )
            val (view, _) = sql { query.detail(b.businessId) }
            recorder.observations.isNotEmpty() shouldBe true
            recorder.observations.forEach { it shouldBe JooqStatementRecorder.Observation(true, true, 4) }
            view.shouldNotBeNull()
            view.business.registrationNumberMasked shouldBe b.number.value.take(3) + "*******"
            view.business.businessType shouldBe "CORPORATION"
            view.business.lifecycle shouldBe "ACTIVE"
            view.business.version shouldBe 1L
            view.business.merchantCount shouldBe 0L
            view.registration.corporateRegistrationNumber shouldBe "1101110000001"
            view.registration.openingDate shouldBe LocalDate.parse("2026-01-01")
            view.registration.englishName shouldBe "Demo Corp"
            view.representatives.map { it.name to it.representativeType } shouldBe listOf("이공동" to "JOINT", "정새로" to "JOINT")
            view.categories.map { it.categoryType to it.name } shouldBe listOf(
                "BUSINESS_TYPE" to "도소매", "BUSINESS_TYPE" to "서비스", "BUSINESS_ITEM" to "소프트웨어", "BUSINESS_ITEM" to "전자상거래",
            )
            view.addresses.map { it.addressType to it.detailAddress } shouldBe listOf("REGISTERED" to "101호", "ACTUAL" to "2층")
            view.contacts.map { listOf(it.name, it.email, it.phoneNumber, it.primary) } shouldBe listOf(
                listOf("김담당", "contact@example.test", "01012345678", true), listOf("최보조", "sub@example.test", "0298765432", false),
            )
            view.toString() shouldNotContain "김담당"
            query.detail(BusinessId("biz_01JC0000000000000000000000")).shouldBeNull()
            query.existingSummary(b.businessId).shouldNotBeNull().registrationStatus shouldBe "REGISTERED"
        }
    }

    Given("BIZ-2-7 the history (§6.5)") {
        Then("pages of 20 with nextAfter, ascending seq; reason and masked changes in a fixed key order; the legacy RegisterBusiness row has no reason and no changes") {
            val b = BusinessCommandFixture.withoutMerchants(businesses, name = "이력0", status = BusinessRegistrationStatus.DRAFT)
            (1..21).forEach { i ->
                succeeded(
                    businesses.update(
                        UpdateBusinessCommand(request(), b.businessId, i - 1L, BusinessChangeReason("상호 정정 $i"), BusinessType.INDIVIDUAL, DisplayName("이력$i"), BusinessProfile.EMPTY),
                    ),
                )
            }
            val first = query.history(b.businessId, 0).shouldNotBeNull()
            first.items.size shouldBe 20
            first.nextAfter shouldBe first.items.last().seq
            first.items.map { it.seq } shouldBe first.items.map { it.seq }.sorted()
            first.items.first().changeType shouldBe "BusinessCreated"
            first.items.first().reason.shouldBeNull()
            first.items[1].changeType shouldBe "BusinessProfileUpdated"
            first.items[1].reason shouldBe "상호 정정 1"
            first.items[1].changes shouldBe listOf(linkedMapOf("field" to "displayName", "before" to "이력0", "after" to "이력1"))
            first.items[1].changes.single().keys.toList() shouldBe listOf("field", "before", "after")
            val second = query.history(b.businessId, first.nextAfter!!).shouldNotBeNull()
            second.items.map { it.reason } shouldBe listOf("상호 정정 20", "상호 정정 21")
            second.nextAfter.shouldBeNull()

            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 RegisterBusiness 경로가 제거돼 과거 데이터(옛 경로가 남긴 business·등록정보·`BusinessRegistered` 이력 행 — V15 백필 모양)를
            // 그대로 넣어 같은 단언을 한다(Reader가 옛 detail 모양을 읽는다).
            val legacy = insertLegacyRegistered()
            query.history(legacy, 0).shouldNotBeNull().items.single().let {
                it.changeType shouldBe "BusinessRegistered"
                it.reason.shouldBeNull()
                it.changes.shouldBeEmpty()
            }
            query.history(BusinessId("biz_01JC0000000000000000000000"), 0).shouldBeNull()
        }

        Then("row changes keep masked values with keys name/type/order (representatives) — the stored masking, never the raw value") {
            val b = BusinessCommandFixture.withoutMerchants(businesses, name = "행이력", profile = BusinessFixtures.canaryProfile())
            val created = query.history(b.businessId, 0).shouldNotBeNull().items.single()
            val reps = created.changes.single { it["field"] == "representatives" }
            reps.keys.toList() shouldBe listOf("field", "added")
            @Suppress("UNCHECKED_CAST")
            val added = (reps["added"] as List<Map<String, Any?>>).single()
            added.keys.toList() shouldBe listOf("name", "type", "order")
            added["name"] shouldBe BusinessPiiMask.name(BusinessFixtures.CANARY_REPRESENTATIVE)
            created.changes.toString() shouldNotContain BusinessFixtures.CANARY_REPRESENTATIVE
            created.changes.toString() shouldNotContain BusinessFixtures.CANARY_PHONE
            created.changes.toString() shouldNotContain BusinessFixtures.CANARY_EMAIL
        }
    }
})
