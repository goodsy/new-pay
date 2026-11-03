package com.paycore.admin.support

import com.paycore.testsupport.containers.InitializedMySqlSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.sql.Connection

/**
 * TEST-PERF-002(D-TEST-PERF-002): DB 시험 컨테이너는 초기화 이미지 사본([InitializedMySqlSnapshot])에서 만들어도 **컨텍스트마다 새 서버**다.
 *
 * - 사본 컨테이너 둘은 서로 다른 컨테이너이고, 한쪽의 쓰기(schema·계정)는 다른 쪽에 보이지 않는다(공유·reuse 없음).
 * - 사본의 시작 상태(서버 버전·binlog·OS 시간대·초기화 스크립트가 만든 계정·role·권한·procedure·schema)는 빈 MySQL에서 초기화 스크립트를
 *   이번에 실제로 실행한 서버([AdminDbAccounts.startedFreshContainer])와 같다. 다른 것은 datadir 위치뿐이다(commit이 volume을 담지 않으므로).
 */
class InitializedMySqlSnapshotIsolationSpec : FunSpec({

    /** 초기화가 만든 상태 — datadir 위치는 따로 본다. */
    fun initialState(c: Connection): Map<String, Any?> {
        val users = c.column("SELECT CONCAT('''', User, '''@''', Host, '''') FROM mysql.user WHERE User LIKE 'paycore%' ORDER BY User, Host")
        return linkedMapOf(
            "version" to c.scalar("SELECT VERSION()"),
            "log_bin" to c.scalar("SELECT @@log_bin").toString(),
            "system_time_zone" to c.scalar("SELECT @@system_time_zone"),
            "server options" to c.column(
                "SELECT CONCAT(@@innodb_flush_log_at_trx_commit, '/', @@sync_binlog, '/', @@innodb_doublewrite)",
            ),
            "users" to users,
            "grants" to users.associateWith { c.column("SHOW GRANTS FOR $it").sorted() },
            "default roles" to c.column("SELECT CONCAT(USER, '->', DEFAULT_ROLE_USER) FROM mysql.default_roles ORDER BY USER, DEFAULT_ROLE_USER"),
            "locked" to c.column("SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE account_locked = 'Y' AND User LIKE 'paycore%' ORDER BY User"),
            "schemas" to c.column("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'paycore%' ORDER BY SCHEMA_NAME"),
            "routines" to c.column(
                "SELECT CONCAT(ROUTINE_SCHEMA, '.', ROUTINE_NAME, ':', DEFINER, ':', MD5(ROUTINE_DEFINITION)) FROM information_schema.ROUTINES " +
                    "WHERE ROUTINE_SCHEMA LIKE 'paycore%' ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME",
            ),
            "tables" to c.column(
                "SELECT CONCAT(TABLE_SCHEMA, '.', TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_SCHEMA LIKE 'paycore%' ORDER BY 1",
            ),
        )
    }

    test("two containers from the initialized snapshot are separate servers that start like a freshly initialized one") {
        val first = AdminDbAccounts.startedContainer()
        val second = AdminDbAccounts.startedContainer()
        val fresh = AdminDbAccounts.startedFreshContainer()
        try {
            first.containerId shouldNotBe second.containerId
            first.dockerImageName shouldStartWith InitializedMySqlSnapshot.REPOSITORY
            fresh.dockerImageName shouldBe "mysql:8.4.11"

            val freshState = AdminDbAccounts.root(fresh).use(::initialState)
            AdminDbAccounts.root(first).use(::initialState) shouldBe freshState
            AdminDbAccounts.root(second).use(::initialState) shouldBe freshState
            (freshState.getValue("version") as String) shouldStartWith "8.4.11"
            freshState.getValue("log_bin") shouldBe "1"
            freshState.getValue("system_time_zone") shouldBe "KST"
            (freshState.getValue("users") as List<*>) shouldContain "'${AdminDbAccounts.ADMIN_RUNTIME_USER}'@'%'"

            AdminDbAccounts.root(first).use { it.scalar("SELECT @@datadir") } shouldBe InitializedMySqlSnapshot.DATADIR + "/"
            AdminDbAccounts.root(fresh).use { it.scalar("SELECT @@datadir") } shouldBe "/var/lib/mysql/"

            // 초기화 스크립트가 만든 migration 계정으로 사본 서버에 실제로 접속된다(비밀번호는 이 JVM의 일회용 값). runtime 계정은 Flyway 권한
            // migration 전에는 어떤 DB에도 권한이 없으므로(새 초기화 서버도 같다) 여기서는 migration 계정으로 본다.
            AdminDbAccounts.connect(second, AdminDbAccounts.ADMIN_MIGRATION_USER).use { it.scalar("SELECT CURRENT_USER()") } shouldBe
                "${AdminDbAccounts.ADMIN_MIGRATION_USER}@%"

            AdminDbAccounts.root(first).use { c ->
                c.update("CREATE DATABASE snapshot_isolation_probe")
                c.update("CREATE USER 'snapshot_isolation_probe'@'%' IDENTIFIED BY 'x'")
            }
            AdminDbAccounts.root(second).use { c ->
                c.scalar("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'snapshot_isolation_probe'").toString() shouldBe "0"
                c.scalar("SELECT COUNT(*) FROM mysql.user WHERE User = 'snapshot_isolation_probe'").toString() shouldBe "0"
            }
            // 한 사본의 쓰기는 이미지에도 남지 않는다: 그 뒤 만든 사본도 깨끗하다.
            val third = AdminDbAccounts.startedContainer()
            try {
                AdminDbAccounts.root(third).use(::initialState) shouldBe freshState
            } finally {
                runCatching { third.stop() }
            }
        } finally {
            runCatching { first.stop() }
            runCatching { second.stop() }
            runCatching { fresh.stop() }
        }
    }
})
