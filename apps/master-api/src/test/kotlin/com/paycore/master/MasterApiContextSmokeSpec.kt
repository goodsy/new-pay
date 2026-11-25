package com.paycore.master

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.mysql.MySQLContainer

/**
 * 기술 조합 smoke(MASTER-BOOT-001, MASTER-DOMAIN-001 갱신): Boot 4 컨텍스트가 실제 MySQL 8.4에 **MASTER DB(`paycore_master`)로만** 연결되고
 * Flyway(운영 V1 업무 schema·V2 runtime 표 권한 + 시험 전용 V900·V901)·JPA(MASTER Entity를 Flyway schema로 validate)·jOOQ가 기동하는지 확인한다.
 * runtime 계정은 표 단위 권한(role)만 가져 Flyway history·권한 없는 표를 볼 수 없으므로 schema 전체 관찰은 root 연결로 한다(PAYMENT-DBACCOUNT-001).
 */
@MasterDbTest
class MasterApiContextSmokeSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val entityManagerFactory: EntityManagerFactory,
    private val dsl: DSLContext,
    private val container: MySQLContainer,
) : FunSpec({

    fun string(sql: String): String = jdbcTemplate.queryForObject(sql, String::class.java)!!

    test("connects to MySQL 8.4 LTS") {
        string("SELECT VERSION()") shouldStartWith "8.4."
    }

    fun rootStrings(sql: String): List<String> = MasterDbAccounts.root(container).use { it.column(sql) }

    test("the application pool is connected to the MASTER database only, as the runtime account with its runtime role active") {
        string("SELECT DATABASE()") shouldBe MasterDbAccounts.MASTER_DATABASE
        string("SELECT CURRENT_USER()") shouldBe "${MasterDbAccounts.MASTER_RUNTIME_USER}@%"
        string("SELECT CURRENT_ROLE()") shouldBe "`${MasterDbAccounts.MASTER_RUNTIME_ROLE}`@`%`"
    }

    test("Flyway applies the MASTER migration locations with the migration account") {
        flyway.configuration.locations.map { it.descriptor } shouldBe listOf(
            MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES, MasterDbAccounts.TEST_MIGRATIONS, MasterDbAccounts.TEST_PRIVILEGES,
        )
        flyway.info().pending().size shouldBe 0
        // 운영 V1(업무 schema)·V2(runtime 표 단위 권한, MASTER-DOMAIN-001)·V3(권리 Snapshot 표)·V4(그 권한, MASTER-SNAPSHOT-001)
        // + V5(승인 시작 조건 표)·V6(그 권한, MASTER-SNAPSHOT-002) + V7(기관 식별 코드 CHECK, INST-CODE-001) + 시험 전용 V900(probe)·V901(probe runtime 권한).
        // 기대값 변경(MERCHANT-OPS-002 B2-2, 명세 §9.3 C-21): + V9(승인 근거 schema)·V10(그 runtime 권한). V8은 의도적으로 비운다.
        // 기대값 변경(MERCHANT-OPS-002 B3-1, §9.22 C-42): + V11(승인 근거 operation CHECK 확장 — 권한 변경 없음, V12는 의도적으로 비운다).
        // 기대값 변경(MERCHANT-CODE-001 MC-1 — 목록 증가): + V13(가맹점 코드 배정 표)·V14(그 runtime 권한).
        // 기대값 변경(BUSINESS-PROFILE-001 BIZ-1 — 목록 증가): + V15(사업자 기본정보 schema)·V16(그 runtime 권한).
        // 기대값 변경(MERCHANT-NAME-SEARCH-001 — V17): 가맹점명 검색 색인 ix_merchant_name_id(schema만 — 새 표·열 없음 → runtime 권한 변경 없음, V18은 의도적으로 비운다).
        // 기대값 변경(MERCHANT-FLOW-001 FLOW-1 — 목록 증가): + V19(계약 접수 건·전이 이력 schema + 기존 사업자 이관)·V20(그 runtime 권한). V18은 빈 번호 그대로.
        flyway.info().applied().map { it.version.version } shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "9", "10", "11", "13", "14", "15", "16", "17", "19", "20", "900", "901")
        rootStrings("SELECT DISTINCT installed_by FROM paycore_master.flyway_schema_history") shouldBe
            listOf(MasterDbAccounts.MASTER_MIGRATION_USER)
    }

    test("the runtime account sees exactly the granted tables (business tables + test probe, not the Flyway history)") {
        val all = rootStrings("SELECT table_name FROM information_schema.tables WHERE table_schema = 'paycore_master' ORDER BY table_name")
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
            String::class.java,
        ) shouldBe all - "flyway_schema_history"
    }

    test("JPA EntityManagerFactory opens with the MASTER entities validated against the Flyway schema (ddl-auto=validate)") {
        entityManagerFactory.isOpen shouldBe true
        entityManagerFactory.metamodel.entities.map { it.name }.toSet() shouldBe MASTER_ENTITIES
    }

    test("jOOQ DSLContext uses MySQL dialect and executes query") {
        dsl.dialect() shouldBe SQLDialect.MYSQL
        dsl.selectOne().fetchOne(0, Int::class.java) shouldBe 1
    }
}) {
    companion object {
        /** MASTER-DOMAIN-001 JPA Entity(adapter.persistence). 표마다 하나(생성 컬럼은 매핑하지 않는다). */
        // 기대값 변경(MERCHANT-OPS-002 B2-2 §9.3): + JpaApprovalEvidenceEntity(승인 근거, @Immutable).
        val MASTER_ENTITIES = setOf(
            "JpaApprovalEvidenceEntity", "JpaAdminRequestEntity", "JpaBusinessEntity", "JpaContractEntity", "JpaContractRevisionEntity", "JpaContractRevisionApprovalEntity",
            "JpaContractRevisionScopeSiteEntity", "JpaContractServiceAgreementEntity", "JpaDefaultDesignationEntity",
            "JpaMasterChangeHistoryEntity", "JpaMerchantEntity", "JpaMerchantAccountEntity", "JpaSiteAssignmentEntity", "JpaMerchantSiteEntity",
            "JpaOutboxEventEntity", "JpaPaymentMethodActivationEntity",
            // MASTER-SNAPSHOT-001
            "JpaActivationEventEntity", "JpaSiteActivationConfirmationEntity", "JpaConfirmationRevocationEntity", "JpaCurrencyUnitEntity",
            "JpaCurrencyEnablementEntity", "JpaSiteReviewDecisionEntity", "JpaSnapshotIssueEntity", "JpaSnapshotAckEntity",
            // MASTER-SNAPSHOT-002
            "JpaTransactionLimitPolicyEntity", "JpaPolicyVersionEntity", "JpaPolicyAssignmentEntity", "JpaCardRouteActivationEntity", "JpaCardRouteEventEntity",
            // 기대값 변경(MERCHANT-CODE-001 MC-1 — 목록 증가): 가맹점 코드 배정(@Immutable, append-only).
            "JpaMerchantCodeAssignmentEntity",
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-1 — 목록 증가): 사업자 등록정보(1:1)·대표자·업태/종목·주소·등록 담당자(유효기간 행).
            "JpaBusinessRegistrationEntity", "JpaBusinessRepresentativeEntity", "JpaBusinessCategoryEntity", "JpaBusinessAddressEntity", "JpaBusinessContactEntity",
            // 기대값 변경(MERCHANT-FLOW-001 FLOW-1 — 목록 증가): 계약 접수 건(현재 상태, @Version)·전이 이력(@Immutable, INSERT 전용).
            "JpaContractIntakeEntity", "JpaContractIntakeTransitionEntity",
        )
    }
}
