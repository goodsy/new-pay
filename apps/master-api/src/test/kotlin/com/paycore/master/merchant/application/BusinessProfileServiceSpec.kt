package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.persistence.PersistenceFailures
import com.paycore.master.merchant.application.OnboardingFixture.key
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessFixtures.businessType
import com.paycore.master.merchant.domain.BusinessFixtures.individualProfile
import com.paycore.master.merchant.domain.BusinessFixtures.joint
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessProfileRuleViolation
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.DRAFT
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.REGISTERED
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterFixture
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.scalar
import com.paycore.master.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.jdbc.datasource.DataSourceUtils
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLIntegrityConstraintViolationException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * BUSINESS-PROFILE-001 BIZ-1 Use Case 시험(실제 MySQL 8.4, runtime 계정 권한 — M-3: web 없이 Use Case 수준):
 * BIZ-1-5(실행기 결함 분류 — 재판정·재시도 0회·503·제약 이름만 로그), BIZ-1-7(TC-17 rollback), BIZ-1-11(멱등 `h1:` — SEC-BIZ-001 전 `m1:`·지문 평탄화·canary), BIZ-1-12(TC-16 + M-1 등록 완료),
 * BIZ-1-13(변경 계산·이력 — TC-12·TC-13, M-2), BIZ-1-14(TC-19 — 금융·권리 데이터 불변, Outbox `Business*`만·payload 키 집합).
 * 변이: (f) complete에 UNCHANGED 적용 → BIZ-1-12 RED, (g) REGISTERED 대표자 추가만 일반 → BIZ-1-13 RED, (j) BUSINESS_PROFILE_ROW businessKey=true → BIZ-1-5 RED,
 * (k) Snapshot/다른 이벤트 발행 추가 → BIZ-1-14 RED.
 */
@MasterDbTest
class BusinessProfileServiceSpec(
    private val service: BusinessProfileCommandUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val ids: ScriptedIdGenerator,
    private val dataSource: DataSource,
    private val onboarding: com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase,
    private val digester: com.paycore.master.merchant.application.required.RequestFingerprintDigester,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
        ids.reset()
    }

    val json = JsonMapper.builder().build()

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }

    fun create(
        profile: BusinessProfile = individualProfile(),
        status: BusinessRegistrationStatus = REGISTERED,
        type: BusinessType = BusinessType.INDIVIDUAL,
        number: BusinessRegistrationNumber = BusinessFixtures.registrationNumber(),
        name: String = "사업자 상호",
        k: AdminIdempotencyKey = key(),
    ) = CreateBusinessCommand(request(k), status, type, number, DisplayName(name), profile)

    fun update(
        id: String,
        version: Long,
        profile: BusinessProfile = individualProfile(),
        name: String = "사업자 상호",
        reason: String? = null,
        type: BusinessType = BusinessType.INDIVIDUAL,
        k: AdminIdempotencyKey = key(),
    ) = UpdateBusinessCommand(request(k), BusinessId(id), version, reason?.let(::BusinessChangeReason), type, DisplayName(name), profile)

    fun complete(id: String, version: Long, profile: BusinessProfile = individualProfile(), name: String = "사업자 상호", reason: String? = null, k: AdminIdempotencyKey = key()) =
        CompleteBusinessRegistrationCommand(request(k), BusinessId(id), version, reason?.let(::BusinessChangeReason), BusinessType.INDIVIDUAL, DisplayName(name), profile)

    fun history(id: String): List<Pair<String, Map<*, *>>> = runtime(container).use { c ->
        c.prepareStatement("SELECT change_type, detail FROM master_change_history WHERE aggregate_type = 'Business' AND aggregate_id = ? ORDER BY id").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to json.readValue(rs.getString(2), Map::class.java)) } }
        }
    }

    fun outbox(id: String): List<Pair<String, Map<*, *>>> = runtime(container).use { c ->
        c.prepareStatement("SELECT event_type, payload FROM outbox_event WHERE aggregate_type = 'Business' AND aggregate_id = ? ORDER BY id").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to json.readValue(rs.getString(2), Map::class.java)) } }
        }
    }

    fun version(id: String) = count("SELECT version FROM business WHERE business_id = ?", id)
    fun status(id: String) = text("SELECT registration_status FROM business WHERE business_id = ?", id)
    fun adminRequests(k: AdminIdempotencyKey) = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", k.value)

    /** 사업자 한 건의 모든 MASTER 흔적(business·등록정보·하위 행·이력·Outbox). */
    fun footprint(id: String): List<Long> = listOf(
        count("SELECT COUNT(*) FROM business WHERE business_id = ?", id),
        count("SELECT COUNT(*) FROM business_registration WHERE business_id = ?", id),
        count("SELECT COUNT(*) FROM business_representative WHERE business_id = ?", id),
        count("SELECT COUNT(*) FROM business_contact WHERE business_id = ?", id),
        count("SELECT COUNT(*) FROM business_category c JOIN business_registration r ON r.registration_id = c.registration_id WHERE r.business_id = ?", id),
        count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id),
        count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", id),
    )

    Given("create (DRAFT minimum and REGISTERED — TC-01·TC-02·TC-04)") {
        Then("each succeeds with businessId; history BusinessCreated (masked number, no 'before'), Outbox payload keys = {businessId, registrationStatus, version, occurredAt}") {
            val draft = succeeded(service.create(create(BusinessProfile.EMPTY, status = DRAFT)))
            val individual = succeeded(service.create(create()))
            val corporation = succeeded(service.create(create(BusinessFixtures.corporationProfile(), type = BusinessType.CORPORATION)))
            listOf(draft, individual, corporation).map { listOf(status(it), version(it)) } shouldBe
                listOf(listOf("DRAFT", 0L), listOf("REGISTERED", 0L), listOf("REGISTERED", 0L))
            val (type, detail) = history(individual).single()
            type shouldBe "BusinessCreated"
            listOf(detail["registrationStatus"], detail["version"], (detail["registrationNumberMasked"] as String).endsWith("*******"), detail.containsKey("reason")) shouldBe
                listOf("REGISTERED", 0, true, false)
            (detail["changes"] as List<*>).flatMap { (it as Map<*, *>).keys }.toSet().contains("before") shouldBe false
            (detail["changes"] as List<*>).map { (it as Map<*, *>)["field"] } shouldBe listOf(
                "displayName", "businessType", "registration.openingDate", "registration.phoneNumber", "registration.email", "representatives", "categories", "addresses", "contacts",
            )
            outbox(individual).single().let { (event, payload) -> listOf(event, payload.keys, payload["registrationStatus"], payload["version"]) } shouldBe
                listOf("BusinessCreated", setOf("businessId", "registrationStatus", "version", "occurredAt"), "REGISTERED", 0)
            text("SELECT effect_key FROM outbox_event WHERE aggregate_id = ?", individual) shouldBe "BusinessCreated:$individual:0"
        }

        Then("TC-06/TC-07 base: a duplicate number with another key is a stored BUSINESS_REGISTRATION_NUMBER_DUPLICATE (reference = existing id), replayed for the same key") {
            val number = BusinessFixtures.registrationNumber()
            val first = succeeded(service.create(create(number = number)))
            val second = create(number = number, status = DRAFT, profile = BusinessProfile.EMPTY)
            val rejected = AdminCommandResult.Rejected(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, first, replayed = false)
            service.create(second) shouldBe rejected
            service.create(second) shouldBe rejected.copy(replayed = true)
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", number.value) shouldBe 1
        }

        Then("C-16 a value-rule violation inside the transaction rolls everything back (no admin_request, no rows) — not a stored rejection") {
            val k = key()
            ids.forceNext(BusinessId.PREFIX, MasterFixture.businessId().value)
            shouldThrow<BusinessProfileRuleViolation> { service.create(create(BusinessProfile.EMPTY, status = REGISTERED, k = k)) }
            adminRequests(k) shouldBe 0
        }
    }

    // 기대값 변경(SEC-BIZ-001 M-2 — D-MOPS-010 ②·D-GOV-001 ②): 지문 형식 m1:(무염 SHA-256) → h1:(MASTER 비밀 키 HMAC, canonical 전체). 재생·409 판정은 그대로.
    Given("BIZ-1-11 idempotency (C-9 — keyed h1: fingerprint since SEC-BIZ-001)") {
        Then("same key + same command replays without new rows; same key + only the representative order differs is IDEMPOTENCY_KEY_REUSED") {
            val k = key()
            val two = individualProfile().copy(representatives = listOf(joint("가공동"), joint("나공동")))
            val command = create(two, k = k)
            val id = succeeded(service.create(command))
            val before = footprint(id)
            service.create(command.copy(request = command.request.copy(correlationId = "corr-retry"))) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            service.create(command.copy(profile = two.copy(representatives = two.representatives.reversed()))) shouldBe AdminCommandResult.IdempotencyKeyReused
            footprint(id) shouldBe before
        }

        Then("the stored fingerprint is h1: HMAC of the flattened fields (not the unkeyed m1 digest); list counts prefix rows (1 vs 2 rows never collide); no canary value is stored or logged") {
            val k = key()
            // 공유 DB: BIZ-2 ApiSpec(BIZ-2-8)이 고정 캔어리 번호를 먼저 쓸 수 있다(실행 순서 비결정) — 이 시험은 매번 새 번호를 캔어리로 쓴다.
            val canaryNumber = BusinessFixtures.registrationNumber()
            val command = create(BusinessFixtures.canaryProfile(), number = canaryNumber, k = k)
            val logs = CapturedLogs()
            val id = logs.use { succeeded(service.create(command)) }
            val stored = text("SELECT request_fingerprint FROM admin_request WHERE operation = 'CreateBusiness' AND idempotency_key = ?", k.value)!!
            stored shouldStartWith "h1:"
            stored shouldBe AdminCommandExecutor.fingerprintOf(digester, AdminOperation.CREATE_BUSINESS, BusinessCommandFingerprintFields.of(command))
            (stored == AdminCommandExecutor.legacyFingerprintOf(AdminOperation.CREATE_BUSINESS, BusinessCommandFingerprintFields.of(command))) shouldBe false
            // 경계 모호성 없음: 대표자 [가, 나]를 한 행 이름 "가|나"처럼 이어 붙여도 같은 필드 목록이 되지 않는다(개수 접두).
            val oneRow = create(individualProfile().copy(representatives = listOf(joint("가공동"))))
            val twoRows = oneRow.copy(profile = oneRow.profile.copy(representatives = listOf(joint("가공동"), joint("가공동"))))
            (BusinessCommandFingerprintFields.of(oneRow) == BusinessCommandFingerprintFields.of(twoRows)) shouldBe false

            val canaries = listOf(
                canaryNumber.value, BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE,
                BusinessFixtures.CANARY_DETAIL_ADDRESS,
            )
            val storedTexts = listOf(
                stored,
                text("SELECT CONCAT(IFNULL(result_reference, ''), IFNULL(result_code, '')) FROM admin_request WHERE idempotency_key = ?", k.value).orEmpty(),
                runtime(container).use { c -> c.scalar("SELECT GROUP_CONCAT(CAST(detail AS CHAR)) FROM master_change_history WHERE aggregate_id = ?", id).toString() },
                runtime(container).use { c -> c.scalar("SELECT GROUP_CONCAT(CAST(payload AS CHAR)) FROM outbox_event WHERE aggregate_id = ?", id).toString() },
            ) + logs.lines()
            canaries.filter { canary -> storedTexts.any { it.contains(canary) } }.shouldBeEmpty()
        }
    }

    Given("BIZ-1-12 TC-16 + M-1 complete registration") {
        Then("DRAFT + the stored content + complete → SUCCEEDED, version +1, exactly one BusinessRegistrationCompleted whose first change is DRAFT → REGISTERED") {
            val id = succeeded(service.create(create(status = DRAFT)))
            val k = key()
            val command = complete(id, 0, k = k)
            service.completeRegistration(command) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            listOf(status(id), version(id)) shouldBe listOf("REGISTERED", 1L)
            val completed = history(id).filter { it.first == "BusinessRegistrationCompleted" }
            completed.size shouldBe 1
            (completed.single().second["changes"] as List<*>).map { it as Map<*, *> } shouldBe
                listOf(mapOf("field" to "registrationStatus", "before" to "DRAFT", "after" to "REGISTERED"))
            outbox(id).map { it.first } shouldBe listOf("BusinessCreated", "BusinessRegistrationCompleted")

            service.completeRegistration(command) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            val before = footprint(id)
            service.completeRegistration(complete(id, 1)) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_DRAFT, null, replayed = false)
            listOf(footprint(id), version(id), status(id)) shouldBe listOf(before, 1L, "REGISTERED")
        }

        Then("complete with an important change and no reason → BUSINESS_CHANGE_REASON_REQUIRED (stays DRAFT); control: update with the same content → BUSINESS_PROFILE_UNCHANGED") {
            val id = succeeded(service.create(create(status = DRAFT)))
            service.completeRegistration(complete(id, 0, name = "바뀐 상호")) shouldBe
                AdminCommandResult.Rejected(MasterRejection.BUSINESS_CHANGE_REASON_REQUIRED, null, replayed = false)
            listOf(status(id), version(id), history(id).size) shouldBe listOf("DRAFT", 0L, 1)
            service.update(update(id, 0)) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_PROFILE_UNCHANGED, null, replayed = false)
            listOf(version(id), history(id).size) shouldBe listOf(0L, 1)
        }

        // BIZ-CRIT-APPROVAL-001 §7.1(D-GOV-025 ④, D-GOV-026 ②): 계약 이력 있는 DRAFT의 등록 완료 + 상호 변경은 사유가 있어도 2인 — 저장된 거절, DRAFT 유지.
        Then("DRAFT with a business contract: complete with a name change (even with a reason) → BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED, stays DRAFT") {
            val id = succeeded(service.create(create(status = DRAFT)))
            OnboardingFixture.contracted(onboarding, BusinessId(id))
            service.completeRegistration(complete(id, 0, name = "바뀐 상호", reason = "상호 변경")) shouldBe
                AdminCommandResult.Rejected(MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED, null, replayed = false)
            listOf(status(id), version(id), history(id).size) shouldBe listOf("DRAFT", 0L, 1)
        }
    }

    // BIZ-NORM-002 M-3(BR-BIZ-311, D-GOV-005 ②, GOV-BIZ-001 G-03): 사용중지 행은 앱 전이 경로가 없어(BR-BIZ-015) 이관·수동 데이터처럼 저장 행을 직접 바꿔 만든다.
    fun markInactive(id: String) = runtime(container).use {
        it.update("UPDATE business SET registration_status = 'INACTIVE' WHERE business_id = ?", id) shouldBe 1
    }

    Given("BIZ-NORM-002 M-3 an INACTIVE business (BR-BIZ-311 — stored business rejection, not a defect)") {
        Then("update → REJECTED BUSINESS_INACTIVE stored in admin_request; the same key replays it; no history, Outbox or row change") {
            val id = succeeded(service.create(create()))
            markInactive(id)
            val before = footprint(id)
            val k = key()
            val command = update(id, 0, name = "새 상호", reason = "상호 변경", k = k)
            service.update(command).toString() shouldBe "Rejected(rejection=BUSINESS_INACTIVE, reference=null, replayed=false)"
            listOf(
                text("SELECT result_kind FROM admin_request WHERE idempotency_key = ?", k.value),
                text("SELECT result_code FROM admin_request WHERE idempotency_key = ?", k.value),
                text("SELECT result_reference FROM admin_request WHERE idempotency_key = ?", k.value),
            ) shouldBe listOf("REJECTED", "BUSINESS_INACTIVE", null)
            service.update(command).toString() shouldBe "Rejected(rejection=BUSINESS_INACTIVE, reference=null, replayed=true)"
            listOf(footprint(id), version(id), status(id), adminRequests(k)) shouldBe listOf(before, 0L, "INACTIVE", 1L)
            history(id).map { it.first } shouldBe listOf("BusinessCreated")
            outbox(id).map { it.first } shouldBe listOf("BusinessCreated")
        }

        Then("complete-registration of an INACTIVE business stays REJECTED BUSINESS_NOT_DRAFT (expected value fixed), stored and replayed the same way") {
            val id = succeeded(service.create(create()))
            markInactive(id)
            val before = footprint(id)
            val k = key()
            val command = complete(id, 0, k = k)
            service.completeRegistration(command) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_DRAFT, null, replayed = false)
            service.completeRegistration(command) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_DRAFT, null, replayed = true)
            text("SELECT result_code FROM admin_request WHERE idempotency_key = ?", k.value) shouldBe "BUSINESS_NOT_DRAFT"
            listOf(footprint(id), version(id), status(id)) shouldBe listOf(before, 0L, "INACTIVE")
        }
    }

    Given("BIZ-1-13 change calculation and history (TC-12·TC-13, M-2)") {
        Then("TC-12 a general-only change without a reason succeeds; history has masked before/after and no reason") {
            val id = succeeded(service.create(create()))
            val after = individualProfile().copy(
                categories = individualProfile().categories + businessType("추가업태"),
                registration = individualProfile().registration.copy(phoneNumber = com.paycore.master.merchant.domain.PhoneNumber("0277778888")),
            )
            service.update(update(id, 0, after)) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            val (type, detail) = history(id).last()
            type shouldBe "BusinessProfileUpdated"
            listOf(detail.containsKey("reason"), detail["version"]) shouldBe listOf(false, 1)
            (detail["changes"] as List<*>).map { it as Map<*, *> } shouldBe listOf(
                mapOf("field" to "registration.phoneNumber", "before" to "****5678", "after" to "****8888"),
                mapOf("field" to "categories", "ended" to emptyList<Any>(), "added" to listOf(mapOf("type" to "BUSINESS_TYPE", "name" to "추가업태", "order" to 2))),
            )
        }

        // BIZ-CRIT-APPROVAL-001 §7.1(D-GOV-016 ③): REGISTERED 상호 변경·대표자 추가만은 사유 유무와 관계없이 2인 — 기대값 `BUSINESS_CHANGE_REASON_REQUIRED` → `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`,
        // 이력 1·version 0 불변. 사유 있으면 성공 + 이력 사유·마스킹 대표자 ended/added 단언은 근거 실행 시험(BusinessCriticalChangeServiceSpec)으로 이동.
        Then("TC-13 important change (name, M-2 representative add only) is BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED with or without a reason (no history, version 0)") {
            val base = individualProfile().copy(representatives = listOf(joint("가공동")))
            val id = succeeded(service.create(create(base)))
            service.update(update(id, 0, base, name = "새 상호")) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED, null, replayed = false)
            // M-2(변이 (g)): REGISTERED 사업자의 대표자 **추가만**(기존 행 유지)도 중요.
            val added = base.copy(representatives = listOf(joint("가공동"), joint("나공동")))
            service.update(update(id, 0, added)) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED, null, replayed = false)
            service.update(update(id, 0, added, name = "새 상호", reason = "공동대표 체제 전환")) shouldBe
                AdminCommandResult.Rejected(MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED, null, replayed = false)
            listOf(history(id).size, version(id)) shouldBe listOf(1, 0L)
            service.update(update(id, 3, name = "또 다른 상호", reason = "x")) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_VERSION_CONFLICT, null, replayed = false)
            service.update(update(BusinessFixtures.individualRegistered().business.id.value, 0)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_FOUND, null, replayed = false)
        }

        Then("X-1/X-10 a business from the legacy RegisterBusiness path is DRAFT with type NULL and one registration row; its first update sets the type without a reason; DRAFT 0 → 1 representative is general") {
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 RegisterBusiness 경로 제거 — 그 경로·V15 백필이 남긴 행 모양(DRAFT·구분 NULL·등록정보 1행, version 0)을 SQL로 넣어 같은 단언을 한다.
            val id = "biz_" + com.paycore.master.merchant.domain.MasterFixture.ulid()
            runtime(container).use { c ->
                c.prepareStatement(
                    "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, registration_status, " +
                        "verification_status, updated_at, updated_by) VALUES (?, ?, '옛 경로 사업자', 'ACTIVE', ?, 'operator-1', 0, NULL, 'DRAFT', 'NOT_CHECKED', ?, 'operator-1')",
                ).use { ps ->
                    val at = java.sql.Timestamp.from(OnboardingFixture.t0)
                    ps.setString(1, id); ps.setString(2, BusinessFixtures.registrationNumber().value); ps.setTimestamp(3, at); ps.setTimestamp(4, at)
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')").use { ps -> ps.setString(1, id); ps.executeUpdate() }
            }
            listOf(status(id), text("SELECT IFNULL(business_type, 'NULL') FROM business WHERE business_id = ?", id), footprint(id).take(2)) shouldBe
                listOf("DRAFT", "NULL", listOf(1L, 1L))
            service.update(update(id, 0, BusinessProfile.EMPTY, name = "옛 경로 사업자")) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            (history(id).last().second["changes"] as List<*>).single() shouldBe mapOf("field" to "businessType", "before" to null, "after" to "INDIVIDUAL")
            val oneRepresentative = BusinessProfile.EMPTY.copy(representatives = listOf(joint("첫대표")))
            service.update(update(id, 1, oneRepresentative, name = "옛 경로 사업자")) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            listOf(version(id), text("SELECT IFNULL(business_type, 'NULL') FROM business WHERE business_id = ?", id)) shouldBe listOf(2L, "INDIVIDUAL")
        }
    }

    Given("BIZ-1 review m-4 monotonic transition time") {
        Then("a server clock earlier than the stored updatedAt still succeeds (no 3819): updated_at stays, ended rows keep effective_to >= effective_from") {
            clock.set(OnboardingFixture.t0.plusSeconds(600))
            // BIZ-CRIT-APPROVAL-001(§7.1 표 밖 Fixture 변경 — 기대값 불변, Coordinator 확인 대상): 대표자 교체가 등록 완료 사업자에서는 2인이라 이력 없는 DRAFT로 만든다(1인 + 사유).
            val id = succeeded(service.create(create(status = DRAFT)))
            clock.set(OnboardingFixture.t0)
            service.update(update(id, 0, individualProfile().copy(representatives = listOf(joint("가공동"))), reason = "대표자 교체")) shouldBe
                AdminCommandResult.Succeeded(id, replayed = false)
            listOf(
                count("SELECT COUNT(*) FROM business_representative WHERE business_id = ? AND effective_to IS NOT NULL AND effective_to = effective_from", id),
                count("SELECT COUNT(*) FROM business WHERE business_id = ? AND updated_at = created_at AND version = 1", id),
            ) shouldBe listOf(1L, 1L)
        }
    }

    Given("BIZ-1-7 TC-17 rollback (one MASTER local transaction)") {
        Then("a failure after the profile rows were flushed rolls back business, registration, child rows, history, outbox and admin_request; the same key then succeeds") {
            val k = key()
            val id = MasterFixture.businessId().value
            ids.forceNext(BusinessId.PREFIX, id)
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_INSERT) { throw IllegalStateException("injected failure after child rows") }
            shouldThrow<IllegalStateException> { service.create(create(BusinessFixtures.corporationProfile(), type = BusinessType.CORPORATION, k = k)) }
            listOf(footprint(id), adminRequests(k)) shouldBe listOf(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), 0L)
            faults.reset()
            ids.forceNext(BusinessId.PREFIX, id)
            service.create(create(BusinessFixtures.corporationProfile(), type = BusinessType.CORPORATION, k = k)) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            footprint(id) shouldBe listOf(1L, 1L, 2L, 2L, 4L, 1L, 1L)
        }

        Then("a DB CHECK violation (3819) inside the same transaction also rolls everything back") {
            val k = key()
            val id = MasterFixture.businessId().value
            ids.forceNext(BusinessId.PREFIX, id)
            val observed = AtomicInteger()
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_INSERT) {
                val connection = DataSourceUtils.getConnection(dataSource)
                try {
                    connection.prepareStatement("UPDATE business_registration SET country_code = 'US' WHERE business_id = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
                } catch (e: java.sql.SQLException) {
                    observed.set(e.errorCode)
                    throw IllegalStateException("CHECK violation", e)
                }
            }
            shouldThrow<IllegalStateException> { service.create(create(k = k)) }
            listOf(observed.get(), footprint(id), adminRequests(k)) shouldBe listOf(3819, listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), 0L)
        }

        Then("an update failing after the diff was flushed leaves version, rows, history and admin_request unchanged") {
            // BIZ-CRIT-APPROVAL-001(§7.1 표 밖 Fixture 변경 — 기대값 불변, Coordinator 확인 대상): 대표자 교체가 등록 완료 사업자에서는 2인이라 이력 없는 DRAFT로 만든다(1인 + 사유).
            val id = succeeded(service.create(create(status = DRAFT)))
            val before = footprint(id)
            val k = key()
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_UPDATE) { throw IllegalStateException("injected failure after diff") }
            shouldThrow<IllegalStateException> { service.update(update(id, 0, individualProfile().copy(representatives = listOf(joint("가공동"))), reason = "교체", k = k)) }
            listOf(footprint(id), version(id), adminRequests(k), count("SELECT COUNT(*) FROM business_representative WHERE business_id = ? AND effective_to IS NOT NULL", id)) shouldBe
                listOf(before, 0L, 0L, 0L)
        }
    }

    Given("BIZ-1-5 executor classification of a BUSINESS_PROFILE_ROW unique violation (M-4)") {
        fun profileRowConflict(): PersistenceFailure = checkNotNull(
            PersistenceFailures.classify(
                "business",
                RuntimeException("flush", SQLIntegrityConstraintViolationException("Duplicate entry 'biz_x#1' for key 'business_contact.uk_bcon_current_primary'", "23000", 1062)),
            ),
        )

        Then("no new-TX re-decision (one attempt), TemporarilyUnavailable (503), a defect log with the constraint class only; control: a business key is retried MAX_ATTEMPTS times") {
            val id = succeeded(service.create(create()))
            val attempts = AtomicInteger()
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_UPDATE) {
                attempts.incrementAndGet()
                throw profileRowConflict()
            }
            val logs = CapturedLogs()
            logs.use {
                shouldThrow<AdminCommandFailure.TemporarilyUnavailable> { service.update(update(id, 0, individualProfile().copy(contacts = listOf(BusinessFixtures.contact("새담당"))))) }
            }
            attempts.get() shouldBe 1
            val defect = logs.lines().filter { it.contains("store constraint defect") }
            defect.size shouldBe 1
            defect.single().contains("constraint=BUSINESS_PROFILE_ROW") shouldBe true
            listOf("biz_x", "새담당", "Duplicate entry").filter { v -> logs.lines().any { it.contains(v) } }.shouldBeEmpty()
            version(id) shouldBe 0L

            attempts.set(0)
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_UPDATE) {
                attempts.incrementAndGet()
                throw PersistenceFailure.UniqueConflict(com.paycore.master.merchant.application.required.StoreConstraint.BUSINESS_REGISTRATION_NUMBER)
            }
            shouldThrow<AdminCommandFailure.TemporarilyUnavailable> { service.update(update(id, 0, individualProfile().copy(contacts = listOf(BusinessFixtures.contact("새담당"))))) }
            attempts.get() shouldBe AdminCommandExecutor.MAX_ATTEMPTS
        }
    }

    Given("BIZ-1-14 TC-19 — no financial, entitlement or merchant data is created or changed") {
        Then("create → update → complete leave merchant/contract/MID/Default/entitlement rows unchanged; new Outbox rows are Business* only with the fixed payload keys") {
            val tables = listOf(
                "merchant", "merchant_site", "contract", "contract_revision", "contract_revision_approval", "contract_service_agreement", "merchant_account",
                "merchant_account_site_assignment", "default_designation", "entitlement_snapshot_issue", "payment_method_activation",
            )
            fun counts() = tables.associateWith { count("SELECT COUNT(*) FROM $it") }
            val maxOutbox = count("SELECT IFNULL(MAX(id), 0) FROM outbox_event")
            val before = counts()

            val id = succeeded(service.create(create(status = DRAFT)))
            succeeded(service.update(update(id, 0, individualProfile().copy(categories = individualProfile().categories + businessType("추가")))))
            succeeded(service.completeRegistration(complete(id, 1, individualProfile().copy(categories = individualProfile().categories + businessType("추가")))))

            counts() shouldBe before
            val newEvents = runtime(container).use { c ->
                c.prepareStatement("SELECT event_type, payload FROM outbox_event WHERE id > ? AND aggregate_id = ? ORDER BY id").use { ps ->
                    ps.setLong(1, maxOutbox); ps.setString(2, id)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to json.readValue(rs.getString(2), Map::class.java).keys) } }
                }
            }
            newEvents.map { it.first } shouldBe listOf("BusinessCreated", "BusinessProfileUpdated", "BusinessRegistrationCompleted")
            newEvents.map { it.second }.toSet() shouldBe setOf(setOf("businessId", "registrationStatus", "version", "occurredAt"))
            count("SELECT COUNT(*) FROM outbox_event WHERE id > ? AND event_type NOT IN ('BusinessCreated', 'BusinessProfileUpdated', 'BusinessRegistrationCompleted') AND aggregate_id = ?", maxOutbox, id) shouldBe 0
            // 리뷰 n-5: 이 사업자 밖의 새 Outbox 행도 0(권리 Snapshot·가맹점 이벤트 등 다른 aggregate 발행 없음 — 변이 (k) 계열).
            count("SELECT COUNT(*) FROM outbox_event WHERE id > ? AND aggregate_id <> ?", maxOutbox, id) shouldBe 0
        }
    }
})
