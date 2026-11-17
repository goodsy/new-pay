package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
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
 * BUSINESS-PROFILE-001 BIZ-1-2(별도 컨테이너 — 운영 초기화 스크립트 계정·운영 migration 위치 그대로, MerchantCodeMigrationSpec 선례):
 * ① **기존 행이 있는 V14 상태 DB**에 V15·V16 → 기존 행 DRAFT·구분 NULL·NOT_CHECKED·updated = created, 모든 business에 등록정보 정확히 1행(TC-18).
 * ② L-8 부분 실패 복구(m-7·r-1): V15를 문장 k까지만 적용하고 실패시킨(Flyway 실패 기록이 남는) DB마다 — 점검 SQL이 적용 범위를 정확히 판정,
 *    전제 점검 통과 → 되돌림 → 점검 SQL이 V14 상태(기존 business 행·열 값 불변) → `repair` → 다음 단계. 마지막에 V15 전체 재실행 성공.
 * ③ 전제 점검 거부(r-1): migration 밖 값(하위 표 1행 / 등록정보 선택 열 값 / registration_status = REGISTERED / updated_at ≠ created_at / runtime 연결)이 있으면
 *    전제 점검이 위반을 보고하고 되돌림이 **실행을 거부**(DROP 0 — 표·열·행 보존).
 * 스크립트: db/recovery/master/business-profile-v15-{precheck,inspect,revert}.sql — 오류에서 멈추는 실행기(mysql 클라이언트 기본)로 문장 순서대로 실행한다.
 */
class BusinessSchemaMigrationSpec : FunSpec({

    val container = MasterDbAccounts.newContainer().also { it.start() }
    val workDir = Files.createTempDirectory("biz-v15-partial").toFile()
    afterSpec {
        container.stop()
        workDir.deleteRecursively()
    }

    fun resource(path: String): File = File(requireNotNull(BusinessSchemaMigrationSpec::class.java.getResource(path)) { "missing $path" }.toURI())

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

    fun inspect(): List<String?> = root { c -> runScript(c, "business-profile-v15-inspect.sql").values.toList() }
    fun precheck(): Map<String, String?> = root { c -> runScript(c, "business-profile-v15-precheck.sql") }
    fun revert() = root { c -> runScript(c, "business-profile-v15-revert.sql") }

    /** V14 열 그대로의 business 행 전체(되돌림 뒤 기존 열·값 불변 확인). */
    fun legacyBusinessRows(): List<String> = root { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT CONCAT_WS('|', business_id, registration_number, display_name, lifecycle, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), created_by, version) " +
                    "FROM paycore_master.business ORDER BY business_id",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    val v15 = statements(resource("/db/migration/master/V15__business_profile.sql").readText())

    /**
     * V15의 앞 [k] 문장 + 실패하는 DDL(Flyway가 실패를 기록한다). 실패 문장을 DDL로 두는 이유: 실제 V15의 다음 문장도 DDL이고 MySQL은 DDL 실행 **전에** 암묵 commit하므로
     * 앞선 DML(문장 2 백필·6 등록정보)은 다음 DDL이 실패해도 커밋돼 남는다(Flyway TX rollback 대상이 아니다) — 그 상태를 그대로 만든다.
     */
    fun failAfter(k: Int) {
        val dir = workDir.resolve("k$k").also { it.mkdirs() }
        resource("/db/migration/master").listFiles()!!.filter { it.name.endsWith(".sql") && !it.name.startsWith("V15__") }.forEach { it.copyTo(dir.resolve(it.name), overwrite = true) }
        dir.resolve("V15__business_profile.sql").writeText((v15.take(k) + "DROP TABLE v15_simulated_failure").joinToString(";\n", postfix = ";\n"))
        shouldThrow<FlywayException> { flyway("filesystem:${dir.absolutePath}", MasterDbAccounts.RUNTIME_PRIVILEGES, target = "15").migrate() }
        root { c -> c.createStatement().use { st -> st.executeQuery("SELECT success FROM paycore_master.flyway_schema_history WHERE version = '15'").use { rs -> rs.next(); rs.getString(1) } } } shouldBe "0"
    }

    val v14 = listOf("0", null, "0", null, "0", "0", null, "0", "0", "0", "0")

    test("BIZ-1-2 V15 has the 10 statements of §5.1·§5.2 in order (INSTANT → backfill → COPY → INPLACE → tables)") {
        v15.size shouldBe 10
        v15.map { it.lineSequence().first().trim() }.take(6) shouldBe listOf(
            "ALTER TABLE business", "UPDATE business SET registration_status = 'DRAFT', verification_status = 'NOT_CHECKED', updated_at = created_at, updated_by = created_by",
            "ALTER TABLE business", "ALTER TABLE business", "CREATE TABLE business_registration (", "INSERT INTO business_registration (business_id, country_code) SELECT business_id, 'KR' FROM business",
        )
        listOf(v15[0].contains("ALGORITHM = INSTANT"), v15[2].contains("ALGORITHM = COPY"), v15[3].contains("ALGORITHM = INPLACE, LOCK = NONE")) shouldBe listOf(true, true, true)
    }

    test("BIZ-1-2 / L-8 a V14 database with existing businesses: partial V15 failures are inspected, refused when values outside the migration exist, reverted, repaired; then V15/V16 apply (TC-18)") {
        real(target = "14").migrate()
        // V14 상태 Demo 형태 행(시험 값): 사업자 3개(생성 시각·행위자·version 다름). 가맹점은 넣지 않는다(V15는 business만 바꾼다).
        root { c ->
            listOf(0L, 61L, 3_600L).forEachIndexed { n, offset ->
                c.prepareStatement(
                    "INSERT INTO paycore_master.business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, MasterSchemaRows.id("biz")); ps.setString(2, MasterSchemaRows.registrationNumber()); ps.setString(3, "Demo business $n")
                    ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-10-01T00:00:00.123Z").plusSeconds(offset))); ps.setString(5, "demo-fixture-$n"); ps.setLong(6, n.toLong())
                    ps.executeUpdate()
                }
            }
        }
        val legacy = legacyBusinessRows()
        inspect() shouldBe v14

        val expected = mapOf(
            1 to listOf("5", "3", "0", "YES", "0", "0", null, "0", "0", "0", "0"),
            2 to listOf("5", "0", "0", "YES", "0", "0", null, "0", "0", "0", "0"),
            3 to listOf("5", "0", "5", "NO", "0", "0", null, "0", "0", "0", "0"),
            4 to listOf("5", "0", "5", "NO", "3", "0", null, "0", "0", "0", "0"),
            5 to listOf("5", "0", "5", "NO", "3", "1", "0", "0", "0", "0", "0"),
            6 to listOf("5", "0", "5", "NO", "3", "1", "3", "0", "0", "0", "0"),
            7 to listOf("5", "0", "5", "NO", "3", "1", "3", "1", "0", "0", "0"),
            8 to listOf("5", "0", "5", "NO", "3", "1", "3", "1", "1", "0", "0"),
            9 to listOf("5", "0", "5", "NO", "3", "1", "3", "1", "1", "1", "0"),
        )
        // r-1 거부 사례: 지점별로 migration 밖 값을 넣었다가(거부 확인) 되돌려 놓는다.
        val tamper: Map<Int, Pair<String, String>> = mapOf(
            2 to ("UPDATE paycore_master.business SET registration_status = 'REGISTERED' ORDER BY business_id LIMIT 1" to
                "UPDATE paycore_master.business SET registration_status = 'DRAFT'"),
            3 to ("UPDATE paycore_master.business SET updated_at = DATE_ADD(created_at, INTERVAL 1 SECOND) ORDER BY business_id LIMIT 1" to
                "UPDATE paycore_master.business SET updated_at = created_at"),
            6 to ("UPDATE paycore_master.business_registration SET phone_number = '0212345678' ORDER BY registration_id LIMIT 1" to
                "UPDATE paycore_master.business_registration SET phone_number = NULL"),
            9 to ("INSERT INTO paycore_master.business_representative (business_id, name, representative_type, display_order, effective_from, created_by) " +
                "SELECT business_id, '대표', 'SOLE', 1, created_at, 'outside-migration' FROM paycore_master.business ORDER BY business_id LIMIT 1" to
                "DELETE FROM paycore_master.business_representative"),
        )
        val violationColumn = mapOf(2 to "business_value_mismatch", 3 to "business_value_mismatch", 6 to "registration_mismatch", 9 to "child_rows")

        expected.forEach { (k, flags) ->
            withClue("partial V15 up to statement $k") {
                failAfter(k)
                inspect() shouldBe flags
                precheck().let { listOf(it["flyway_state_mismatch"], it["ok"]) } shouldBe listOf("0", "1")

                tamper[k]?.let { (apply, undo) ->
                    root { c -> c.createStatement().use { it.executeUpdate(apply) } }
                    val report = precheck()
                    listOf(report["ok"], report.getValue(violationColumn.getValue(k)) != "0") shouldBe listOf("0", true)
                    shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146 // v15_revert_refused_precheck_failed — 첫 DROP 전에 멈춤
                    inspect() shouldBe flags // DROP 0: 표·열·색인·CHECK 그대로
                    root { c -> c.createStatement().use { it.executeUpdate(undo) } }
                }
                if (k == 1) {
                    // r-1 점검 1: runtime 계정 연결이 남아 있으면(웹·Demo seed·이전 버전) 거부.
                    MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use {
                        precheck()["runtime_connections"] shouldBe "1"
                        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
                        inspect() shouldBe flags
                    }
                }

                revert()
                inspect() shouldBe v14
                legacyBusinessRows() shouldBe legacy
                real().repair()
            }
        }

        // 마지막: 실패 기록 없이 V15(전체)·V16 적용.
        real().migrate()
        real().info().applied().filter { it.version?.version in setOf("15", "16") }.map { it.version.version to it.state.isApplied } shouldBe listOf("15" to true, "16" to true)
        inspect() shouldBe listOf("5", "0", "5", "NO", "3", "1", "3", "1", "1", "1", "1")
        legacyBusinessRows() shouldBe legacy
        root { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COUNT(*) FROM paycore_master.business b WHERE b.registration_status = 'DRAFT' AND b.business_type IS NULL AND b.verification_status = 'NOT_CHECKED' " +
                        "AND b.updated_at = b.created_at AND b.updated_by = b.created_by " +
                        "AND (SELECT COUNT(*) FROM paycore_master.business_registration r WHERE r.business_id = b.business_id AND r.country_code = 'KR' " +
                        "AND r.corporate_registration_number IS NULL AND r.phone_number IS NULL) = 1",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        } shouldBe 3
        // 리뷰 m-2(강화): 완료된 V15·V16 DB는 되돌림 대상이 아니다 — Flyway 기록이 "V15 실패(success=0)·V16 없음"이 아니면 전제 점검이 거부하고 되돌림도 멈춘다(DROP 0).
        precheck().let { listOf(it["flyway_state_mismatch"], it["ok"]) } shouldBe listOf("1", "0")
        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
        inspect() shouldBe listOf("5", "0", "5", "NO", "3", "1", "3", "1", "1", "1", "1")
    }
})
