package com.paycore.master

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.mysql.MySQLContainer

/**
 * 로컬 compose와 같은 조건의 master-api 기동 복구 확인(MASTER-DOMAIN-001 AC — PAYMENT-DBACCOUNT-001 이월: "runtime role 표 권한 0개로 1044").
 *
 * 시험 전용 migration을 **넣지 않고** application.yml의 운영 Flyway 위치(업무 schema V1 + runtime 표 단위 권한 V2)만으로 실제 초기화 스크립트를
 * 넣은 MySQL 8.4.11에 기동한다. runtime 계정은 DB 단위 권한이 없고 V2가 준 표 단위 권한만 있으므로, 이 컨텍스트가 뜨고 업무 표를 읽는다는 것이
 * "MASTER 표 권한이 0개라 DB에 접속조차 못 하던(1044)" 상태가 해소됐다는 증거다. (별도 컨텍스트·컨테이너 하나를 더 쓴다.)
 *
 * 기존 로컬 compose volume: 앱 기동 시 Flyway가 V1·V2를 적용하므로 추가 수동 절차는 없다. 단 PAYMENT-DBACCOUNT-001 이전 volume은
 * .env.example의 (a) down -v 또는 (b) 초기화 스크립트 1회 재실행이 먼저 필요하다(procedure·grantor가 있어야 V2가 성공).
 */
@SpringBootTest
@Import(MasterDbTestConfig::class)
class MasterApiMainMigrationsBootSpec(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val container: MySQLContainer,
) : FunSpec({

    test("only the production Flyway locations are applied (V1·V3·V5·V7·V9·V11 business schema, V2·V4·V6·V10 runtime table privileges)") {
        flyway.configuration.locations.map { it.descriptor } shouldBe listOf(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES)
        // 기대값 변경(B2-2 V9·V10, MERCHANT-OPS-002 §9.3 C-21): 승인 근거 schema(V9)와 그 runtime 권한(V10). V8은 의도적으로 비운다.
        // 기대값 변경(MERCHANT-OPS-002 B3-1 V11, §9.22 C-42): 승인 근거 operation CHECK 확장(schema만 — 권한 변경 없음, V12는 의도적으로 비운다).
        // 기대값 변경(MERCHANT-CODE-001 MC-1 — 목록 증가): + V13(가맹점 코드 배정 schema)·V14(그 runtime 권한).
        // 기대값 변경(BUSINESS-PROFILE-001 BIZ-1 — 목록 증가): + V15(사업자 기본정보 schema)·V16(그 runtime 권한).
        flyway.info().applied().map { it.version.version } shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "9", "10", "11", "13", "14", "15", "16")
    }

    test("the runtime account (role-only, no database-level privilege) connects to the MASTER database and reads business tables") {
        jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String::class.java) shouldBe "${MasterDbAccounts.MASTER_RUNTIME_USER}@%"
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant", Long::class.java) shouldBe 0L
        MasterDbAccounts.root(container).use { c ->
            c.column("SHOW GRANTS FOR '${MasterDbAccounts.MASTER_RUNTIME_USER}'@'%'").none { it.contains("`paycore_master`.*") }
        } shouldBe true
    }
})
