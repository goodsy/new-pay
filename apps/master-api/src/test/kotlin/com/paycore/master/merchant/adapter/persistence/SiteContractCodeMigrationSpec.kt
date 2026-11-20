package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.domain.ContractCode
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.SiteCode
import com.paycore.master.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.io.File
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/**
 * SITE-CONTRACT-CODE-001 SCODE-1-4·1-5·1-6(별도 컨테이너 — 운영 초기화 스크립트 계정·운영 migration 위치 그대로, MerchantCodeMigrationSpec·BusinessSchemaMigrationSpec 선례).
 * 시험들은 **순서대로 같은 DB를 쓴다**(V17 상태 Fixture → 고의 실패·복구 → 백필 단언 → 재생성 결정성 → runtime 계정 제약·권한).
 *
 * - 1-4 migration: V17 상태(V18 빈 번호) DB에 Demo 형태 Site·계약(가맹점별 created_at 순서·동률·101개·코드 변경 이력 가맹점)을 넣고 V19·V20 적용 →
 *   행 수 = Site·계약 수, 가맹점별 순번 1..k 연속, 101번째 `-101`(LPAD 절단 회귀), 동률은 내부 키 순, 코드 = **Kotlin으로 독립 계산**한 값(현재 가맹점 코드),
 *   `assigned_at` = migration 시각(한 문장 같은 값, 원 `created_at`과 무관), `source = BACKFILL`·`assigned_by = system:migration`, 결정성(표를 지우고 다시 적용해도 같은 코드),
 *   Flyway 세션 `sql_mode` 기록(LG-3). recovery: precheck가 배정 행 없는 가맹점을 보고 → 고의 실패(V19 백필 INSERT — DDL 자동 커밋으로 빈 표가 남는다) → inspect가 남은 빈 표 보고 →
 *   revert(새 두 표 DROP, 원 표 무변경) → `flyway repair` → 원인 수정 뒤 재적용 성공. 완료된 DB에서 revert는 실행을 거부한다.
 * - 1-5 DB 제약(**runtime 계정 연결**): 정상 코드 INSERT 통과(Kotlin 포맷 = SQL 식, 1·9·10·99·100·101·999·1000·9999), CHECK 위반(코드 ≠ f(가맹점 코드, 순번)·형식) 3819 —
 *   연결 문자 집합·collation을 바꿔도 같은 결과(식의 문자 집합·collation 명시 근거). FK(다른 가맹점의 가맹점 코드·다른 가맹점 Site) 1452, 순번·코드 UNIQUE 1062.
 * - 1-6 권한: runtime UPDATE·DELETE·SELECT … FOR UPDATE → 1142(두 표).
 */
class SiteContractCodeMigrationSpec : FunSpec({

    val container = MasterDbAccounts.newContainer().also { it.start() }
    afterSpec { container.stop() }

    fun resource(path: String): File = File(requireNotNull(SiteContractCodeMigrationSpec::class.java.getResource(path)) { "missing $path" }.toURI())

    fun statements(sql: String): List<String> =
        sql.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").split(Regex(";[ \\t]*(\\r?\\n|$)")).map { it.trim() }.filter { it.isNotEmpty() }

    fun flyway(target: String? = null): Flyway = Flyway.configure()
        .dataSource(
            MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE), MasterDbAccounts.MASTER_MIGRATION_USER,
            MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER),
        )
        .locations(MasterDbAccounts.MAIN_MIGRATIONS, MasterDbAccounts.RUNTIME_PRIVILEGES)
        .initSql("SET time_zone = '+00:00'")
        .also { c -> target?.let { c.target(it) } }
        .load()

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        block(c)
    }

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun Connection.rows(sql: String, vararg args: Any?): List<List<String?>> = prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
    }

    fun Connection.long(sql: String, vararg args: Any?): Long = rows(sql, *args).single().single()!!.toLong()

    /** 스크립트를 문장 순서대로 실행(첫 오류에서 멈춤 — 예외). 마지막 결과 집합의 첫 행(열 이름 → 값). */
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

    fun precheck(): Map<String, String?> = root { runScript(it, "site-contract-code-v19-precheck.sql") }
    fun inspect(): Map<String, String?> = root { runScript(it, "site-contract-code-v19-inspect.sql") }
    fun revert() = root { runScript(it, "site-contract-code-v19-revert.sql") }

    val base = Instant.parse("2026-10-01T00:00:00.000Z")

    /** V17 열 그대로의 Site 행(생성 시각 지정). */
    fun site(c: Connection, merchantId: String, at: Instant, id: String = MasterSchemaRows.id("site")): String = id.also {
        c.update(
            "INSERT INTO merchant_site (merchant_site_id, merchant_id, channel_type, channel_identifier, lifecycle, created_at, created_by) VALUES (?, ?, 'WEB', ?, 'ACTIVE', ?, 'demo-fixture')",
            it, merchantId, MasterSchemaRows.channel(), Timestamp.from(at),
        )
    }

    fun contract(c: Connection, businessId: String, merchantId: String, at: Instant, id: String = MasterSchemaRows.id("ctr")): String = id.also {
        c.update("INSERT INTO contract (contract_id, business_id, merchant_id, created_at, created_by, version) VALUES (?, ?, ?, ?, 'demo-fixture', 0)", it, businessId, merchantId, Timestamp.from(at))
    }

    fun currentCode(c: Connection, merchantId: String): String =
        c.rows("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq DESC LIMIT 1", merchantId).single().single()!!

    /** 시험 Fixture 상태(기대값 독립 계산 재료). */
    data class Row(val id: String, val merchantId: String, val at: Instant)
    val sites = mutableListOf<Row>()
    val contracts = mutableListOf<Row>()
    val merchants = mutableListOf<String>()
    lateinit var broken: String
    lateinit var brokenBusiness: String
    lateinit var changed: String
    var migrationWindow: Pair<Instant, Instant>? = null

    fun expected(rows: List<Row>, prefix: String, codes: Map<String, String>): Map<String, String> =
        rows.groupBy { it.merchantId }.flatMap { (merchantId, own) ->
            own.sortedWith(compareBy<Row>({ it.at }, { it.id })).mapIndexed { i, r ->
                val seq = i + 1
                r.id to (if (prefix == "site") SiteCode.issue(MerchantCode(codes.getValue(merchantId)), seq).value else ContractCode.issue(MerchantCode(codes.getValue(merchantId)), seq).value)
            }
        }.toMap()

    test("SCODE-1-4 precheck on the V17 database reports a merchant without a code assignment (and no other problem); inspect shows the V17 state") {
        flyway(target = "17").migrate()
        root { c ->
            c.long("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'paycore_master' AND table_name IN ('merchant_site_code', 'contract_code')") shouldBe 0
            val business = MasterSchemaRows.business(c)
            // 가맹점 A: Site 101개(101번째 = -101 절단 회귀) + 동률 created_at 2개, 계약 3개(동률 2개).
            val a = MasterSchemaRows.merchant(c, business).also(merchants::add)
            (1..99).forEach { n -> sites += Row(site(c, a, base.plusSeconds(n * 60L)), a, base.plusSeconds(n * 60L)) }
            val tie = base.plusSeconds(100 * 60L)
            listOf(MasterSchemaRows.id("site"), MasterSchemaRows.id("site")).sorted().reversed().forEach { id -> sites += Row(site(c, a, tie, id), a, tie) }
            listOf(1L, 1L, 2L).forEach { h -> val at = base.plusSeconds(h * 3600); contracts += Row(contract(c, business, a, at), a, at) }
            // 가맹점 B: 코드 1회 변경(seq 2 = 현재 코드) — 백필은 현재 코드를 쓴다.
            changed = MasterSchemaRows.merchant(c, business).also(merchants::add)
            val old = currentCode(c, changed)
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "VALUES (?, 2, ?, ?, 'CHANGE', ?, 'TYPO_FIX', ?, 'demo-fixture')",
                changed, "chg" + MasterSchemaRows.id("x").substring(2).lowercase().take(8), old, MasterSchemaRows.id("apr"), Timestamp.from(base),
            )
            (1..2).forEach { n -> val at = base.plusSeconds(n * 7L); sites += Row(site(c, changed, at), changed, at); contracts += Row(contract(c, business, changed, at), changed, at) }
            // 가맹점 C: Site 없음·계약 1개.
            val cMerchant = MasterSchemaRows.merchant(c, business).also(merchants::add)
            contracts += Row(contract(c, business, cMerchant, base), cMerchant, base)
            // 결함 주입: 배정 행 없는 가맹점 + Site 1(백필 LEFT JOIN NULL → migration 실패해야 한다).
            brokenBusiness = business
            broken = MasterSchemaRows.merchant(c, business, withCode = false)
            site(c, broken, base)
        }
        precheck().let {
            withClue(it) {
                listOf(it["merchants_without_assignment"], it["merchants_over_9999_sites"], it["merchants_over_9999_contracts"], it["predicted_code_collisions"], it["ok"]) shouldBe
                    listOf("1", "0", "0", "0", "0")
            }
        }
        inspect().let { withClue(it) { listOf(it["site_code_table"], it["contract_code_table"], it["site_code_rows"], it["contract_code_rows"]) shouldBe listOf("0", "0", null, null) } }
    }

    test("SCODE-1-4 / MINOR-4 fail-closed: V19 fails on the missing assignment (empty table left by DDL auto-commit) → inspect → revert → repair → fix → V19/V20 apply") {
        shouldThrow<FlywayException> { flyway().migrate() }
        root { c -> c.rows("SELECT success FROM flyway_schema_history WHERE version = '19'").single().single() } shouldBe "0"
        inspect().let {
            withClue(it) {
                listOf(it["site_code_table"], it["contract_code_table"], it["site_code_rows"], it["contract_code_rows"]) shouldBe listOf("1", "0", "0", null)
                it["sites_missing_code"] shouldBe (sites.size + 1).toString()
            }
        }
        val originalSites = root { c -> c.long("SELECT COUNT(*) FROM merchant_site") }
        revert()
        inspect().let { withClue(it) { listOf(it["site_code_table"], it["contract_code_table"]) shouldBe listOf("0", "0") } }
        root { c -> c.long("SELECT COUNT(*) FROM merchant_site") } shouldBe originalSites
        flyway().repair()
        // 원인 수정(운영자 판단 — 여기서는 시험 Fixture): 배정 행 보충.
        root { c ->
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) VALUES (?, 1, ?, NULL, 'BACKFILL', NULL, NULL, ?, 'demo-fixture')",
                broken, "fix" + MasterSchemaRows.id("x").substring(2).lowercase().take(8), Timestamp.from(base),
            )
            sites += Row(c.rows("SELECT merchant_site_id FROM merchant_site WHERE merchant_id = ?", broken).single().single()!!, broken, base)
        }
        merchants += broken
        precheck()["ok"] shouldBe "1"
        val before = root { c -> Instant.parse(c.rows("SELECT DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ')").single().single()!!.replace(Regex("(\\.\\d{3})\\d{3}Z$"), "$1Z")) }
        flyway().migrate()
        val after = root { c -> Instant.parse(c.rows("SELECT DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ')").single().single()!!.replace(Regex("(\\.\\d{3})\\d{3}Z$"), "$1Z")) }
        migrationWindow = before to after
        root { c -> c.column("SELECT CONCAT(version, ':', success) FROM flyway_schema_history WHERE version IN ('19', '20') ORDER BY installed_rank") } shouldBe listOf("19:1", "20:1")
        inspect().let {
            withClue(it) {
                listOf(it["site_code_table"], it["contract_code_table"], it["site_code_rows"], it["contract_code_rows"], it["sites_missing_code"], it["contracts_missing_code"]) shouldBe
                    listOf("1", "1", sites.size.toString(), contracts.size.toString(), "0", "0")
            }
        }
        // 완료된 DB(V19 성공·V20 있음)에서 revert는 첫 DROP 전에 거부한다(표·행 보존).
        shouldThrow<java.sql.SQLException> { revert() }
        root { c -> c.long("SELECT COUNT(*) FROM merchant_site_code") } shouldBe sites.size.toLong()
    }

    test("SCODE-1-4 backfill: counts, consecutive per-merchant seq, -101, tie by internal key, current merchant code, migration-time assigned_at, deterministic values") {
        val codes = root { c -> merchants.associateWith { currentCode(c, it) } }
        val siteRows = root { c -> c.rows("SELECT merchant_site_id, merchant_id, code_seq, issued_merchant_code, site_code, source, DATE_FORMAT(assigned_at, '%Y-%m-%dT%H:%i:%s.%fZ'), assigned_by FROM merchant_site_code") }
        val contractRows = root { c -> c.rows("SELECT contract_id, merchant_id, code_seq, issued_merchant_code, contract_code, source, DATE_FORMAT(assigned_at, '%Y-%m-%dT%H:%i:%s.%fZ'), assigned_by FROM contract_code") }
        siteRows.size shouldBe sites.size
        contractRows.size shouldBe contracts.size
        siteRows.associate { it[0] to it[4] } shouldBe expected(sites, "site", codes)
        contractRows.associate { it[0] to it[4] } shouldBe expected(contracts, "ctr", codes)
        // 가맹점별 1..k 연속.
        (siteRows + contractRows).groupBy { it[4]!!.substringBefore('-') + "|" + it[1] }.forEach { (k, rows) ->
            withClue(k) { rows.map { it[2]!!.toInt() }.sorted() shouldBe (1..rows.size).toList() }
        }
        val a = merchants.first()
        siteRows.filter { it[1] == a }.map { it[4]!! }.filter { it.endsWith("-101") }.size shouldBe 1
        siteRows.single { it[1] == a && it[2] == "101" }[4] shouldBe "site-${codes.getValue(a)}-101"
        siteRows.single { it[1] == a && it[2] == "100" }[4] shouldBe "site-${codes.getValue(a)}-100"
        // 동률 created_at → 내부 키 오름차순(작은 키가 100, 큰 키가 101).
        val tied = sites.filter { it.merchantId == a }.groupBy { it.at }.values.single { it.size == 2 }.map { it.id }.sorted()
        tied.map { id -> siteRows.single { it[0] == id }[2] } shouldBe listOf("100", "101")
        // 코드 변경 이력 가맹점은 현재(seq 2) 코드.
        siteRows.filter { it[1] == changed }.map { it[3] }.toSet() shouldBe setOf(codes.getValue(changed))
        codes.getValue(changed).startsWith("chg") shouldBe true
        // 출처·행위자·migration 시각(한 문장 같은 값, 원 created_at 아님).
        (siteRows + contractRows).filter { it[5] != "BACKFILL" || it[7] != "system:migration" }.shouldBeEmpty()
        val stamps = (siteRows.map { it[6] } + contractRows.map { it[6] }).toSet()
        siteRows.map { it[6] }.toSet().size shouldBe 1
        contractRows.map { it[6] }.toSet().size shouldBe 1
        val (from, to) = checkNotNull(migrationWindow)
        stamps.map { Instant.parse(it!!.replace(Regex("(\\.\\d{3})\\d{3}Z$"), "$1Z")) }.forEach { t ->
            withClue("assigned_at $t within [$from, $to]") { (!t.isBefore(from) && !t.isAfter(to)) shouldBe true }
        }
        root { c -> c.long("SELECT COUNT(*) FROM merchant_site_code k JOIN merchant_site s ON s.merchant_site_id = k.merchant_site_id WHERE k.assigned_at = s.created_at") } shouldBe 0
        // 내부 키 문자열이 코드에 없다.
        (siteRows + contractRows).map { it[4]!! }.filter { it.contains("site_") || it.contains("ctr_") || it.contains("mer_") }.shouldBeEmpty()
    }

    test("SCODE-1-4 determinism: dropping the two tables and re-applying V19/V20 on the same data yields the same codes; LG-3 the Flyway session sql_mode is strict") {
        val first = root { c -> c.rows("SELECT merchant_site_id, site_code FROM merchant_site_code").associate { it[0] to it[1] } + c.rows("SELECT contract_id, contract_code FROM contract_code").associate { it[0] to it[1] } }
        root { c ->
            c.update("DROP TABLE contract_code")
            c.update("DROP TABLE merchant_site_code")
            c.update("DELETE FROM flyway_schema_history WHERE version IN ('19', '20')")
        }
        flyway().migrate()
        root { c -> c.rows("SELECT merchant_site_id, site_code FROM merchant_site_code").associate { it[0] to it[1] } + c.rows("SELECT contract_id, contract_code FROM contract_code").associate { it[0] to it[1] } } shouldBe first
        // LG-3: Flyway(migration 계정 + 운영 initSql) 세션의 sql_mode 기록. strict이면 백필 NULL은 즉시 실패한다(strict가 아니어도 FK·CHECK·precheck가 잡는다).
        val sqlMode = MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_MIGRATION_USER).use { c ->
            c.createStatement().use { it.execute("SET time_zone = '+00:00'") }
            c.rows("SELECT @@SESSION.sql_mode").single().single()!!
        }
        println("SCODE-1-4 LG-3 Flyway session sql_mode=$sqlMode")
        sqlMode shouldContain "STRICT_TRANS_TABLES"
    }

    test("SCODE-1-5 runtime account: valid codes insert (Kotlin format = SQL expression for 1·9·10·99·100·101·999·1000·9999) under any connection charset/collation; CHECK 3819, FK 1452, UNIQUE 1062") {
        // 새 가맹점(배정 행 있음) + Site 9개·계약 9개(코드 행 없음 — root가 원 표만 넣는다). 코드 행은 runtime 계정이 넣는다(운영과 같은 권한·세션).
        val seqs = listOf(1, 9, 10, 99, 100, 101, 999, 1000, 9999)
        lateinit var m: String
        lateinit var mCode: String
        lateinit var siteIds: List<String>
        lateinit var contractIds: List<String>
        root { c ->
            m = MasterSchemaRows.merchant(c, brokenBusiness)
            mCode = currentCode(c, m)
            siteIds = seqs.map { n -> site(c, m, base.plusSeconds(n.toLong())) }
            contractIds = seqs.map { n -> contract(c, brokenBusiness, m, base.plusSeconds(n.toLong())) }
        }
        val other = root { c -> MasterSchemaRows.merchant(c, brokenBusiness).let { o -> o to currentCode(c, o) } }
        val otherSite = root { c -> site(c, other.first, base) }
        val sessions = listOf(
            "SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci",
            "SET NAMES utf8mb4 COLLATE utf8mb4_general_ci",
            "SET NAMES latin1",
        )
        fun insertSite(c: Connection, siteId: String, merchantId: String, seq: Int, issued: String, code: String) = c.errorCodeOfUpdate(
            "INSERT INTO merchant_site_code (merchant_site_id, merchant_id, code_seq, issued_merchant_code, site_code, source, assigned_at, assigned_by) VALUES (?, ?, ?, ?, ?, 'REGISTRATION', UTC_TIMESTAMP(3), 'operator-1')",
            siteId, merchantId, seq, issued, code,
        )
        fun insertContract(c: Connection, contractId: String, merchantId: String, seq: Int, issued: String, code: String) = c.errorCodeOfUpdate(
            "INSERT INTO contract_code (contract_id, merchant_id, code_seq, issued_merchant_code, contract_code, source, assigned_at, assigned_by) VALUES (?, ?, ?, ?, ?, 'REGISTRATION', UTC_TIMESTAMP(3), 'operator-1')",
            contractId, merchantId, seq, issued, code,
        )
        runtime { c ->
            c.rows("SELECT CURRENT_USER()").single().single() shouldBe "${MasterDbAccounts.MASTER_RUNTIME_USER}@%"
            // 위반 먼저(세션마다 같은 결과) — 정상 행이 들어가기 전이라 UNIQUE와 섞이지 않는다.
            sessions.forEach { session ->
                c.createStatement().use { it.execute(session) }
                withClue(session) {
                    listOf(
                        // 순번 100인데 코드는 LPAD 절단 결과(-10) — 형식은 맞지만 f(가맹점 코드, 순번) 불일치.
                        insertSite(c, siteIds[4], m, 100, mCode, "site-$mCode-10"),
                        // 코드 ≠ f: 순번 9 / 코드 -08.
                        insertSite(c, siteIds[1], m, 9, mCode, "site-$mCode-08"),
                        // 코드 안 가맹점 코드 ≠ issued_merchant_code.
                        insertSite(c, siteIds[0], m, 1, mCode, "site-${other.second}-01"),
                        // 형식 위반(대문자·한 자리).
                        insertSite(c, siteIds[0], m, 1, mCode, "SITE-$mCode-01"),
                        insertContract(c, contractIds[5], m, 101, mCode, "ctr-$mCode-10"),
                        insertContract(c, contractIds[0], m, 1, mCode, "site-$mCode-01"),
                        // 순번 범위 밖.
                        insertSite(c, siteIds[0], m, 10_000, mCode, "site-$mCode-10000"),
                    ) shouldBe List(7) { 3819 }
                    // FK: 다른 가맹점의 가맹점 코드 / 다른 가맹점 Site.
                    insertSite(c, siteIds[0], m, 1, other.second, SiteCode.issue(MerchantCode(other.second), 1).value) shouldBe 1452
                    insertSite(c, otherSite, m, 1, mCode, SiteCode.issue(MerchantCode(mCode), 1).value) shouldBe 1452
                    insertContract(c, contractIds[0], m, 1, other.second, ContractCode.issue(MerchantCode(other.second), 1).value) shouldBe 1452
                }
            }
            // 정상: 세션을 돌려 가며 Kotlin이 만든 코드 그대로 넣는다(SQL 식과 같은 값이어야 CHECK 통과).
            seqs.forEachIndexed { i, n ->
                c.createStatement().use { it.execute(sessions[i % sessions.size]) }
                withClue("seq $n / ${sessions[i % sessions.size]}") {
                    insertSite(c, siteIds[i], m, n, mCode, SiteCode.issue(MerchantCode(mCode), n).value) shouldBe null
                    insertContract(c, contractIds[i], m, n, mCode, ContractCode.issue(MerchantCode(mCode), n).value) shouldBe null
                }
            }
            c.createStatement().use { it.execute("SET NAMES utf8mb4") }
            // UNIQUE: 같은 가맹점·같은 순번(다른 Site), 같은 코드.
            val spare = root { r -> site(r, m, base) }
            insertSite(c, spare, m, 1, mCode, SiteCode.issue(MerchantCode(mCode), 1).value) shouldBe 1062
            c.rows("SELECT site_code FROM merchant_site_code WHERE merchant_id = ? ORDER BY code_seq", m).map { it.single() } shouldBe seqs.map { SiteCode.issue(MerchantCode(mCode), it).value }
            c.rows("SELECT contract_code FROM contract_code WHERE merchant_id = ? ORDER BY code_seq", m).map { it.single() } shouldBe seqs.map { ContractCode.issue(MerchantCode(mCode), it).value }
        }
    }

    test("SCODE-1-6 runtime UPDATE / DELETE / SELECT … FOR UPDATE on both code tables are denied (1142)") {
        runtime { c ->
            listOf("merchant_site_code" to "site_code", "contract_code" to "contract_code").associate { (table, column) ->
                table to listOf(
                    c.errorCodeOf("UPDATE $table SET $column = $column WHERE 1 = 0"),
                    c.errorCodeOf("DELETE FROM $table WHERE 1 = 0"),
                    c.errorCodeOf("SELECT * FROM $table WHERE 1 = 0 FOR UPDATE"),
                    c.errorCodeOf("SELECT COUNT(*) FROM $table"),
                )
            }
        } shouldBe mapOf("merchant_site_code" to listOf(1142, 1142, 1142, null), "contract_code" to listOf(1142, 1142, 1142, null))
    }
})
