package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

/**
 * MERCHANT-CODE-001 MC-1-5(§3.5 C-24·C-26): **V12 시점**(V12는 빈 번호 — Flyway target 11)(MC-1 전 배포 — V13 없음) DB에 Demo 가맹점 Fixture를 넣고 V13·V14를 적용한다. 운영 초기화 스크립트 계정·운영 migration 위치 그대로
 * (payment `OutboxNotificationMigrationSpec` 선례 — 별도 컨테이너). 확인: 채우기 값 = 각 행의 `'m' + LEFT(SHA2(merchant_id,256),13)`을 **Kotlin으로 독립 계산**한 값(결정성),
 * §3.1 형식·`mer_` 미포함, seq 1 없는 `merchant` 행 0, 코드 충돌 0, `source = BACKFILL`·`assigned_at = merchant.created_at`·`assigned_by = system:migration`,
 * 채우기 INSERT가 `uk_mca_code`를 만든 CREATE TABLE 뒤에 있음(충돌 시 migration 실패 = fail-closed — 같은 코드 1062는 MC-1-4가 증명).
 */
class MerchantCodeMigrationSpec : FunSpec({

    val container = MasterDbAccounts.newContainer().also { it.start() }
    afterSpec { container.stop() }

    fun flyway(target: String? = null): Flyway = Flyway.configure()
        .dataSource(
            MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE), MasterDbAccounts.MASTER_MIGRATION_USER,
            MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER),
        )
        .locations(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES)
        .initSql("SET time_zone = '+00:00'")
        .also { c -> target?.let { c.target(it) } }
        .load()

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use { it.createStatement().use { s -> s.execute("SET time_zone = '+00:00'") }; block(it) }

    fun expectedCode(merchantId: String): String =
        "m" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(merchantId.toByteArray(Charsets.UTF_8))).take(13)

    test("MC-1-5 a V12 database with Demo merchants: V13/V14 backfill one deterministic hashed seq-1 code per merchant, fail-closed under uk_mca_code") {
        // V12는 비어 있다(MERCHANT-OPS-002 C-42) — "V12 시점" = V11 schema·V10 권한까지 적용된 MC-1 직전 배포.
        flyway(target = "11").migrate().migrationsExecuted shouldBe flyway(target = "11").info().applied().size
        root { c -> c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'paycore_master' AND table_name = 'merchant_code_assignment'").use { rs -> rs.next(); rs.getInt(1) } } } shouldBe 0

        // Demo 가맹점 Fixture(시험 값): 사업자 2개, 가맹점 40개(서로 다른 생성 시각). 행은 V12 스키마의 열 그대로(MasterSchemaRows와 같은 열 목록).
        val merchants = root { c ->
            val businesses = (1..2).map { n ->
                MasterSchemaRows.id("biz").also { id ->
                    c.prepareStatement("INSERT INTO paycore_master.business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version) VALUES (?, ?, ?, 'ACTIVE', ?, 'demo-fixture', 0)").use { ps ->
                        ps.setString(1, id); ps.setString(2, MasterSchemaRows.registrationNumber()); ps.setString(3, "Demo business $n"); ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")))
                        ps.executeUpdate()
                    }
                }
            }
            (1..40).map { n ->
                val id = MasterSchemaRows.id("mer")
                val at = Instant.parse("2026-10-01T00:00:00.000Z").plusSeconds(n * 61L).plusMillis(n.toLong())
                c.prepareStatement("INSERT INTO paycore_master.merchant (merchant_id, business_id, display_name, lifecycle, entitlement_revision, created_at, created_by, version) VALUES (?, ?, ?, 'ACTIVE', 0, ?, 'demo-fixture', 0)").use { ps ->
                    ps.setString(1, id); ps.setString(2, businesses[n % 2]); ps.setString(3, "Demo merchant $n"); ps.setTimestamp(4, Timestamp.from(at))
                    ps.executeUpdate()
                }
                id to at
            }.toMap()
        }

        flyway().migrate()

        val rows = root { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT a.merchant_id, a.assignment_seq, a.merchant_code, a.source, a.replaces_code, a.approval_id, a.reason_code, " +
                        "DATE_FORMAT(a.assigned_at, '%Y-%m-%dT%H:%i:%s.%fZ'), a.assigned_by, (a.assigned_at = m.created_at) " +
                        "FROM paycore_master.merchant_code_assignment a JOIN paycore_master.merchant m ON m.merchant_id = a.merchant_id",
                ).use { rs ->
                    buildList {
                        while (rs.next()) add((1..10).map { rs.getString(it) })
                    }
                }
            }
        }
        rows.size shouldBe merchants.size
        rows.associate { it[0] to it[2] } shouldBe merchants.keys.associateWith(::expectedCode)
        rows.filter { r -> listOf(r[1], r[3], r[4], r[5], r[6], r[8], r[9]) != listOf("1", "BACKFILL", null, null, null, "system:migration", "1") }.shouldBeEmpty()
        rows.map { it[2] }.filterNot { Regex("^m[0-9a-f]{13}$").matches(it) && Regex("^[a-z][a-z0-9]{5,13}$").matches(it) }.shouldBeEmpty()
        rows.map { it[2] }.filter { it.contains("mer_", ignoreCase = true) }.shouldBeEmpty()
        rows.map { it[2] }.toSet().size shouldBe rows.size

        root { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT COUNT(*) FROM paycore_master.merchant m WHERE NOT EXISTS (SELECT 1 FROM paycore_master.merchant_code_assignment a WHERE a.merchant_id = m.merchant_id AND a.assignment_seq = 1)",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        } shouldBe 0
        // 같은 식을 SQL로 다시 계산해도 같은 값(재실행·다른 새 DB에서도 같은 코드 — 무작위·시각 입력 없음).
        root { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT COUNT(*) FROM paycore_master.merchant_code_assignment WHERE merchant_code <> CONCAT('m', LEFT(SHA2(merchant_id, 256), 13))",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        } shouldBe 0
    }

    test("MC-1-5 fail-closed ordering: the V13 backfill INSERT (explicit column list) runs after the table with uk_mca_code exists; no SIGNAL in the schema migration") {
        val sql = File(requireNotNull(MerchantCodeMigrationSpec::class.java.getResource("/db/migration/master/V13__merchant_code_assignment.sql")).toURI()).readText()
        val create = sql.indexOf("CREATE TABLE merchant_code_assignment")
        val unique = sql.indexOf("CONSTRAINT uk_mca_code UNIQUE (merchant_code)")
        val backfill = sql.indexOf(
            "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by)",
        )
        listOf(create >= 0, unique > create, backfill > unique, !sql.contains("SIGNAL", ignoreCase = true), !sql.contains("RAND(", ignoreCase = true)) shouldBe
            listOf(true, true, true, true, true)
    }
})
