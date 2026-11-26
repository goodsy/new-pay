package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant

/**
 * MERCHANT-FLOW-001 FLOW-1(§4A.9 이관·§4A.14 Reconciliation — 별도 컨테이너, 운영 초기화 스크립트 계정·운영 migration 위치 그대로 — BusinessSchemaMigrationSpec·MerchantCodeMigrationSpec 선례):
 * ① **기존 사업자가 있는 V17 상태 DB**(DRAFT·REGISTERED, 가맹점 있음·없음)에 V19·V20 → "사업자 수 = 이관 접수 건 수", 사업자마다 이관 건 1개(순번 1·계약 완료·이관 표시·접수 항목·근거·담당자 없음·
 *    `system:migration`·시각 = 사업자 생성 시각) + 전이 이력 이관 행 1개(n-2), business·merchant(`entitlement_revision`)·Outbox·권리 Snapshot 행 불변(효력 없음 — M-1).
 * ② 부분 실패 복구: V19를 문장 k까지만 적용하고 실패시킨(Flyway 실패 기록이 남는) DB마다 — 점검 SQL이 적용 범위를 정확히 판정, 전제 점검 통과 → 되돌림 → 점검 SQL이 V17 상태
 *    (business 행 불변) → `repair` → 다음 단계. 마지막에 V19·V20 전체 적용 성공.
 * ③ 전제 점검 거부: migration 밖 행(이관 모양이 아닌 접수 건 / 전이 이력 행)·runtime 연결·완료된 V19·V20 DB → 위반 보고, 되돌림이 **실행을 거부**(DROP 0).
 * 스크립트: db/recovery/master/contract-intake-v19-{precheck,inspect,revert}.sql — 오류에서 멈추는 실행기(mysql 클라이언트 기본)로 문장 순서대로 실행한다.
 */
class ContractIntakeMigrationSpec : FunSpec({

    val container = MasterDbAccounts.newContainer().also { it.start() }
    val workDir = Files.createTempDirectory("cin-v19-partial").toFile()
    afterSpec {
        container.stop()
        workDir.deleteRecursively()
    }

    fun resource(path: String): File = File(requireNotNull(ContractIntakeMigrationSpec::class.java.getResource(path)) { "missing $path" }.toURI())

    fun statements(sql: String): List<String> =
        sql.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").split(Regex(";[ \\t]*(\\r?\\n|$)")).map { it.trim() }.filter { it.isNotEmpty() }

    fun flyway(vararg locations: String, target: String? = null): Flyway = Flyway.configure()
        .dataSource(
            MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE), MasterDbAccounts.MASTER_MIGRATION_USER,
            MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER),
        )
        .locations(*locations)
        .initSql("SET time_zone = '+00:00'")
        .also { c -> target?.let { c.target(it) } }
        .load()

    fun real(target: String? = null) = flyway(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES, target = target)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        block(c)
    }

    fun rows(sql: String): List<String> = root { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } } }

    /** 스크립트를 문장 순서대로 실행(첫 오류에서 멈춤 — 예외). 마지막 결과 집합의 첫 행(열 이름 → 값)을 돌려준다. */
    fun runScript(c: Connection, name: String): Map<String, String?> {
        var last: Map<String, String?> = emptyMap()
        statements(resource("/db/recovery/master/$name").readText()).forEach { sql ->
            c.createStatement().use { st ->
                if (st.execute(sql)) {
                    st.resultSet.use { rs ->
                        val meta = rs.metaData
                        if (rs.next()) last = (1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getString(it) }
                    }
                }
            }
        }
        return last
    }

    fun inspect(): List<String?> = root { c -> runScript(c, "contract-intake-v19-inspect.sql").values.toList() }
    fun precheck(): Map<String, String?> = root { c -> runScript(c, "contract-intake-v19-precheck.sql") }
    fun revert() = root { c -> runScript(c, "contract-intake-v19-revert.sql") }

    /** 이관 대상이 아닌 기존 표의 행 지문(business·merchant·Outbox·권리 Snapshot — V19 전후 불변이어야 한다). */
    fun untouched(): List<String> = rows(
        "SELECT CONCAT_WS('|', business_id, registration_status, IFNULL(business_type, '-'), lifecycle, version, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f'), updated_by) " +
            "FROM paycore_master.business ORDER BY business_id",
    ) + rows("SELECT CONCAT_WS('|', merchant_id, business_id, entitlement_revision, version) FROM paycore_master.merchant ORDER BY merchant_id") +
        rows("SELECT CONCAT('outbox=', COUNT(*)) FROM paycore_master.outbox_event") +
        rows("SELECT CONCAT('snapshot_issue=', COUNT(*)) FROM paycore_master.entitlement_snapshot_issue") +
        rows("SELECT CONCAT('change_history=', COUNT(*)) FROM paycore_master.master_change_history")

    val v19 = statements(resource("/db/migration/master/V19__contract_intake.sql").readText())

    /** V19의 앞 [k] 문장 + 실패하는 DDL(실제 MySQL은 DDL 실행 **전에** 암묵 commit하므로 앞선 DML(문장 3)은 커밋돼 남는다 — 그 상태를 그대로 만든다). */
    fun failAfter(k: Int) {
        val dir = workDir.resolve("k$k").also { it.mkdirs() }
        resource("/db/migration/master").listFiles()!!.filter { it.name.endsWith(".sql") && !it.name.startsWith("V19__") }.forEach { it.copyTo(dir.resolve(it.name), overwrite = true) }
        dir.resolve("V19__contract_intake.sql").writeText((v19.take(k) + "DROP TABLE v19_simulated_failure").joinToString(";\n", postfix = ";\n"))
        shouldThrow<FlywayException> { flyway("filesystem:${dir.absolutePath}", MasterDbAccounts.RUNTIME_PRIVILEGES, target = "19").migrate() }
        rows("SELECT success FROM paycore_master.flyway_schema_history WHERE version = '19'") shouldBe listOf("0")
    }

    test("FLOW-1 V19 has the 4 statements in order (intake table → history table → intake migration → history migration); no SIGNAL / RAND / NOW in the migration") {
        v19.size shouldBe 4
        v19.map { it.lineSequence().first().trim() } shouldBe listOf(
            "CREATE TABLE contract_intake (", "CREATE TABLE contract_intake_transition (",
            "INSERT INTO contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin,",
            "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at)",
        )
        val sql = resource("/db/migration/master/V19__contract_intake.sql").readText()
        listOf("SIGNAL", "RAND(", "NOW(", "UTC_TIMESTAMP", "CURRENT_TIMESTAMP").filter { sql.contains(it, ignoreCase = true) }.shouldBeEmpty()
        // V18은 빈 번호(V17 주석 — 추가 금지).
        resource("/db/migration/master").listFiles()!!.map { it.name }.filter { it.startsWith("V18__") }.shouldBeEmpty()
    }

    test("FLOW-1 §4A.9 a V17 database with existing businesses: partial V19 failures are inspected, refused when foreign rows exist, reverted, repaired; then V19/V20 migrate every business") {
        real(target = "17").migrate()
        // V17 상태 Demo 형태 행(시험 값): 사업자 4개 — DRAFT(구분 NULL, 가맹점 없음)·DRAFT(가맹점 1)·REGISTERED(가맹점 2)·REGISTERED(가맹점 없음). 생성 시각 모두 다름.
        val businesses = root { c ->
            listOf("DRAFT" to null, "DRAFT" to "INDIVIDUAL", "REGISTERED" to "CORPORATION", "REGISTERED" to "INDIVIDUAL").mapIndexed { n, (status, type) ->
                val id = MasterSchemaRows.id("biz")
                val at = Timestamp.from(Instant.parse("2026-10-01T00:00:00.123Z").plusSeconds(n * 3_601L))
                c.prepareStatement(
                    "INSERT INTO paycore_master.business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, " +
                        "registration_status, verification_status, updated_at, updated_by) VALUES (?, ?, ?, 'ACTIVE', ?, 'demo-fixture', ?, ?, ?, 'NOT_CHECKED', ?, 'demo-fixture')",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, MasterSchemaRows.registrationNumber()); ps.setString(3, "Demo business $n"); ps.setTimestamp(4, at)
                    ps.setLong(5, n.toLong()); ps.setString(6, type); ps.setString(7, status); ps.setTimestamp(8, Timestamp.from(at.toInstant().plusSeconds(5)))
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO paycore_master.business_registration (business_id, country_code) VALUES (?, 'KR')").use { it.setString(1, id); it.executeUpdate() }
                id
            }
        }
        root { c ->
            listOf(businesses[1], businesses[2], businesses[2]).forEachIndexed { n, businessId ->
                c.prepareStatement(
                    "INSERT INTO paycore_master.merchant (merchant_id, business_id, display_name, lifecycle, entitlement_revision, created_at, created_by, version) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, ?, 'demo-fixture', 0)",
                ).use { ps ->
                    ps.setString(1, MasterSchemaRows.id("mer")); ps.setString(2, businessId); ps.setString(3, "Demo merchant $n"); ps.setLong(4, 3L + n)
                    ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-10-02T00:00:00Z"))); ps.executeUpdate()
                }
            }
        }
        val before = untouched()
        val v17 = listOf("0", "0", "4", null, null, null, null)
        inspect() shouldBe v17

        val expected = mapOf(
            1 to listOf("1", "0", "4", "0", "0", null, null),
            2 to listOf("1", "1", "4", "0", "0", "0", "0"),
            3 to listOf("1", "1", "4", "4", "4", "0", "0"),
        )
        // 거부 사례: 지점별로 migration 밖 행을 넣었다가(거부 확인) 지운다(root — 시험 정리 전용).
        val tamper: Map<Int, Pair<String, String>> = mapOf(
            2 to ("INSERT INTO paycore_master.contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, " +
                "desires_card_keyin, created_at, created_by, updated_at, updated_by, version) VALUES ('${businesses[0]}', 1, 'CONSULTING', 0, '2026-11-25', 'PHONE', " +
                "'admin:adm_01JCFL0000000000000000OP01', 0, 0, '2026-11-25 00:00:00', 'admin:x', '2026-11-25 00:00:00', 'admin:x', 0)" to
                "DELETE FROM paycore_master.contract_intake"),
            3 to ("UPDATE paycore_master.contract_intake SET status = 'TERMINATED', version = 1 WHERE business_id = '${businesses[3]}'" to
                "UPDATE paycore_master.contract_intake SET status = 'COMPLETED', version = 0"),
        )

        expected.forEach { (k, flags) ->
            withClue("partial V19 up to statement $k") {
                failAfter(k)
                inspect() shouldBe flags
                precheck().let { listOf(it["flyway_state_mismatch"], it["foreign_rows"], it["ok"]) } shouldBe listOf("0", "0", "1")

                tamper[k]?.let { (apply, undo) ->
                    root { c -> c.createStatement().use { it.executeUpdate(apply) } }
                    val report = precheck()
                    listOf(report["ok"], report["foreign_rows"]) shouldBe listOf("0", "1")
                    shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146 // v19_revert_refused_precheck_failed — 첫 DROP 전에 멈춤
                    inspect().take(2) shouldBe flags.take(2) // DROP 0
                    root { c -> c.createStatement().use { it.executeUpdate(undo) } }
                    inspect() shouldBe flags
                }
                if (k == 1) {
                    MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use {
                        precheck()["runtime_connections"] shouldBe "1"
                        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
                        inspect() shouldBe flags
                    }
                }

                revert()
                inspect() shouldBe v17
                untouched() shouldBe before
                real().repair()
            }
        }

        // 마지막: 실패 기록 없이 V19(전체)·V20 적용.
        // 기대값 변경(§4B.15 ContractIntakeMigrationSpec.kt:158 — 강화, D-GOV-018 ②ⓑ): 이 시험은 V19 시점 정확성 그대로 두고 Flyway target을 V20으로 고정한다(V21이 V19 CHECK·
        // 생성 열을 바꾸므로 — 아래 active_key 판정·완료 DB 되돌림 거부는 V20 schema 기준). V21 이후 guard(1146)·복구는 BusinessContractMigrationSpec이 시험한다.
        real(target = "20").migrate()
        real().info().applied().filter { it.version?.version in setOf("19", "20") }.map { it.version.version to it.state.isApplied } shouldBe listOf("19" to true, "20" to true)
        inspect() shouldBe listOf("1", "1", "4", "4", "4", "4", "4")
        untouched() shouldBe before

        // Reconciliation(§4A.14): 사업자 수 = 이관 접수 건 수 = 이관 이력 행 수, 접수 건 없는 사업자 0, 사업자마다 정확히 1건.
        rows("SELECT COUNT(*) FROM paycore_master.business") shouldBe listOf("4")
        rows("SELECT COUNT(*) FROM paycore_master.contract_intake WHERE migrated = 1") shouldBe listOf("4")
        rows("SELECT COUNT(*) FROM paycore_master.contract_intake_transition WHERE migrated = 1") shouldBe listOf("4")
        rows(
            "SELECT COUNT(*) FROM paycore_master.business b WHERE (SELECT COUNT(*) FROM paycore_master.contract_intake i WHERE i.business_id = b.business_id) <> 1",
        ) shouldBe listOf("0")
        rows(
            "SELECT CONCAT_WS('|', i.business_id, i.intake_seq, i.status, i.migrated, IFNULL(i.received_on, '-'), IFNULL(i.intake_channel, '-'), IFNULL(i.contract_manager, '-'), " +
                "i.version, i.created_by, i.created_at = b.created_at, t.transition_seq, IFNULL(t.from_status, '-'), t.to_status, t.migrated, IFNULL(t.document_ref, '-'), " +
                "IFNULL(t.reason, '-'), t.actor_ref, t.occurred_at = b.created_at, i.active_key = i.business_id) " +
                "FROM paycore_master.contract_intake i JOIN paycore_master.business b ON b.business_id = i.business_id " +
                "JOIN paycore_master.contract_intake_transition t ON t.business_id = i.business_id AND t.intake_seq = i.intake_seq ORDER BY i.business_id",
        ) shouldBe businesses.sorted().map { "$it|1|COMPLETED|1|-|-|-|0|system:migration|1|1|-|COMPLETED|1|-|-|system:migration|1|1" }

        // 완료된 V19·V20 DB는 되돌림 대상이 아니다 — 전제 점검 거부, 되돌림 DROP 0(이관 행은 업무 기록).
        precheck().let { listOf(it["flyway_state_mismatch"], it["ok"]) } shouldBe listOf("1", "0")
        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
        inspect() shouldBe listOf("1", "1", "4", "4", "4", "4", "4")
    }
})
