package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.required.EntitlementSourceStore
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessFixtures.actualAddress
import com.paycore.master.merchant.domain.BusinessFixtures.businessItem
import com.paycore.master.merchant.domain.BusinessFixtures.businessType
import com.paycore.master.merchant.domain.BusinessFixtures.contact
import com.paycore.master.merchant.domain.BusinessFixtures.joint
import com.paycore.master.merchant.domain.BusinessFixtures.operator
import com.paycore.master.merchant.domain.BusinessFixtures.registeredAddress
import com.paycore.master.merchant.domain.BusinessFixtures.t0
import com.paycore.master.merchant.domain.BusinessProfileEdit
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.BusinessVerificationStatus
import com.paycore.master.merchant.domain.DisplayName
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer

/**
 * BUSINESS-PROFILE-001 BIZ-1-3(저장 → 재조회·diff·순서 교환·Root 전용 경로 — TC-08·TC-10, M-4, m-3)·BIZ-1-4(version 정확히 +1)·BIZ-1-5(flush 순서 — 기본 담당자·주소 교체·순서 교환이
 * 1062 없이 성공)·BIZ-1-6(종료 열 외 UPDATE 0·번호 UPDATE 없음·DELETE 0 — 실제 SQL 포획). 실제 MySQL 8.4(runtime 계정 권한), 실제 JPA adapter(장애 주입 대역이 아닌 [JpaBusinessRepository]).
 * 변이: (a) force increment 무조건 → BIZ-1-4 RED, (b) 종료 뒤 flush 제거 → BIZ-1-3·5 RED(1062), (e) 종료 대신 DELETE → BIZ-1-3·6 RED, (h) 짝짓기에서 순서 제외 → 순서 교환 RED,
 * (i) `lock`이 하위 행 로드 → Root 전용 RED.
 */
@MasterDbTest
class JpaBusinessRepositorySpec(
    private val repository: JpaBusinessRepository,
    private val transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlementSources: EntitlementSourceStore,
) : BehaviorSpec({

    val capture = MasterSqlCapture(container)

    fun <T> tx(block: () -> T): T = TransactionTemplate(transactionManager).execute { block() }

    fun query(sql: String, vararg args: Any?): List<List<String?>> =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
            }
        }

    fun registrationId(businessId: String): Long = query("SELECT registration_id FROM business_registration WHERE business_id = ?", businessId).single()[0]!!.toLong()

    /** 표별 행: [id, 내용 요약, effective_from(시각), effective_to(시각|open), ended_by]. */
    fun rows(table: String, idColumn: String, ownerColumn: String, owner: Any, content: String): List<List<String?>> = query(
        "SELECT $idColumn, $content, DATE_FORMAT(effective_from, '%H:%i:%s'), IFNULL(DATE_FORMAT(effective_to, '%H:%i:%s'), 'open'), ended_by FROM $table " +
            "WHERE $ownerColumn = ? ORDER BY $idColumn",
        owner,
    )

    fun representatives(businessId: String) = rows("business_representative", "representative_id", "business_id", businessId, "CONCAT(name, '/', representative_type, '/', display_order)")
    fun contacts(businessId: String) = rows("business_contact", "contact_id", "business_id", businessId, "CONCAT(name, '/', is_primary, '/', display_order)")
    fun categories(businessId: String) = rows("business_category", "category_id", "registration_id", registrationId(businessId), "CONCAT(category_type, '/', name, '/', display_order)")
    fun addresses(businessId: String) = rows("business_address", "address_id", "registration_id", registrationId(businessId), "CONCAT(address_type, '/', IFNULL(detail_address, '-'))")
    fun version(businessId: String): Long = query("SELECT version FROM business WHERE business_id = ?", businessId).single()[0]!!.toLong()

    fun stored(edit: BusinessProfileEdit): BusinessProfileEdit = edit.also { tx { repository.insert(it.business, it.profile) } }

    /** 잠근 뒤 Domain 전이 → 저장(사업자 명령과 같은 순서, 한 TX). */
    fun update(business: Business, change: (com.paycore.master.merchant.application.required.BusinessWithProfile) -> BusinessProfileEdit): BusinessProfileEdit = tx {
        val locked = checkNotNull(repository.lockWithProfile(business.id))
        change(locked).also { repository.update(it.business, locked.profile, it.profile) }
    }

    Given("BIZ-1-3 create → read back (TC-08 joint representatives, TC-10 categories/addresses/contacts)") {
        Then("insert stores Root + registration 1:1 + current rows; lockWithProfile returns the same Business and canonical profile") {
            val created = stored(BusinessFixtures.corporationRegistered())
            val read = checkNotNull(tx { repository.lockWithProfile(created.business.id) })
            read.profile shouldBe created.profile
            with(read.business) {
                listOf(registrationNumber, displayName, businessType, registrationStatus, verificationStatus, version, createdAt, updatedAt, updatedBy)
            } shouldBe listOf(
                created.business.registrationNumber, DisplayName("법인 상호"), BusinessType.CORPORATION, BusinessRegistrationStatus.REGISTERED,
                BusinessVerificationStatus.NOT_CHECKED, 0L, t0, t0, operator,
            )
            listOf(read.profile.representatives.size, read.profile.categories.size, read.profile.addresses.size, read.profile.contacts.size) shouldBe listOf(2, 4, 2, 2)
            query("SELECT COUNT(*) FROM business_registration WHERE business_id = ?", created.business.id.value).single()[0] shouldBe "1"
        }

        Then("the legacy registration path stores the migration-row shape: DRAFT, type NULL, one registration row (KR), no child rows") {
            val legacy = BusinessFixtures.legacyDraft()
            tx { repository.insert(legacy) }
            query(
                "SELECT b.registration_status, IFNULL(b.business_type, 'NULL'), b.verification_status, r.country_code, IFNULL(r.phone_number, 'NULL') " +
                    "FROM business b JOIN business_registration r ON r.business_id = b.business_id WHERE b.business_id = ?",
                legacy.id.value,
            ) shouldBe listOf(listOf("DRAFT", "NULL", "NOT_CHECKED", "KR", "NULL"))
            checkNotNull(tx { repository.lockWithProfile(legacy.id) }).profile shouldBe com.paycore.master.merchant.domain.BusinessProfile.EMPTY
        }
    }

    Given("BIZ-1-3 / BIZ-1-5 / BIZ-1-6 update diff (identity = content + order)") {
        Then("kept rows keep id and effective_from; replaced rows are ended (effective_to, ended_by) and new rows added; primary contact and address swaps succeed; nothing is deleted") {
            val created = stored(BusinessFixtures.corporationRegistered())
            val id = created.business.id.value
            val before = listOf(representatives(id), categories(id), addresses(id), contacts(id))
            val at = t0.plusSeconds(1)

            val (_, sql) = capture.capture {
                update(created.business) { locked ->
                    val after = locked.profile.copy(
                        representatives = listOf(joint("이공동"), joint("최공동")),
                        addresses = listOf(registeredAddress("909호"), actualAddress()),
                        contacts = listOf(contact(primary = false), contact("최보조", primary = true, email = "sub@example.test", phone = "0298765432")),
                    )
                    locked.business.updateProfile(locked.profile, BusinessType.CORPORATION, locked.business.displayName, after, BusinessChangeReason("대표자 교체"), 0, at, operator)
                }
            }

            val reps = representatives(id)
            reps.map { it.drop(1) } shouldBe listOf(
                listOf("이공동/JOINT/1", "01:00:00", "open", null),
                listOf("박공동/JOINT/2", "01:00:00", "01:00:01", operator.value),
                listOf("최공동/JOINT/2", "01:00:01", "open", null),
            )
            reps.take(2).map { it[0] } shouldBe before[0].map { it[0] }
            categories(id) shouldBe before[1]
            addresses(id).map { it.drop(1) } shouldBe listOf(
                listOf("REGISTERED/101호", "01:00:00", "01:00:01", operator.value),
                listOf("ACTUAL/2층", "01:00:00", "open", null),
                listOf("REGISTERED/909호", "01:00:01", "open", null),
            )
            contacts(id).map { it.drop(1) } shouldBe listOf(
                listOf("김담당/1/1", "01:00:00", "01:00:01", operator.value),
                listOf("최보조/0/2", "01:00:00", "01:00:01", operator.value),
                listOf("김담당/0/1", "01:00:01", "open", null),
                listOf("최보조/1/2", "01:00:01", "open", null),
            )
            // 물리 삭제 0: 이전 행 ID가 모두 남아 있다(행 수 단조 증가).
            listOf(representatives(id), categories(id), addresses(id), contacts(id)).zip(before).forEach { (now, old) -> now.map { it[0] } shouldContainAll old.map { it[0] } }

            // BIZ-1-6: 하위 표 UPDATE는 종료 열만, business UPDATE에 등록번호 없음, 등록정보 UPDATE에 소속·국가 없음, DELETE 없음.
            fun setColumns(statement: String): Set<String> =
                Regex("(?is)\\bset\\s+(.*?)\\s+where\\b").find(statement)?.groupValues?.get(1)?.let { set -> Regex("(\\w+)\\s*=").findAll(set).map { it.groupValues[1].lowercase() }.toSet() }
                    ?: emptySet()
            val updates = sql.filter { it.trimStart().startsWith("update", ignoreCase = true) }
            val childUpdates = updates.filter { s -> listOf("business_representative", "business_category", "business_address", "business_contact").any { s.contains("update $it ", true) } }
            childUpdates.size shouldBe 4 // 대표자 1·주소 1·담당자 2 종료(등록정보·업태는 그대로)
            childUpdates.map(::setColumns).toSet() shouldBe setOf(setOf("effective_to", "ended_by"))
            updates.filter { it.contains("update business ", true) }.flatMap(::setColumns).toSet().let { listOf("registration_number" in it, "created_at" in it, "business_id" in it) } shouldBe
                listOf(false, false, false)
            updates.filter { it.contains("update business_registration ", true) }.flatMap(::setColumns).toSet().let { listOf("business_id" in it, "country_code" in it) } shouldBe
                listOf(false, false)
            sql.filter { it.trimStart().startsWith("delete", ignoreCase = true) }.shouldBeEmpty()

            checkNotNull(tx { repository.lockWithProfile(created.business.id) }).profile.contacts.single { it.primary }.name.value shouldBe "최보조"
        }

        Then("M-4 order-only swaps (representatives and business types) end both rows and add both — no 1062 on the current-order keys") {
            val created = stored(BusinessFixtures.corporationRegistered())
            val id = created.business.id.value
            update(created.business) { locked ->
                val after = locked.profile.copy(
                    representatives = locked.profile.representatives.reversed(),
                    categories = listOf(businessType("서비스"), businessType("도소매"), businessItem("소프트웨어"), businessItem("전자상거래")),
                )
                locked.business.updateProfile(locked.profile, BusinessType.CORPORATION, locked.business.displayName, after, BusinessChangeReason("순서 정정"), 0, t0, operator)
            }
            representatives(id).map { it[1] to it[3] } shouldBe listOf("이공동/JOINT/1" to "01:00:00", "박공동/JOINT/2" to "01:00:00", "박공동/JOINT/1" to "open", "이공동/JOINT/2" to "open")
            categories(id).filter { it[3] != "open" }.map { it[1] } shouldBe listOf("BUSINESS_TYPE/도소매/1", "BUSINESS_TYPE/서비스/2")
            categories(id).filter { it[3] == "open" }.map { it[1].orEmpty() }.sorted() shouldBe
                listOf("BUSINESS_ITEM/소프트웨어/1", "BUSINESS_ITEM/전자상거래/2", "BUSINESS_TYPE/도소매/2", "BUSINESS_TYPE/서비스/1")
        }
    }

    Given("BIZ-1-4 version advances exactly once per change") {
        Then("Root column change, child-only change at the same millisecond by the same actor, and DRAFT → REGISTERED each add exactly 1") {
            val draft = stored(com.paycore.master.merchant.domain.Business.createDraft(
                com.paycore.master.merchant.domain.MasterFixture.businessId(), BusinessType.INDIVIDUAL, BusinessFixtures.registrationNumber(), DisplayName("임시"),
                BusinessFixtures.individualProfile(), t0, operator,
            ))
            val id = draft.business.id.value
            val renamed = update(draft.business) { it.business.updateProfile(it.profile, BusinessType.INDIVIDUAL, DisplayName("새 이름"), it.profile, BusinessChangeReason("상호 변경"), 0, t0, operator) }
            version(id) shouldBe 1L
            // 하위 행만(업태 추가 — 일반) + 같은 시각·같은 행위자 → Root 열 변경 없음 → 강제 증가 한 번.
            update(renamed.business) {
                it.business.updateProfile(it.profile, BusinessType.INDIVIDUAL, DisplayName("새 이름"), it.profile.copy(categories = it.profile.categories + businessType("추가업태")), null, 1, t0, operator)
            }
            version(id) shouldBe 2L
            update(renamed.business) { it.business.completeRegistration(it.profile, BusinessType.INDIVIDUAL, DisplayName("새 이름"), it.profile, null, 2, t0.plusSeconds(5), operator) }
            listOf(version(id), query("SELECT registration_status FROM business WHERE business_id = ?", id).single()[0]) shouldBe listOf(3L, "REGISTERED")
        }
    }

    Given("BIZ-1-3 m-3 Root-only reads never touch the profile tables") {
        Then("lock and find select only business; lockWithProfile reads the child tables (control); the entitlement source path reads Business without profile tables") {
            val created = stored(BusinessFixtures.individualRegistered())
            val fromBusiness = Regex("(?i)\\bfrom\\s+business\\b(?!_)")
            val (_, lockSql) = capture.capture { tx { repository.lock(created.business.id) } }
            val (_, findSql) = capture.capture { tx { repository.find(created.business.id) } }
            val (_, profileSql) = capture.capture { tx { repository.lockWithProfile(created.business.id) } }
            listOf(
                lockSql.count(MasterSqlCapture::touchesProfileTables), lockSql.count { fromBusiness.containsMatchIn(it) } > 0,
                findSql.count(MasterSqlCapture::touchesProfileTables), findSql.count { fromBusiness.containsMatchIn(it) } > 0,
                profileSql.count(MasterSqlCapture::touchesProfileTables) >= 5,
            ) shouldBe listOf(0, true, 0, true, true)

            val ready = OnboardingFixture.readyMerchant(onboarding)
            OnboardingFixture.succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            val (source, entitlementSql) = capture.capture { tx { entitlementSources.load(ready.merchantId) } }
            listOf(source != null, entitlementSql.count(MasterSqlCapture::touchesProfileTables), entitlementSql.count { fromBusiness.containsMatchIn(it) } > 0) shouldBe
                listOf(true, 0, true)
        }
    }
})
