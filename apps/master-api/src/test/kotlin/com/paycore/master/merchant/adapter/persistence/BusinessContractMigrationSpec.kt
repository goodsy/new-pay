package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractId
import com.paycore.master.merchant.domain.BusinessContractStatus
import com.paycore.master.merchant.domain.BusinessContractVersion
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ServiceKind
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
 * MERCHANT-FLOW-001 FLOW-2a(§4B.10 — 별도 컨테이너, 운영 초기화 스크립트 계정·운영 migration 위치 그대로 — ContractIntakeMigrationSpec 형식):
 * ① **전제 위반 데이터가 있는 V20 DB**(접수 건 TERMINATED / 전이 이력 → TERMINATED / 이관이 아닌 계약 완료 건 / 저장된 `CONTRACT_INTAKE_COMPLETED_EXISTS` 거절)마다 — 전제 점검 SQL이 위반을
 *    보고하고(migrate_ok = 0), V21이 **첫 단계 guard에서 1146으로 DDL 전에 멈춘다**(점검 SQL = V20 상태). 데이터를 되돌리고 `repair` 뒤 다음 사례.
 * ② 부분 실패 복구: V21을 문장 k(guard 뒤 모든 지점)까지만 적용하고 실패시킨 DB마다 — 점검 SQL이 적용 범위를 정확히 판정, 되돌림 전제 통과 → 되돌림 → 점검 SQL·`SHOW CREATE TABLE`이
 *    V20과 같음(business·접수 건·전이 이력 행 불변) → `repair`. 되돌림 거부 사례: migration 밖 행(이관 모양이 아닌 사업자 계약 / 변경 접수)·runtime 연결·완료된 V21·V22 DB.
 * ③ 마지막에 V21·V22 전체 적용 → **Reconciliation**(§4B.12): 이관 계약 수 = 이관 완료 접수 건 수 = V19 시점 사업자 수, 계약마다 버전 1·범위 1행(카드 PG 결제), 이관 버전 시작 = 계약
 *    생성 시각(이관 시각 — 한 문장 같은 값), 이관 계약은 판정 시각에 `ACTIVE`(가맹점 등록이 끊기지 않음), 접수 건 종류 = 신규, business·merchant·Outbox·권리 Snapshot·변경 이력 불변.
 *    V21 뒤 schema에서 TERMINATED 행을 쓰면 3819(§4B.15 — V19 시험의 옛 UPDATE).
 * 스크립트: db/recovery/master/business-contract-v21-{precheck,inspect,revert}.sql — 오류에서 멈추는 실행기(mysql 클라이언트 기본)로 문장 순서대로 실행한다.
 */
class BusinessContractMigrationSpec : FunSpec({

    val container = MasterDbAccounts.newContainer().also { it.start() }
    val workDir = Files.createTempDirectory("bc-v21-partial").toFile()
    afterSpec {
        container.stop()
        workDir.deleteRecursively()
    }

    fun resource(path: String): File = File(requireNotNull(BusinessContractMigrationSpec::class.java.getResource(path)) { "missing $path" }.toURI())

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
        c.createStatement().use { it.execute("USE paycore_master") }
        block(c)
    }

    fun exec(sql: String) = root { c -> c.createStatement().use { it.executeUpdate(sql) } }

    fun rows(sql: String): List<String> = root { c -> c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } } }

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

    fun inspect(): List<String?> = root { c -> runScript(c, "business-contract-v21-inspect.sql").values.toList() }
    fun precheck(): Map<String, String?> = root { c -> runScript(c, "business-contract-v21-precheck.sql") }
    fun revert() = root { c -> runScript(c, "business-contract-v21-revert.sql") }

    /** 이관 대상이 아닌 기존 표와 접수 건·전이 이력의 V19 열 행 지문(V21 전후 불변이어야 한다 — 접수 건은 kind 열만 더해진다). */
    fun untouched(): List<String> = rows(
        "SELECT CONCAT_WS('|', business_id, registration_status, lifecycle, version, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f'), updated_by) " +
            "FROM paycore_master.business ORDER BY business_id",
    ) + rows("SELECT CONCAT_WS('|', merchant_id, business_id, entitlement_revision, version) FROM paycore_master.merchant ORDER BY merchant_id") +
        rows(
            "SELECT CONCAT_WS('|', business_id, intake_seq, status, migrated, version, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), created_by, updated_by) " +
                "FROM paycore_master.contract_intake ORDER BY business_id, intake_seq",
        ) +
        rows(
            "SELECT CONCAT_WS('|', business_id, intake_seq, transition_seq, IFNULL(from_status, '-'), to_status, migrated, actor_ref) " +
                "FROM paycore_master.contract_intake_transition ORDER BY business_id, intake_seq, transition_seq",
        ) +
        rows("SELECT CONCAT('outbox=', COUNT(*)) FROM paycore_master.outbox_event") +
        rows("SELECT CONCAT('snapshot_issue=', COUNT(*)) FROM paycore_master.entitlement_snapshot_issue") +
        rows("SELECT CONCAT('change_history=', COUNT(*)) FROM paycore_master.master_change_history")

    fun schemaOf(): List<String> = root { c ->
        listOf("contract_intake", "contract_intake_transition").map { t ->
            c.createStatement().use { st -> st.executeQuery("SHOW CREATE TABLE paycore_master.$t").use { rs -> rs.next(); rs.getString(2) } }
        }
    }

    val v21 = statements(resource("/db/migration/master/V21__business_contract.sql").readText())

    /** V21의 앞 [k] 문장 + 실패하는 DDL(DDL 실행 **전에** 암묵 commit하므로 앞선 DML은 커밋돼 남는다 — 그 상태를 그대로 만든다). */
    fun failAfter(k: Int) {
        val dir = workDir.resolve("k$k").also { it.mkdirs() }
        resource("/db/migration/master").listFiles()!!.filter { it.name.endsWith(".sql") && !it.name.startsWith("V21__") }.forEach { it.copyTo(dir.resolve(it.name), overwrite = true) }
        dir.resolve("V21__business_contract.sql").writeText((v21.take(k) + "DROP TABLE v21_simulated_failure").joinToString(";\n", postfix = ";\n"))
        shouldThrow<FlywayException> { flyway("filesystem:${dir.absolutePath}", MasterDbAccounts.RUNTIME_PRIVILEGES, target = "21").migrate() }
        rows("SELECT success FROM paycore_master.flyway_schema_history WHERE version = '21'") shouldBe listOf("0")
    }

    fun causeCode(e: Throwable): Int? = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.filterIsInstance<SQLException>().firstOrNull()?.errorCode

    test("FLOW-2a V21 statement order (§4B.10 0~5): guard first, CHECK swap per table, new UNIQUE before dropping the old one, kind, tables, FK, migration; ALGORITHM on every ALTER") {
        v21.size shouldBe 21
        v21.map { it.lineSequence().first().trim() } shouldBe listOf(
            "SET @v21_precheck_violations = (SELECT COUNT(*) FROM contract_intake WHERE status = 'TERMINATED')",
            "SET @v21_precheck_guard = IF(@v21_precheck_violations = 0, 'DO 0', 'SELECT 1 FROM v21_refused_precheck_failed')",
            "PREPARE v21_precheck_guard FROM @v21_precheck_guard", "EXECUTE v21_precheck_guard", "DEALLOCATE PREPARE v21_precheck_guard",
            "ALTER TABLE contract_intake", "ALTER TABLE contract_intake_transition", "ALTER TABLE contract_intake", "ALTER TABLE contract_intake", "ALTER TABLE contract_intake",
            "UPDATE contract_intake SET kind = 'NEW'", "ALTER TABLE contract_intake",
            "CREATE TABLE business_contract (", "CREATE TABLE business_contract_version (", "CREATE TABLE business_contract_version_service (",
            "CREATE TABLE business_contract_version_close (", "CREATE TABLE business_contract_event (", "ALTER TABLE contract_intake",
            "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by)",
            "INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, reason,",
            "INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind)",
        )
        v21.filter { it.startsWith("ALTER TABLE") }.filterNot { it.contains("ALGORITHM = COPY") }.shouldBeEmpty()
        v21[7].contains("ADD CONSTRAINT uk_cin_open UNIQUE (open_key)") shouldBe true
        v21[8].contains("DROP INDEX uk_cin_active") shouldBe true
        val sql = resource("/db/migration/master/V21__business_contract.sql").readText()
        listOf("SIGNAL", "RAND(", "NOW(", "CURRENT_TIMESTAMP", "DELETE FROM").filter { sql.contains(it, ignoreCase = true) }.shouldBeEmpty()
        // V18은 빈 번호(V17 주석), 권한은 V22.
        resource("/db/migration/master").listFiles()!!.map { it.name }.filter { it.startsWith("V18__") || it.startsWith("V22__") }.shouldBeEmpty()
        resource("/db/runtime-privilege/master").listFiles()!!.map { it.name }.filter { it.startsWith("V22__") } shouldBe listOf("V22__business_contract_privileges.sql")
    }

    test("FLOW-2a §4B.10 a V20 database: precondition guard 1146, partial V21 failures inspected / refused / reverted, then V21·V22 migrate every migrated intake") {
        real(target = "17").migrate()
        // V17 상태 Demo 형태 행(시험 값): 사업자 4개(DRAFT·REGISTERED, 가맹점 있음·없음) — V19가 사업자마다 이관 접수 건을 만든다.
        val businesses = root { c ->
            listOf("DRAFT" to null, "DRAFT" to "INDIVIDUAL", "REGISTERED" to "CORPORATION", "REGISTERED" to "INDIVIDUAL").mapIndexed { n, (status, type) ->
                val id = MasterSchemaRows.id("biz")
                val at = Timestamp.from(Instant.parse("2026-10-01T00:00:00.123Z").plusSeconds(n * 3_601L))
                c.prepareStatement(
                    "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, " +
                        "registration_status, verification_status, updated_at, updated_by) VALUES (?, ?, ?, 'ACTIVE', ?, 'demo-fixture', ?, ?, ?, 'NOT_CHECKED', ?, 'demo-fixture')",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, MasterSchemaRows.registrationNumber()); ps.setString(3, "Demo business $n"); ps.setTimestamp(4, at)
                    ps.setLong(5, n.toLong()); ps.setString(6, type); ps.setString(7, status); ps.setTimestamp(8, Timestamp.from(at.toInstant().plusSeconds(5)))
                    ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')").use { it.setString(1, id); it.executeUpdate() }
                id
            }
        }
        root { c ->
            listOf(businesses[1], businesses[2], businesses[2]).forEachIndexed { n, businessId ->
                c.prepareStatement(
                    "INSERT INTO merchant (merchant_id, business_id, display_name, lifecycle, entitlement_revision, created_at, created_by, version) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, ?, 'demo-fixture', 0)",
                ).use { ps ->
                    ps.setString(1, MasterSchemaRows.id("mer")); ps.setString(2, businessId); ps.setString(3, "Demo merchant $n"); ps.setLong(4, 3L + n)
                    ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-10-02T00:00:00Z"))); ps.executeUpdate()
                }
            }
        }
        real(target = "20").migrate()
        val before = untouched()
        val v20Schema = schemaOf()
        val n = "4"
        val v20 = listOf("0", "0", "0", "1", "0", "0", "0", "0", "0", n, null, null, null)
        inspect() shouldBe v20
        precheck().let { listOf(it["intake_terminated"], it["transition_terminated"], it["nonmigrated_completed"], it["stored_completed_exists"], it["migrate_ok"]) } shouldBe
            listOf("0", "0", "0", "0", "1")

        // ① 전제 위반 4종 — guard가 DDL 전에 1146으로 멈춘다(자동 변환 없음). 위반 데이터는 시험 정리용 root로 되돌린다.
        val extra = MasterSchemaRows.id("biz")
        val violations: List<Triple<String, List<String>, List<String>>> = listOf(
            Triple(
                "intake_terminated",
                listOf("UPDATE contract_intake SET status = 'TERMINATED', version = 1 WHERE business_id = '${businesses[3]}'"),
                listOf("UPDATE contract_intake SET status = 'COMPLETED', version = 0 WHERE business_id = '${businesses[3]}'"),
            ),
            Triple(
                "transition_terminated",
                listOf(
                    "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, " +
                        "occurred_at) VALUES ('${businesses[0]}', 1, 2, 'COMPLETED', 'TERMINATED', 0, 'demo', NULL, 'admin:x', '2026-11-20 00:00:00')",
                ),
                listOf("DELETE FROM contract_intake_transition WHERE business_id = '${businesses[0]}' AND transition_seq = 2"),
            ),
            Triple(
                "nonmigrated_completed",
                listOf(
                    "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, registration_status, " +
                        "verification_status, updated_at, updated_by) VALUES ('$extra', '9999999999', 'Later business', 'ACTIVE', '2026-11-20 00:00:00', 'x', 0, 'DRAFT', " +
                        "'NOT_CHECKED', '2026-11-20 00:00:00', 'x')",
                    "INSERT INTO contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, " +
                        "desires_card_keyin, created_at, created_by, updated_at, updated_by, version) VALUES ('$extra', 1, 'COMPLETED', 0, '2026-11-20', 'PHONE', " +
                        "'admin:adm_01JCFL0000000000000000OP01', 0, 0, '2026-11-20 00:00:00', 'admin:x', '2026-11-20 00:00:00', 'admin:x', 2)",
                ),
                listOf("DELETE FROM contract_intake WHERE business_id = '$extra'", "DELETE FROM business WHERE business_id = '$extra'"),
            ),
            Triple(
                "stored_completed_exists",
                listOf(
                    "INSERT INTO admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind, result_code, result_reference, " +
                        "created_at, updated_at, version) VALUES ('OpenContractIntake', 'k-1', 'r-1', 'h1:x', 'admin:x', 'REJECTED', 'CONTRACT_INTAKE_COMPLETED_EXISTS', NULL, " +
                        "'2026-11-20 00:00:00', '2026-11-20 00:00:00', 1)",
                ),
                listOf("DELETE FROM admin_request WHERE idempotency_key = 'k-1'"),
            ),
        )
        violations.forEach { (name, apply, undo) ->
            withClue("precondition $name") {
                apply.forEach(::exec)
                precheck().let { listOf(it[name], it["migrate_ok"]) } shouldBe listOf("1", "0")
                causeCode(shouldThrow<FlywayException> { real().migrate() }) shouldBe 1146 // v21_refused_precheck_failed — DDL 전에 멈춤
                rows("SELECT success FROM paycore_master.flyway_schema_history WHERE version = '21'") shouldBe listOf("0")
                inspect().take(9) shouldBe v20.take(9) // 단계 열 = V20(이관 대상 수 열은 심은 위반 행에 따라 다르다)
                schemaOf() shouldBe v20Schema
                undo.forEach(::exec)
                inspect() shouldBe v20
                real().repair()
            }
        }
        untouched() shouldBe before

        // ② 부분 실패: guard 뒤 모든 지점. 단계별 기대 점검 결과를 누적해 만든다(열 순서 = inspect SQL).
        fun expectedAfter(k: Int): List<String?> {
            val flags = v20.toMutableList()
            if (k >= 6) flags[0] = "1"
            if (k >= 7) flags[1] = "1"
            if (k >= 8) flags[2] = "1"
            if (k >= 9) flags[3] = "0"
            if (k >= 10) flags[4] = "1"
            if (k >= 12) { flags[5] = "1"; flags[6] = "3" }
            if (k >= 13) { flags[7] = "1"; flags[10] = "0" }
            if (k >= 14) { flags[7] = "2"; flags[11] = "0" }
            if (k >= 15) { flags[7] = "3"; flags[12] = "0" }
            if (k >= 16) flags[7] = "4"
            if (k >= 17) flags[7] = "5"
            if (k >= 18) flags[8] = "1"
            if (k >= 19) flags[10] = n
            if (k >= 20) flags[11] = n
            if (k >= 21) flags[12] = n
            return flags
        }
        // 거부 사례: 지점별 migration 밖 행(시험 정리 전용 root로 넣고 지운다).
        val tamper: Map<Int, Pair<String, String>> = mapOf(
            11 to ("UPDATE contract_intake SET kind = 'CHANGE', target_contract_seq = 1 WHERE business_id = '${businesses[1]}'" to
                "UPDATE contract_intake SET kind = 'NEW', target_contract_seq = NULL WHERE business_id = '${businesses[1]}'"),
            13 to ("INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) VALUES ('${businesses[2]}', 2, 1, 0, " +
                "'2026-11-20 00:00:00', 'admin:x')" to "DELETE FROM business_contract"),
        )
        (6..20).forEach { k ->
            withClue("partial V21 up to statement $k") {
                failAfter(k)
                inspect() shouldBe expectedAfter(k)
                precheck().let { listOf(it["flyway_state_mismatch"], it["foreign_rows"], it["revert_ok"]) } shouldBe listOf("0", "0", "1")
                tamper[k]?.let { (apply, undo) ->
                    exec(apply)
                    precheck().let { listOf(it["revert_ok"], it["foreign_rows"]) } shouldBe listOf("0", "1")
                    shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146 // v21_revert_refused_precheck_failed — 첫 변경 전에 멈춤
                    exec(undo)
                    inspect() shouldBe expectedAfter(k)
                }
                if (k == 8) {
                    MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use {
                        precheck()["runtime_connections"] shouldBe "1"
                        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
                        inspect() shouldBe expectedAfter(k)
                    }
                }
                revert()
                inspect() shouldBe v20
                schemaOf() shouldBe v20Schema
                untouched() shouldBe before
                real().repair()
            }
        }

        // ③ 실패 기록 없이 V21(전체)·V22 적용 → Reconciliation.
        val migrationStart = Instant.now().minusSeconds(5)
        real().migrate()
        val migrationEnd = Instant.now().plusSeconds(5)
        real().info().applied().filter { it.version?.version in setOf("21", "22") }.map { it.version.version to it.state.isApplied } shouldBe listOf("21" to true, "22" to true)
        inspect() shouldBe expectedAfter(21)
        untouched() shouldBe before

        rows("SELECT COUNT(*) FROM paycore_master.business") shouldBe listOf(n)
        rows("SELECT COUNT(*) FROM paycore_master.contract_intake WHERE migrated = 1 AND status = 'COMPLETED'") shouldBe listOf(n)
        rows("SELECT COUNT(*) FROM paycore_master.business_contract WHERE migrated = 1") shouldBe listOf(n)
        rows(
            "SELECT COUNT(*) FROM paycore_master.business b WHERE (SELECT COUNT(*) FROM paycore_master.business_contract c WHERE c.business_id = b.business_id) <> 1",
        ) shouldBe listOf("0")
        rows(
            "SELECT CONCAT_WS('|', c.business_id, c.contract_seq, c.origin_intake_seq, c.migrated, c.created_by, v.version_no, v.origin_intake_seq, v.migrated, " +
                "v.starts_at = c.created_at, v.created_at = c.created_at, IFNULL(v.start_date, '-'), IFNULL(v.end_date, '-'), IFNULL(v.document_ref, '-'), IFNULL(v.reason, '-'), " +
                "v.created_by, (SELECT GROUP_CONCAT(s.service_kind) FROM paycore_master.business_contract_version_service s WHERE s.business_id = v.business_id " +
                "AND s.contract_seq = v.contract_seq AND s.version_no = v.version_no), " +
                "(SELECT COUNT(*) FROM paycore_master.business_contract_version_close x WHERE x.business_id = c.business_id), " +
                "(SELECT COUNT(*) FROM paycore_master.business_contract_event e WHERE e.business_id = c.business_id)) " +
                "FROM paycore_master.business_contract c JOIN paycore_master.business_contract_version v ON v.business_id = c.business_id AND v.contract_seq = c.contract_seq " +
                "ORDER BY c.business_id",
        ) shouldBe businesses.sorted().map { "$it|1|1|1|system:migration|1|1|1|1|1|-|-|-|-|system:migration|CARD_PG_PAYMENT|0|0" }
        // 이관 시각 = 한 문장의 UTC_TIMESTAMP(3) — 모든 계약이 같은 값, migration 실행 구간 안.
        val migratedAt = rows("SELECT DISTINCT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM paycore_master.business_contract").map { Instant.parse(it) }
        migratedAt.size shouldBe 1
        (migratedAt.single() in migrationStart..migrationEnd) shouldBe true
        rows("SELECT CONCAT_WS('|', kind, IFNULL(target_contract_seq, '-'), IFNULL(open_key, '-')) FROM paycore_master.contract_intake") shouldBe List(4) { "NEW|-|-" }

        // 이관 계약은 판정 시각에 유효 + 적용 중 버전 범위에 카드 PG 결제(BR-BIZ-334·343 — FLOW-3 관문이 기존 사업자를 막지 않는다).
        businesses.forEach { id ->
            val at = migratedAt.single()
            val actor = ActorRef("system:migration")
            val contract = BusinessContract.reconstitute(
                BusinessContractId(BusinessId(id), 1), 1, true, at, actor,
                listOf(BusinessContractVersion(1, 1, true, at, null, null, setOf(ServiceKind.CARD_PG_PAYMENT), null, null, at, actor)), emptyList(), emptyList(),
            )
            val now = Instant.now()
            listOf(contract.statusAt(now), contract.applyingVersionAt(now)?.services) shouldBe listOf(BusinessContractStatus.ACTIVE, setOf(ServiceKind.CARD_PG_PAYMENT))
        }

        // V21 뒤 schema: 접수 건·전이 이력에 TERMINATED를 쓰면 3819(§4B.15 ContractIntakeMigrationSpec.kt:158 — 옛 복구 시나리오의 UPDATE).
        shouldThrow<SQLException> { exec("UPDATE contract_intake SET status = 'TERMINATED', version = 1 WHERE business_id = '${businesses[3]}'") }.errorCode shouldBe 3819

        // 완료된 V21·V22 DB는 되돌림 대상이 아니다 — 전제 점검 거부, 되돌림 변경 0(이관 행은 업무 기록).
        precheck().let { listOf(it["flyway_state_mismatch"], it["revert_ok"]) } shouldBe listOf("1", "0")
        shouldThrow<SQLException> { revert() }.errorCode shouldBe 1146
        inspect() shouldBe expectedAfter(21)
    }
})
