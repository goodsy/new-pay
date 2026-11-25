package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.scalar
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistrar
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * BUSINESS-PROFILE-001 BIZ-2-9(명세 문구 그대로 — 리뷰 MINOR-4): **V14 상태 DB에 BIZ-2 전 seed 단계 1 결과를 SQL로 넣고**(business 행 V14 열 + `admin_request` SUCCESS
 * `RegisterBusiness`·키 `demo-seed-v1:01-register-business` — 옛 seed가 남긴 모양), 그 뒤 앱 컨텍스트의 Flyway가 V15·V16(·시험 migration)을 적용한다. 그 DB에서 새 seed를 실행하면
 * 옛 사업자를 재사용하고(`CreateBusiness` 기록 0·`ContentConflict`·`StepRejected` 없음), 그 사업자는 V15가 바꾼 DRAFT·구분 NULL·등록정보 1행으로 남으며, 재실행은 재생이다.
 * 운영 Demo 상수(`DemoMerchantSeedValues` — 운영값 아님)와 운영 키 공간을 그대로 쓴다(이 DB 전용 — 공유 DB 아님).
 * 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27 — D-BIZ-001·L-7): 전환 뒤 DRAFT 사업자에는 **새** 가맹점을 등록할 수 없다. 실제 옛 Demo DB는 가맹점 단계(2)까지 기록돼 있으므로
 * 이 DB도 옛 seed 단계 1·2(사업자 + 가맹점 — V14 행과 SUCCESS 기록)를 넣는다. 새 seed는 두 기록을 재사용하고(가맹점 새로 만들지 않음) 기존 가맹점의 Site·계약·MID 등 나머지 단계를 마친다
 * (기존 가맹점 무영향 — D-BIZ-001). 단계 1만 기록된 DB의 `StepRejected(REGISTER_MERCHANT, BUSINESS_NOT_REGISTERED)`는 공유 DB 시험(BusinessDemoSeedSpec)이 고정한다.
 *
 * V14까지 미리 migrate한 DB가 필요해 공유 시험 컨텍스트를 쓸 수 없다(이미 최신 schema) — 이 Spec 전용 컨텍스트·컨테이너이며 끝나면 닫는다(`@DirtiesContext`, 메모리 상한).
 */
@SpringBootTest
@Import(BusinessLegacySeedMigrationSpec.V14SeededDatabase::class, MasterFaultInjectionConfig::class, DemoSeedBeansTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.flyway.locations=${MasterDbAccounts.MAIN_MIGRATIONS},${MasterDbAccounts.RUNTIME_PRIVILEGES}," +
            "${MasterDbAccounts.TEST_MIGRATIONS},${MasterDbAccounts.TEST_PRIVILEGES}",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
        "spring.datasource.hikari.maximum-pool-size=16",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessLegacySeedMigrationSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val query: BusinessProfileQueryUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val t0: Instant = Instant.parse("2026-10-09T15:00:00Z")
    fun command() = DemoMerchantSeedCommand(
        DemoMerchantSeedValues.KEY_PREFIX, DemoMerchantSeedValues.ACTOR, "demo-seed-" + UUID.randomUUID().toString().take(13), t0, DemoMerchantSeedValues.VALUES,
    )
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    val stepOneKey = DemoSeedStep.REGISTER_BUSINESS.key(DemoMerchantSeedValues.KEY_PREFIX).value

    Given("BIZ-2-9 a V14 database holding the pre-BIZ-2 seed step 1 (RegisterBusiness), migrated to V15/V16 by the application") {
        Then("the migration turned the legacy business into DRAFT / type NULL with one registration row") {
            val d = query.detail(BusinessId(LEGACY_BUSINESS_ID)).shouldNotBeNull()
            d.business.registrationStatus shouldBe "DRAFT"
            d.business.businessType.shouldBeNull()
            d.business.verificationStatus shouldBe "NOT_CHECKED"
            count("SELECT COUNT(*) FROM business_registration WHERE business_id = ?", LEGACY_BUSINESS_ID) shouldBe 1L
        }

        Then("the new seed reuses the recorded business (no CreateBusiness record, no ContentConflict / StepRejected); a rerun replays") {
            val done = seed.seed(command()).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            OnboardingFixture.runtime(container).use { it.scalar("SELECT business_id FROM merchant WHERE merchant_id = ?", done.merchantId.value) } shouldBe LEGACY_BUSINESS_ID
            done.merchantId.value shouldBe LEGACY_MERCHANT_ID
            count("SELECT COUNT(*) FROM merchant") shouldBe 1L
            count("SELECT COUNT(*) FROM admin_request WHERE operation = 'RegisterMerchant'") shouldBe 1L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND operation = 'CreateBusiness'", stepOneKey) shouldBe 0L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND operation = 'RegisterBusiness'", stepOneKey) shouldBe 1L
            count("SELECT COUNT(*) FROM business") shouldBe 1L
            query.detail(BusinessId(LEGACY_BUSINESS_ID)).shouldNotBeNull().business.registrationStatus shouldBe "DRAFT"
            val rows = count("SELECT COUNT(*) FROM admin_request")
            val again = seed.seed(command()).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
            again.replayed shouldBe true
            again.merchantId shouldBe done.merchantId
            count("SELECT COUNT(*) FROM admin_request") shouldBe rows
        }
    }
}) {
    /**
     * 시험 전용 DB 준비: 운영 초기화 스크립트 계정의 새 컨테이너 → 운영 migration(schema + runtime 권한)을 **V14까지** → 옛 seed 단계 1 결과를 root SQL로 넣는다.
     * 앱 컨텍스트의 Flyway가 그 뒤 V15부터 적용한다(같은 `flyway_schema_history`). 값은 운영 Demo 상수(운영값 아님).
     */
    @TestConfiguration(proxyBeanMethods = false)
    class V14SeededDatabase {
        @Bean
        fun legacySeedContainer(): MySQLContainer = MasterDbAccounts.newContainer().also { c ->
            c.start()
            Flyway.configure()
                .dataSource(
                    MasterDbAccounts.urlFor(c, MasterDbAccounts.MASTER_DATABASE), MasterDbAccounts.MASTER_MIGRATION_USER,
                    MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER),
                )
                .locations(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES)
                .initSql("SET time_zone = '+00:00'")
                .target("14")
                .load()
                .migrate()
            val v = DemoMerchantSeedValues.VALUES
            val at = Timestamp.from(Instant.parse("2026-10-01T00:00:00.000Z"))
            MasterDbAccounts.root(c).use { root ->
                root.createStatement().use { it.execute("SET time_zone = '+00:00'") }
                root.prepareStatement(
                    "INSERT INTO paycore_master.business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)",
                ).use { ps ->
                    ps.setString(1, LEGACY_BUSINESS_ID); ps.setString(2, v.registrationNumber.value); ps.setString(3, v.businessName.value)
                    ps.setTimestamp(4, at); ps.setString(5, DemoMerchantSeedValues.ACTOR.value)
                    ps.executeUpdate()
                }
                root.prepareStatement(
                    "INSERT INTO paycore_master.admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind, result_code, " +
                        "result_reference, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, ?, ?, 1)",
                ).use { ps ->
                    ps.setString(1, AdminOperation.REGISTER_BUSINESS.code)
                    ps.setString(2, DemoSeedStep.REGISTER_BUSINESS.key(DemoMerchantSeedValues.KEY_PREFIX).value)
                    ps.setString(3, "req_legacy_seed_step_01")
                    ps.setString(4, AdminCommandExecutor.legacyFingerprintOf(AdminOperation.REGISTER_BUSINESS, listOf(v.registrationNumber.value, v.businessName.value)))
                    ps.setString(5, DemoMerchantSeedValues.ACTOR.value)
                    ps.setString(6, LEGACY_BUSINESS_ID)
                    ps.setTimestamp(7, at); ps.setTimestamp(8, at)
                    ps.executeUpdate()
                }
                // 옛 seed 단계 2(가맹점 — V14 행 + 코드 배정 seq 1 + SUCCESS 기록). 지문은 그 단계 명령 필드(MC-1 — [businessId, displayName, merchantCode]).
                root.prepareStatement(
                    "INSERT INTO paycore_master.merchant (merchant_id, business_id, display_name, lifecycle, entitlement_revision, created_at, created_by, version) " +
                        "VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?, 0)",
                ).use { ps ->
                    ps.setString(1, LEGACY_MERCHANT_ID); ps.setString(2, LEGACY_BUSINESS_ID); ps.setString(3, v.merchantName.value)
                    ps.setTimestamp(4, at); ps.setString(5, DemoMerchantSeedValues.ACTOR.value)
                    ps.executeUpdate()
                }
                root.prepareStatement(
                    "INSERT INTO paycore_master.merchant_code_assignment (merchant_id, assignment_seq, merchant_code, source, assigned_at, assigned_by) VALUES (?, 1, ?, 'REGISTRATION', ?, ?)",
                ).use { ps ->
                    ps.setString(1, LEGACY_MERCHANT_ID); ps.setString(2, v.merchantCode.value); ps.setTimestamp(3, at); ps.setString(4, DemoMerchantSeedValues.ACTOR.value)
                    ps.executeUpdate()
                }
                root.prepareStatement(
                    "INSERT INTO paycore_master.admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind, result_code, " +
                        "result_reference, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, ?, ?, 1)",
                ).use { ps ->
                    ps.setString(1, AdminOperation.REGISTER_MERCHANT.code)
                    ps.setString(2, DemoSeedStep.REGISTER_MERCHANT.key(DemoMerchantSeedValues.KEY_PREFIX).value)
                    ps.setString(3, "req_legacy_seed_step_02")
                    ps.setString(
                        4,
                        AdminCommandExecutor.legacyFingerprintOf(AdminOperation.REGISTER_MERCHANT, listOf(LEGACY_BUSINESS_ID, v.merchantName.value, v.merchantCode.value)),
                    )
                    ps.setString(5, DemoMerchantSeedValues.ACTOR.value)
                    ps.setString(6, LEGACY_MERCHANT_ID)
                    ps.setTimestamp(7, at); ps.setTimestamp(8, at)
                    ps.executeUpdate()
                }
            }
        }

        @Bean
        fun legacySeedDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
            registry.add("PAYCORE_MASTER_DB_URL") { MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE) }
            registry.add("PAYCORE_MASTER_DB_USERNAME") { MasterDbAccounts.MASTER_RUNTIME_USER }
            registry.add("PAYCORE_MASTER_DB_PASSWORD") { MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_RUNTIME_USER) }
            registry.add("PAYCORE_MASTER_DB_MIGRATION_USERNAME") { MasterDbAccounts.MASTER_MIGRATION_USER }
            registry.add("PAYCORE_MASTER_DB_MIGRATION_PASSWORD") { MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER) }
        }
    }

    companion object {
        /** 옛 seed가 만든 사업자 ID(시험 값 — ULID 형식). */
        const val LEGACY_BUSINESS_ID = "biz_01JC00000000000000000SEED1"

        /** 옛 seed가 만든 가맹점 ID(시험 값 — ULID 형식, BIZ-4 C-27 — 가맹점 단계까지 기록된 옛 Demo DB). */
        const val LEGACY_MERCHANT_ID = "mer_01JC00000000000000000SEED2"
    }
}
