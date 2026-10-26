package com.paycore.admin

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbAccounts.ADMIN_DATABASE
import com.paycore.admin.support.AdminDbAccounts.ADMIN_RUNTIME_USER
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.column
import com.paycore.admin.support.errorCodeOf
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * ADMIN DB schema(ADMIN-BOOT-001 §4.2.2 — 표 8종 + Spring Session 표 2종, Flyway db/migration/admin). 제약은 DB가 강제한다:
 * 문자열 키 `utf8mb4_0900_bin`, 시각 `DATETIME(3)`, 상태 값 CHECK, 같은 (종류, 대상) PENDING 승인 하나(생성 열 `pending_key` UNIQUE — n-6),
 * 열린 복구 회차 하나(`open_marker` UNIQUE — §4.9.2), 부트스트랩 고정 행 `id = 1`(§4.9.1), 역할 집합은 고정 값 SET.
 * 삽입은 runtime 계정으로 한다(권한이 충분하다는 증거도 겸한다).
 */
@SpringBootTest
@AdminDbTest
class AdminSchemaSpec(private val container: MySQLContainer, private val dataSource: DataSource) : BehaviorSpec({

    fun runtime(): Connection = AdminDbAccounts.connect(container, ADMIN_RUNTIME_USER)
    fun root(): Connection = AdminDbAccounts.root(container)
    fun id(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "").take(26)

    fun Connection.insertAccount(actorId: String, loginName: String = "login-$actorId", roles: String = ""): Int? = errorCodeOf(
        "INSERT INTO admin_account (actor_id, login_name, display_name, status, credential_basis, active_roles, suspended_roles, failed_attempts, " +
            "session_generation, security_version, version, created_at, updated_at) " +
            "VALUES (?, ?, 'Display', 'PENDING_ENROLLMENT', 'x', ?, '', 0, 0, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
        actorId, loginName, roles,
    )

    fun Connection.insertApproval(approvalId: String, kind: String, target: String, status: String = "PENDING"): Int? = errorCodeOf(
        "INSERT INTO admin_approval_request (approval_id, kind, target_actor_id, target_security_version, target_snapshot, requested_by, " +
            "requested_by_display_name, requested_at, expires_at, status, version) " +
            "VALUES (?, ?, ?, 0, '{}', 'adm_requester', 'Requester', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?, 0)",
        approvalId, kind, target, status,
    )

    Given("the ADMIN schema after the Flyway migrations") {
        Then("it has exactly the ADMIN tables and the Flyway history") {
            root().use { c ->
                c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name",
                    ADMIN_DATABASE,
                )
            } shouldBe listOf(
                "admin_account", "admin_approval_request", "admin_audit_event", "admin_bootstrap", "admin_enrollment_code",
                "admin_recovery_episode", "admin_role_event", "admin_session", "admin_session_attributes", "flyway_schema_history",
            )
        }

        Then("every ADMIN table is InnoDB with utf8mb4_0900_bin and every string column compares binary (keys are compared as given)") {
            root().use { c ->
                c.column(
                    "SELECT CONCAT(table_name, ':', engine, ':', table_collation) FROM information_schema.tables " +
                        "WHERE table_schema = ? AND table_name LIKE 'admin\\_%' AND (engine <> 'InnoDB' OR table_collation <> 'utf8mb4_0900_bin')",
                    ADMIN_DATABASE,
                ) + c.column(
                    "SELECT CONCAT(table_name, '.', column_name, ':', collation_name) FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name LIKE 'admin\\_%' AND collation_name IS NOT NULL AND collation_name <> 'utf8mb4_0900_bin'",
                    ADMIN_DATABASE,
                )
            } shouldBe emptyList()
        }

        Then("every time column is DATETIME(3) (UTC instants, millisecond precision)") {
            root().use { c ->
                c.column(
                    "SELECT CONCAT(table_name, '.', column_name, ':', column_type) FROM information_schema.columns " +
                        "WHERE table_schema = ? AND table_name NOT IN ('flyway_schema_history', 'admin_session', 'admin_session_attributes') " +
                        "AND (column_name LIKE '%\\_at' OR data_type IN ('datetime', 'timestamp')) AND column_type <> 'datetime(3)'",
                    ADMIN_DATABASE,
                )
            } shouldBe emptyList()
        }

        Then("status, purpose and event-type columns accept only their fixed values (CHECK 3819)") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor) shouldBe null
                mapOf(
                    "account status" to c.errorCodeOf("UPDATE admin_account SET status = 'DELETED' WHERE actor_id = ?", actor),
                    "pending purpose" to c.errorCodeOf("UPDATE admin_account SET pending_purpose = 'GUESS' WHERE actor_id = ?", actor),
                    "approval status" to c.insertApproval(id("apr_"), "ROLE_GRANT", actor, status = "MAYBE"),
                    "approval kind" to c.insertApproval(id("apr_"), "DROP_EVERYTHING", actor),
                    "code purpose" to c.errorCodeOf(
                        "INSERT INTO admin_enrollment_code (code_hash, target_actor_id, purpose, basis, issued_at, expires_at) " +
                            "VALUES (UNHEX(SHA2(?, 256)), ?, 'WHATEVER', 'b', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                        id("c"), actor,
                    ),
                    "role event type" to c.errorCodeOf(
                        "INSERT INTO admin_role_event (target_actor_id, role, event_type, actor_kind, occurred_at) " +
                            "VALUES (?, 'APPROVER', 'DELETE', 'ADMIN', UTC_TIMESTAMP(3))",
                        actor,
                    ),
                )
            } shouldBe mapOf(
                "account status" to 3819, "pending purpose" to 3819, "approval status" to 3819, "approval kind" to 3819,
                "code purpose" to 3819, "role event type" to 3819,
            )
        }

        Then("owner-service approval states are not storable yet (APPROVED / EXECUTING / EXECUTION_UNKNOWN — 3819, widened with their kinds later)") {
            runtime().use { c ->
                val target = id("adm_")
                c.insertAccount(target)
                listOf("APPROVED", "EXECUTING", "EXECUTION_UNKNOWN").associateWith { c.insertApproval(id("apr_"), "ROLE_GRANT", target, status = it) }
            } shouldBe mapOf("APPROVED" to 3819, "EXECUTING" to 3819, "EXECUTION_UNKNOWN" to 3819)
        }

        Then("cross-column rules: RECOVERY codes need an episode and the recorded roles, recorded roles only on RECOVERY codes, a pending purpose needs its basis and PENDING_ENROLLMENT (3819)") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                fun code(purpose: String, episode: Long?, roles: String?) = c.errorCodeOf(
                    "INSERT INTO admin_enrollment_code (code_hash, target_actor_id, purpose, basis, recovery_episode_no, recovery_roles, issued_at, expires_at) " +
                        "VALUES (UNHEX(SHA2(?, 256)), ?, ?, 'b', ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    id("c"), actor, purpose, episode, roles,
                )
                mapOf(
                    "recovery without episode" to code("RECOVERY", null, "APPROVER"),
                    "recovery without recorded roles" to code("RECOVERY", 7, null),
                    "recorded roles on ENROLL" to code("ENROLL", 7, "APPROVER"),
                    "recovery ok (empty role set allowed)" to code("RECOVERY", 7, ""),
                    "work-B ENROLL code with episode ok" to code("ENROLL", 7, null),
                    "purpose without basis" to c.errorCodeOf("UPDATE admin_account SET pending_purpose = 'ENROLL' WHERE actor_id = ?", actor),
                    "basis without purpose" to c.errorCodeOf("UPDATE admin_account SET pending_basis = 'x' WHERE actor_id = ?", actor),
                    "purpose on ACTIVE" to c.errorCodeOf(
                        "UPDATE admin_account SET status = 'ACTIVE', pending_purpose = 'ENROLL', pending_basis = 'x' WHERE actor_id = ?", actor,
                    ),
                    "purpose+basis on PENDING ok" to c.errorCodeOf(
                        "UPDATE admin_account SET pending_purpose = 'ENROLL', pending_basis = 'x' WHERE actor_id = ?", actor,
                    ),
                )
            } shouldBe mapOf(
                "recovery without episode" to 3819, "recovery without recorded roles" to 3819, "recorded roles on ENROLL" to 3819,
                "recovery ok (empty role set allowed)" to null, "work-B ENROLL code with episode ok" to null,
                "purpose without basis" to 3819, "basis without purpose" to 3819, "purpose on ACTIVE" to 3819, "purpose+basis on PENDING ok" to null,
            )
        }
        Then("credential basis rules (D-ADMIN1-014, S5 re-review m-3 — 3819): pending basis = credential basis, approver only with a different requester, the account is never its own issuer") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                fun set(sql: String) = c.errorCodeOf("UPDATE admin_account SET $sql WHERE actor_id = ?", actor)
                mapOf(
                    "pending basis differs" to set("pending_purpose = 'ENROLL', pending_basis = 'y'"),
                    "approver without requester" to set("credential_basis_approver = 'adm_a'"),
                    "approver = requester" to set("credential_basis_requester = 'adm_a', credential_basis_approver = 'adm_a'"),
                    "requester = the account" to set("credential_basis_requester = actor_id"),
                    "approver = the account" to set("credential_basis_requester = 'adm_a', credential_basis_approver = actor_id"),
                    "requester + approver ok" to set("credential_basis_requester = 'adm_a', credential_basis_approver = 'adm_b'"),
                )
            } shouldBe mapOf(
                "pending basis differs" to 3819, "approver without requester" to 3819, "approver = requester" to 3819, "requester = the account" to 3819,
                "approver = the account" to 3819, "requester + approver ok" to null,
            )
        }
        Then("the pre-suspension status exists exactly for SUSPENDED accounts (S3 review nit — 3819)") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                mapOf(
                    "SUSPENDED without previous status" to c.errorCodeOf("UPDATE admin_account SET status = 'SUSPENDED' WHERE actor_id = ?", actor),
                    "previous status while PENDING" to c.errorCodeOf("UPDATE admin_account SET status_before_suspend = 'ACTIVE' WHERE actor_id = ?", actor),
                    "SUSPENDED with previous status ok" to c.errorCodeOf(
                        "UPDATE admin_account SET status = 'SUSPENDED', status_before_suspend = 'PENDING_ENROLLMENT' WHERE actor_id = ?", actor,
                    ),
                )
            } shouldBe mapOf("SUSPENDED without previous status" to 3819, "previous status while PENDING" to 3819, "SUSPENDED with previous status ok" to null)
        }

        Then("S2 review M-1: creating two different-kind approvals for the same target (INSERT, then lock accounts in actor order) never deadlocks") {
            // §4.13 승인 요청 생성 순서: 승인 행 INSERT → 요청자·대상 계정 FOR UPDATE(actor ID 오름차순). 승인 행에 대상 FK가 있으면 INSERT가 부모 계정 행에
            // S 잠금을 걸어, 두 TX가 서로의 S 잠금 때문에 X 잠금을 못 얻고 1213이 된다. FK가 없으면 두 번째 TX는 첫 TX 커밋까지 기다릴 뿐이다.
            val requester = id("adm_")
            val target = id("adm_")
            runtime().use { c -> c.insertAccount(requester); c.insertAccount(target) }
            val first = runtime()
            val second = runtime()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            try {
                first.autoCommit = false
                second.autoCommit = false
                first.insertApproval(id("apr_"), "ROLE_GRANT", target)?.let(errors::add)
                second.insertApproval(id("apr_"), "ACCOUNT_UNLOCK", target)?.let(errors::add)
                val lockSql = "SELECT actor_id FROM admin_account WHERE actor_id IN (?, ?) ORDER BY actor_id FOR UPDATE"
                val worker = Thread {
                    first.errorCodeOf(lockSql, requester, target)?.let(errors::add)
                    Thread.sleep(500)
                    first.rollback()
                }.apply { start() }
                Thread.sleep(200)
                second.errorCodeOf(lockSql, requester, target)?.let(errors::add)
                second.rollback()
                worker.join(15_000)
            } finally {
                first.close()
                second.close()
            }
            errors.toList() shouldBe emptyList()
        }

        Then("role sets accept only the fixed roles (strict SET — unknown role 1265)") {
            runtime().use { c ->
                listOf(c.insertAccount(id("adm_"), roles = "APPROVER,SECURITY_ADMIN"), c.insertAccount(id("adm_"), roles = "APPROVER,ROOT"))
            } shouldBe listOf(null, 1265)
        }

        Then("login names are unique (1062)") {
            runtime().use { c ->
                val login = "same-login-${UUID.randomUUID()}"
                listOf(c.insertAccount(id("adm_"), login), c.insertAccount(id("adm_"), login))
            } shouldBe listOf(null, 1062)
        }

        Then("only one PENDING approval per (kind, target): a second is 1062, after the first is decided a new one is accepted, another kind is accepted") {
            runtime().use { c ->
                val target = id("adm_")
                c.insertAccount(target)
                val first = id("apr_")
                listOf(
                    c.insertApproval(first, "ROLE_GRANT", target),
                    c.insertApproval(id("apr_"), "ROLE_GRANT", target),
                    c.insertApproval(id("apr_"), "ACCOUNT_UNLOCK", target),
                    c.errorCodeOf("UPDATE admin_approval_request SET status = 'EXECUTED' WHERE approval_id = ?", first),
                    c.insertApproval(id("apr_"), "ROLE_GRANT", target),
                )
            } shouldBe listOf(null, 1062, null, null, null)
        }

        Then("only one open recovery episode: a second open one is 1062, after closing a new one is accepted") {
            runtime().use { c ->
                val base = ((c.scalar("SELECT COALESCE(MAX(episode_no), 0) FROM admin_recovery_episode") as Number).toLong()) + 1
                fun open(n: Long) = c.errorCodeOf(
                    "INSERT INTO admin_recovery_episode (episode_no, opened_at, expires_at) VALUES (?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", n,
                )
                listOf(
                    open(base),
                    open(base + 1),
                    c.errorCodeOf("UPDATE admin_recovery_episode SET closed_at = UTC_TIMESTAMP(3) WHERE episode_no = ?", base),
                    open(base + 1),
                    c.errorCodeOf("UPDATE admin_recovery_episode SET closed_at = UTC_TIMESTAMP(3) WHERE episode_no = ?", base + 1),
                )
            } shouldBe listOf(null, 1062, null, null, null)
        }

        Then("the bootstrap row is the single fixed row id = 1 (second insert 1062, other id 3819)") {
            runtime().use { c ->
                val a = id("adm_")
                val b = id("adm_")
                fun insert(rowId: Int) = c.errorCodeOf(
                    "INSERT INTO admin_bootstrap (id, executed_at, security_admin_actor_id, approver_actor_id) VALUES (?, UTC_TIMESTAMP(3), ?, ?)",
                    rowId, a, b,
                )
                listOf(insert(2), insert(1), insert(1))
            } shouldBe listOf(3819, null, 1062)
        }

        Then("role events and enrollment codes refer to existing accounts (FK 1452)") {
            runtime().use { c ->
                val ghost = id("adm_")
                listOf(
                    c.errorCodeOf(
                        "INSERT INTO admin_role_event (target_actor_id, role, event_type, actor_kind, occurred_at) VALUES (?, 'APPROVER', 'GRANT', 'ADMIN', UTC_TIMESTAMP(3))",
                        ghost,
                    ),
                    c.errorCodeOf(
                        "INSERT INTO admin_enrollment_code (code_hash, target_actor_id, purpose, basis, issued_at, expires_at) " +
                            "VALUES (UNHEX(SHA2(?, 256)), ?, 'ENROLL', 'b', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                        id("c"), ghost,
                    ),
                )
            } shouldBe listOf(1452, 1452)
        }

        Then("the Spring Session tables have the Spring Session JDBC columns under lower-case names (attribute table admin_session_attributes)") {
            root().use { c ->
                listOf("admin_session", "admin_session_attributes").associateWith { t ->
                    c.column(
                        "SELECT UPPER(column_name) FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                        ADMIN_DATABASE, t,
                    )
                }
            } shouldBe mapOf(
                "admin_session" to listOf(
                    "PRIMARY_ID", "SESSION_ID", "CREATION_TIME", "LAST_ACCESS_TIME", "MAX_INACTIVE_INTERVAL", "EXPIRY_TIME", "PRINCIPAL_NAME",
                ),
                "admin_session_attributes" to listOf("SESSION_PRIMARY_ID", "ATTRIBUTE_NAME", "ATTRIBUTE_BYTES"),
            )
        }

        Then("the application pool session is UTC (driver properties), independent of the container OS time zone") {
            dataSource.connection.use { c -> listOf(c.scalar("SELECT @@system_time_zone").toString(), c.scalar("SELECT @@session.time_zone").toString()) } shouldBe
                listOf("KST", "+00:00")
        }
    }
})
