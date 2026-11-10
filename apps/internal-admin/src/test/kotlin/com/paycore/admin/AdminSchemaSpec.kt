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
        "INSERT INTO admin_approval_request (approval_id, kind, target_kind, target_actor_id, target_security_version, target_snapshot, requested_by, " +
            "requested_by_display_name, requested_at, expires_at, status, version) " +
            "VALUES (?, ?, 'ADMIN_ACCOUNT', ?, 0, '{}', 'adm_requester', 'Requester', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?, 0)",
        approvalId, kind, target, status,
    )

    /**
     * 승인 행을 열 조합 그대로 넣는다(V5 CHECK 시험용 Invalid Fixture — 대상 종류·계정·참조를 시험이 명시한다, null 대상 종류는 열 생략).
     * 결정 열은 상태가 PENDING이 아니면 승인자로 채운다.
     */
    fun Connection.insertTargeted(approvalId: String, kind: String, targetKind: String?, actor: String?, ref: String?, status: String = "PENDING"): Int? {
        val decided = status != "PENDING"
        val columns = listOfNotNull("approval_id", "kind", if (targetKind != null) "target_kind" else null, "target_actor_id", "target_ref")
        val values = listOf<Any?>(approvalId, kind) + (if (targetKind != null) listOf(targetKind) else emptyList()) + listOf(actor, ref)
        return errorCodeOf(
            "INSERT INTO admin_approval_request (${columns.joinToString()}, target_security_version, target_snapshot, requested_by, requested_by_display_name, " +
                "requested_at, expires_at, status, decided_by, decided_by_display_name, decided_at, version) " +
                "VALUES (${columns.joinToString { "?" }}, 3, '{}', 'adm_requester', 'Requester', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?, " +
                (if (decided) "'adm_approver', 'Approver', UTC_TIMESTAMP(3)" else "NULL, NULL, NULL") + ", 0)",
            *(values + status).toTypedArray(),
        )
    }

    fun Connection.insertPaymentApproval(approvalId: String, paymentId: String, status: String = "PENDING"): Int? =
        insertTargeted(approvalId, "OPERATOR_CORRECTION_CANCEL", "PAYMENT", null, paymentId, status)

    fun paymentId(): String = "pay_" + UUID.randomUUID().toString().replace("-", "").uppercase().take(26)

    /** 서로 다른 원 파일 ID(매입사 코드에 임의 값 — 파일 계약 v1 문법). */
    fun fileId(): String = "ACQ.S" + UUID.randomUUID().toString().replace("-", "").uppercase().take(20) + ".TEST.20261105.001"

    /** 파일 대상 재매입 승인 행(V7 — 대상 버전 0, 결정 열은 PENDING이 아니면 승인자로). */
    fun Connection.insertFileApproval(approvalId: String, fileId: String, status: String = "PENDING"): Int? {
        val decided = status != "PENDING"
        return errorCodeOf(
            "INSERT INTO admin_approval_request (approval_id, kind, target_kind, target_actor_id, target_ref, target_security_version, target_snapshot, requested_by, " +
                "requested_by_display_name, requested_at, expires_at, status, decided_by, decided_by_display_name, decided_at, version) " +
                "VALUES (?, 'ACQ_REACQUISITION', 'ACQUISITION_FILE', NULL, ?, 0, '{}', 'adm_requester', 'Requester', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?, " +
                (if (decided) "'adm_approver', 'Approver', UTC_TIMESTAMP(3)" else "NULL, NULL, NULL") + ", 0)",
            approvalId, fileId, status,
        )
    }

    /** 서로 다른 가맹점 ID(`mer_` + 대문자 hex 26자 — Crockford ULID 문자 집합 안). */
    fun merchantId(): String = "mer_" + UUID.randomUUID().toString().replace("-", "").uppercase().take(26)

    /** 가맹점 대상 승인 행(V9 — 대상 버전 0, 결정 열은 PENDING이 아니면 승인자로). [actor]·[kind]는 Invalid Fixture가 바꾼다. */
    fun Connection.insertMerchantApproval(
        approvalId: String, merchantId: String?, status: String = "PENDING", kind: String = "MERCHANT_CONTRACT_APPROVAL", actor: String? = null,
    ): Int? {
        val decided = status != "PENDING"
        return errorCodeOf(
            "INSERT INTO admin_approval_request (approval_id, kind, target_kind, target_actor_id, target_ref, target_security_version, target_snapshot, requested_by, " +
                "requested_by_display_name, requested_at, expires_at, status, decided_by, decided_by_display_name, decided_at, version) " +
                "VALUES (?, ?, 'MERCHANT', ?, ?, 0, '{}', 'adm_requester', 'Requester', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?, " +
                (if (decided) "'adm_approver', 'Approver', UTC_TIMESTAMP(3)" else "NULL, NULL, NULL") + ", 0)",
            approvalId, kind, actor, merchantId, status,
        )
    }

    Given("the ADMIN schema after the Flyway migrations") {
        Then("it has exactly the ADMIN tables and the Flyway history") {
            root().use { c ->
                c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name",
                    ADMIN_DATABASE,
                )
            } shouldBe listOf(
                // 기대값 변경(B2-2 MERCHANT-OPS-002 §9.5 V9): 가맹점 승인 명령 표 admin_merchant_ops_command 추가.
                "admin_account", "admin_acquiring_ops_command", "admin_approval_request", "admin_audit_event", "admin_bootstrap", "admin_enrollment_code",
                "admin_merchant_ops_command", "admin_operator_cancel_command", "admin_ops_reconciliation_checkpoint", "admin_ops_reconciliation_finding", "admin_owner_execution",
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

        Then("V5 (§4.11 ck_owner_states): EXECUTING / EXECUTION_UNKNOWN only for the owner-service kind; APPROVED for no kind (I-20) — 3819") {
            runtime().use { c ->
                val target = id("adm_")
                c.insertAccount(target)
                mapOf(
                    "ROLE_GRANT APPROVED" to c.insertApproval(id("apr_"), "ROLE_GRANT", target, status = "APPROVED"),
                    "ROLE_GRANT EXECUTING" to c.insertApproval(id("apr_"), "ROLE_GRANT", target, status = "EXECUTING"),
                    "ROLE_GRANT EXECUTION_UNKNOWN" to c.insertApproval(id("apr_"), "ROLE_GRANT", target, status = "EXECUTION_UNKNOWN"),
                    "ACCOUNT_UNLOCK EXECUTING" to c.insertApproval(id("apr_"), "ACCOUNT_UNLOCK", target, status = "EXECUTING"),
                    "OPERATOR APPROVED" to c.insertPaymentApproval(id("apr_"), paymentId(), status = "APPROVED"),
                    "OPERATOR EXECUTING" to c.insertPaymentApproval(id("apr_"), paymentId(), status = "EXECUTING"),
                    "OPERATOR EXECUTION_UNKNOWN" to c.insertPaymentApproval(id("apr_"), paymentId(), status = "EXECUTION_UNKNOWN"),
                )
            } shouldBe mapOf(
                "ROLE_GRANT APPROVED" to 3819, "ROLE_GRANT EXECUTING" to 3819, "ROLE_GRANT EXECUTION_UNKNOWN" to 3819, "ACCOUNT_UNLOCK EXECUTING" to 3819,
                "OPERATOR APPROVED" to 3819, "OPERATOR EXECUTING" to null, "OPERATOR EXECUTION_UNKNOWN" to null,
            )
        }

        Then("V5 (§4.11 ck_target): target columns match the target kind and the target kind matches the approval kind (3819); target_kind has no default (1364)") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                val pay = paymentId()
                mapOf(
                    "account ok" to c.insertTargeted(id("apr_"), "ACCOUNT_UNLOCK", "ADMIN_ACCOUNT", actor, null),
                    "account with ref" to c.insertTargeted(id("apr_"), "ACCOUNT_REACTIVATE", "ADMIN_ACCOUNT", actor, pay),
                    "account without actor" to c.insertTargeted(id("apr_"), "TOTP_REENROLL", "ADMIN_ACCOUNT", null, null),
                    "account kind on PAYMENT" to c.insertTargeted(id("apr_"), "PASSWORD_RESET", "PAYMENT", null, pay),
                    "payment ok" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "PAYMENT", null, pay),
                    "payment with actor" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "PAYMENT", actor, paymentId()),
                    "payment without ref" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "PAYMENT", null, null),
                    "operator kind on ADMIN_ACCOUNT" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "ADMIN_ACCOUNT", actor, null),
                    "unknown target kind" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "MERCHANT", null, paymentId()),
                    "no target kind" to c.insertTargeted(id("apr_"), "ACCOUNT_UNLOCK", null, actor, null, status = "EXECUTED"),
                )
            } shouldBe mapOf(
                "account ok" to null, "account with ref" to 3819, "account without actor" to 3819, "account kind on PAYMENT" to 3819, "payment ok" to null,
                "payment with actor" to 3819, "payment without ref" to 3819, "operator kind on ADMIN_ACCOUNT" to 3819, "unknown target kind" to 3819,
                "no target kind" to 1364,
            )
        }

        Then("V5 (§4.11 unresolved_key, I-14): one unresolved (PENDING / EXECUTING / EXECUTION_UNKNOWN) approval per Payment — 1062; terminal states free the Payment") {
            val terminalStatuses = listOf("EXECUTED", "OWNER_REJECTED", "REJECTED", "WITHDRAWN", "EXPIRED", "INVALIDATED")
            val result = runtime().use { c ->
                val unresolved = listOf("PENDING", "EXECUTING", "EXECUTION_UNKNOWN").associateWith { status ->
                    val pay = paymentId()
                    listOf(c.insertPaymentApproval(id("apr_"), pay, status), c.insertPaymentApproval(id("apr_"), pay, "PENDING"))
                }
                val terminal = terminalStatuses.associateWith { status ->
                    val pay = paymentId()
                    listOf(c.insertPaymentApproval(id("apr_"), pay, status), c.insertPaymentApproval(id("apr_"), pay, status), c.insertPaymentApproval(id("apr_"), pay, "PENDING"))
                }
                unresolved to terminal
            }
            result shouldBe (
                mapOf("PENDING" to listOf(null, 1062), "EXECUTING" to listOf(null, 1062), "EXECUTION_UNKNOWN" to listOf(null, 1062)) to
                    terminalStatuses.associateWith { listOf<Int?>(null, null, null) }
                )
        }

        Then("V5: the unresolved key is the Payment reference while unresolved and is released when the approval ends (runtime UPDATE of status)") {
            val pay = paymentId()
            runtime().use { c ->
                val approval = id("apr_")
                c.insertPaymentApproval(approval, pay, "EXECUTING")
                fun key() = c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", approval)
                val executing = key()
                c.update("UPDATE admin_approval_request SET status = 'EXECUTION_UNKNOWN' WHERE approval_id = ?", approval)
                val unknown = key()
                c.update("UPDATE admin_approval_request SET status = 'EXECUTED' WHERE approval_id = ?", approval)
                listOf(executing, unknown, key(), c.insertPaymentApproval(id("apr_"), pay))
            } shouldBe listOf("PAYMENT:$pay", "PAYMENT:$pay", null, null)
        }

        Then("V5: unresolved_key and pending_key are STORED generated columns; account rows never take an unresolved key") {
            root().use { c ->
                c.column(
                    "SELECT CONCAT(column_name, ':', extra) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'admin_approval_request' " +
                        "AND column_name IN ('pending_key', 'unresolved_key') ORDER BY column_name",
                    ADMIN_DATABASE,
                )
            } shouldBe listOf("pending_key:STORED GENERATED", "unresolved_key:STORED GENERATED")
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                val approval = id("apr_")
                c.insertApproval(approval, "ACCOUNT_UNLOCK", actor)
                c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", approval)
            } shouldBe null
        }

        Then("V5 command (INSERT-only, §4.11): amount > 0, known purpose kinds, an existing approval (FK 1452); one command per approval (1062)") {
            runtime().use { c ->
                fun command(approval: String, amount: Long = 7000, purpose: String = "INCIDENT_OR_RECON_CORRECTION") = c.errorCodeOf(
                    "INSERT INTO admin_operator_cancel_command (approval_id, payment_id, amount_minor, currency, purpose_kind, purpose_ref, reason, pinned_target, created_at) " +
                        "VALUES (?, 'pay_01JC0000000000000000000001', ?, 'KRW', ?, 'RECON-1', 'reason', '{}', UTC_TIMESTAMP(3))",
                    approval, amount, purpose,
                )
                val approval = id("apr_")
                c.insertPaymentApproval(approval, paymentId())
                mapOf(
                    "zero amount" to command(approval, amount = 0), "unknown purpose" to command(approval, purpose = "WHIM"), "no approval" to command(id("apr_")),
                    "ok" to command(approval), "second" to command(approval),
                )
            } shouldBe mapOf("zero amount" to 3819, "unknown purpose" to 3819, "no approval" to 1452, "ok" to null, "second" to 1062)
        }

        Then("V5 execution: attempt ≥ 1, oct1 fingerprint format, deadline after approval, an existing approval (FK 1452)") {
            runtime().use { c ->
                fun execution(approval: String, attempts: Int = 1, fingerprint: String = "oct1:" + "a".repeat(64), windowSeconds: Int = 60) = c.errorCodeOf(
                    "INSERT INTO admin_owner_execution (approval_id, request_body, fingerprint, approved_at, execute_before, executor_actor_id, attempt_count, " +
                        "next_attempt_at, version) VALUES (?, x'7b7d', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL ? SECOND, 'adm_approver', ?, UTC_TIMESTAMP(3), 0)",
                    approval, fingerprint, windowSeconds, attempts,
                )
                val approval = id("apr_")
                c.insertPaymentApproval(approval, paymentId(), "EXECUTING")
                mapOf(
                    "zero attempts" to execution(approval, attempts = 0), "upper-case hex" to execution(approval, fingerprint = "oct1:" + "A".repeat(64)),
                    "other version" to execution(approval, fingerprint = "oct2:" + "a".repeat(64)), "deadline not after approval" to execution(approval, windowSeconds = 0),
                    "no approval" to execution(id("apr_")), "ok" to execution(approval),
                )
            } shouldBe mapOf(
                "zero attempts" to 3819, "upper-case hex" to 3819, "other version" to 3819, "deadline not after approval" to 3819, "no approval" to 1452, "ok" to null,
            )
        }

        Then("V7 (ACQ-ADMIN-001 B1-A): the re-acquisition kind takes only a file target (file id grammar, version 0) and the owner-service states; other pairs stay 3819") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                mapOf(
                    "file version 3" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, fileId()),
                    "file executing" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, fileId(), status = "EXECUTING"),
                    "file unknown" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, fileId(), status = "EXECUTION_UNKNOWN"),
                    "file APPROVED" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, fileId(), status = "APPROVED"),
                    "file with actor" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", actor, fileId()),
                    "file without ref" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, null),
                    "file ref not a file id" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ACQUISITION_FILE", null, paymentId()),
                    "acq kind on PAYMENT" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "PAYMENT", null, paymentId()),
                    "operator kind on file" to c.insertTargeted(id("apr_"), "OPERATOR_CORRECTION_CANCEL", "ACQUISITION_FILE", null, fileId()),
                    "acq kind on account" to c.insertTargeted(id("apr_"), "ACQ_REACQUISITION", "ADMIN_ACCOUNT", actor, null),
                )
            } shouldBe mapOf(
                // insertTargeted의 대상 버전은 3 — 파일 대상은 0만(권위 대상 버전은 명령 행 pinned_target)이라 조합이 맞아도 3819.
                "file version 3" to 3819, "file executing" to 3819, "file unknown" to 3819, "file APPROVED" to 3819, "file with actor" to 3819, "file without ref" to 3819,
                "file ref not a file id" to 3819, "acq kind on PAYMENT" to 3819, "operator kind on file" to 3819, "acq kind on account" to 3819,
            )
            // 버전 0이면 들어간다(위 행은 insertTargeted의 버전 3 때문에 모두 3819 — 아래에서 0으로 확인).
            runtime().use { c ->
                listOf("PENDING", "EXECUTING", "EXECUTION_UNKNOWN").map { c.insertFileApproval(id("apr_"), fileId(), it) } + c.insertFileApproval(id("apr_"), fileId(), "APPROVED")
            } shouldBe listOf(null, null, null, 3819)
        }

        Then("V7 unresolved_key: one unresolved re-acquisition approval per original file (ACQFILE: — 1062), released when it ends; the Payment key is unchanged") {
            val file = fileId()
            val pay = paymentId()
            runtime().use { c ->
                val first = id("apr_")
                val inserts = listOf(c.insertFileApproval(first, file), c.insertFileApproval(id("apr_"), file), c.insertFileApproval(id("apr_"), fileId()))
                val key = c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", first)
                c.update("UPDATE admin_approval_request SET status = 'EXECUTING', decided_by = 'adm_x', decided_by_display_name = 'X', decided_at = UTC_TIMESTAMP(3) WHERE approval_id = ?", first)
                val whileExecuting = c.insertFileApproval(id("apr_"), file)
                c.update("UPDATE admin_approval_request SET status = 'EXECUTED' WHERE approval_id = ?", first)
                val payApproval = id("apr_")
                c.insertPaymentApproval(payApproval, pay)
                listOf(inserts, key, whileExecuting, c.insertFileApproval(id("apr_"), file), c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", payApproval))
            } shouldBe listOf(listOf(null, 1062, null), "ACQFILE:$file", 1062, null, "PAYMENT:$pay")
        }

        Then("V7 execution fingerprint: acd1 (re-acquisition content hash) is accepted next to oct1; other prefixes stay 3819") {
            runtime().use { c ->
                fun execution(approval: String, fingerprint: String) = c.errorCodeOf(
                    "INSERT INTO admin_owner_execution (approval_id, request_body, fingerprint, approved_at, execute_before, executor_actor_id, attempt_count, " +
                        "next_attempt_at, version) VALUES (?, x'7b7d', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 60 SECOND, 'adm_approver', 1, UTC_TIMESTAMP(3), 0)",
                    approval, fingerprint,
                )
                val approval = id("apr_")
                c.insertFileApproval(approval, fileId(), "EXECUTING")
                listOf(execution(approval, "acd2:" + "a".repeat(64)), execution(approval, "acd1:" + "A".repeat(64)), execution(approval, "acd1:" + "a".repeat(64)))
            } shouldBe listOf(3819, 3819, null)
        }

        Then("V7 command (INSERT-only): file id grammar, fixed reason codes, evidence grammar, an existing approval (FK 1452); one command per approval (1062)") {
            runtime().use { c ->
                fun command(approval: String, file: String = "ACQ.SIMACQA.TEST.20261105.001", code: String = "INSTITUTION_REQUESTED", evidence: String = "INQ-1") = c.errorCodeOf(
                    "INSERT INTO admin_acquiring_ops_command (approval_id, original_file_id, reason_code, evidence_ref, reason, pinned_target, created_at) " +
                        "VALUES (?, ?, ?, ?, 'reason', '{}', UTC_TIMESTAMP(3))",
                    approval, file, code, evidence,
                )
                val approval = id("apr_")
                c.insertFileApproval(approval, fileId())
                mapOf(
                    "bad file" to command(approval, file = "ACQ.x.TEST.1.001"), "unknown code" to command(approval, code = "WHIM"),
                    "bad evidence" to command(approval, evidence = "has space"), "no approval" to command(id("apr_")), "ok" to command(approval), "second" to command(approval),
                )
            } shouldBe mapOf("bad file" to 3819, "unknown code" to 3819, "bad evidence" to 3819, "no approval" to 1452, "ok" to null, "second" to 1062)
        }

        Then("V9 (MERCHANT-OPS-002 B2-2): the merchant kinds take only a MERCHANT target (mer_ ULID, version 0) and the owner-service states; MERCHANT takes no other kind") {
            runtime().use { c ->
                val actor = id("adm_")
                c.insertAccount(actor)
                mapOf(
                    "merchant version 3" to c.insertTargeted(id("apr_"), "MERCHANT_CONTRACT_APPROVAL", "MERCHANT", null, merchantId()),
                    "merchant with actor" to c.insertMerchantApproval(id("apr_"), merchantId(), actor = actor),
                    "merchant without ref" to c.insertMerchantApproval(id("apr_"), null),
                    "ref not a merchant id" to c.insertMerchantApproval(id("apr_"), paymentId()),
                    "lower-case merchant id" to c.insertMerchantApproval(id("apr_"), merchantId().lowercase().replace("mer_", "mer_")),
                    "merchant kind on PAYMENT" to c.insertTargeted(id("apr_"), "MERCHANT_CONTRACT_APPROVAL", "PAYMENT", null, paymentId()),
                    "acq kind on MERCHANT" to c.insertMerchantApproval(id("apr_"), merchantId(), kind = "ACQ_REACQUISITION"),
                    "account kind on MERCHANT" to c.insertMerchantApproval(id("apr_"), merchantId(), kind = "ROLE_GRANT"),
                    "unknown kind" to c.insertMerchantApproval(id("apr_"), merchantId(), kind = "MERCHANT_GUESS"),
                    "APPROVED" to c.insertMerchantApproval(id("apr_"), merchantId(), status = "APPROVED"),
                    "pending" to c.insertMerchantApproval(id("apr_"), merchantId()),
                    "executing" to c.insertMerchantApproval(id("apr_"), merchantId(), status = "EXECUTING"),
                    "unknown" to c.insertMerchantApproval(id("apr_"), merchantId(), status = "EXECUTION_UNKNOWN"),
                    "first MID kind (B2-3 — DB ready)" to c.insertMerchantApproval(id("apr_"), merchantId(), kind = "MERCHANT_FIRST_MID_ISSUANCE"),
                )
            } shouldBe mapOf(
                "merchant version 3" to 3819, "merchant with actor" to 3819, "merchant without ref" to 3819, "ref not a merchant id" to 3819, "lower-case merchant id" to 3819,
                "merchant kind on PAYMENT" to 3819, "acq kind on MERCHANT" to 3819, "account kind on MERCHANT" to 3819, "unknown kind" to 3819, "APPROVED" to 3819,
                "pending" to null, "executing" to null, "unknown" to null, "first MID kind (B2-3 — DB ready)" to null,
            )
        }

        Then("V9 unresolved_key: one unresolved merchant approval per merchant across both merchant kinds (MERCHANT: — C-20, 1062), released when it ends; file key unchanged") {
            val merchant = merchantId()
            val file = fileId()
            runtime().use { c ->
                val first = id("apr_")
                val inserts = listOf(
                    c.insertMerchantApproval(first, merchant), c.insertMerchantApproval(id("apr_"), merchant), c.insertMerchantApproval(id("apr_"), merchant, kind = "MERCHANT_FIRST_MID_ISSUANCE"),
                    c.insertMerchantApproval(id("apr_"), merchantId()),
                )
                val key = c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", first)
                c.update("UPDATE admin_approval_request SET status = 'EXECUTION_UNKNOWN', decided_by = 'adm_x', decided_by_display_name = 'X', decided_at = UTC_TIMESTAMP(3) WHERE approval_id = ?", first)
                val whileUnknown = c.insertMerchantApproval(id("apr_"), merchant)
                c.update("UPDATE admin_approval_request SET status = 'OWNER_REJECTED', result_code = 'APPROVAL_EXPIRED' WHERE approval_id = ?", first)
                val fileApproval = id("apr_")
                c.insertFileApproval(fileApproval, file)
                listOf(inserts, key, whileUnknown, c.insertMerchantApproval(id("apr_"), merchant), c.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", fileApproval))
            } shouldBe listOf(listOf(null, 1062, 1062, null), "MERCHANT:$merchant", 1062, null, "ACQFILE:$file")
        }

        Then("V9 execution fingerprint: mop1 (MASTER evidence fingerprint) is accepted next to oct1/acd1; other prefixes stay 3819") {
            runtime().use { c ->
                fun execution(approval: String, fingerprint: String) = c.errorCodeOf(
                    "INSERT INTO admin_owner_execution (approval_id, request_body, fingerprint, approved_at, execute_before, executor_actor_id, attempt_count, " +
                        "next_attempt_at, version) VALUES (?, x'7b7d', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 60 SECOND, 'adm_approver', 1, UTC_TIMESTAMP(3), 0)",
                    approval, fingerprint,
                )
                val approval = id("apr_")
                c.insertMerchantApproval(approval, merchantId(), status = "EXECUTING")
                listOf(execution(approval, "mop2:" + "a".repeat(64)), execution(approval, "mop1:" + "A".repeat(64)), execution(approval, "mop1:" + "a".repeat(64)))
            } shouldBe listOf(3819, 3819, null)
        }

        Then("V9 command (INSERT-only): command kind, merchant id grammar, required evidence in the reacquisition grammar, an existing approval (FK 1452); one per approval (1062)") {
            runtime().use { c ->
                fun command(approval: String, kind: String = "CONTRACT_APPROVAL", merchant: String = "mer_01JCME00000000000000000001", evidence: String? = "CONTRACT-2026/0001#p1") =
                    c.errorCodeOf(
                        "INSERT INTO admin_merchant_ops_command (approval_id, command_kind, merchant_id, pinned_target, evidence_ref, created_at) VALUES (?, ?, ?, '{}', ?, UTC_TIMESTAMP(3))",
                        approval, kind, merchant, evidence,
                    )
                val approval = id("apr_")
                c.insertMerchantApproval(approval, merchantId())
                mapOf(
                    "unknown kind" to command(approval, kind = "GUESS"), "bad merchant" to command(approval, merchant = "mer_x"), "no evidence" to command(approval, evidence = null),
                    "empty evidence" to command(approval, evidence = ""), "free text evidence" to command(approval, evidence = "see the contract"),
                    "129 chars" to command(approval, evidence = "x".repeat(129)), "no approval" to command(id("apr_")), "ok" to command(approval), "second" to command(approval),
                )
            } shouldBe mapOf(
                "unknown kind" to 3819, "bad merchant" to 3819, "no evidence" to 1048, "empty evidence" to 3819, "free text evidence" to 3819, "129 chars" to 1406,
                "no approval" to 1452, "ok" to null, "second" to 1062,
            )
        }

        Then("V9 reconciliation findings: OWNER_UNRESOLVABLE (C-28) is accepted next to the existing six, once per approval; other kinds stay 3819") {
            runtime().use { c ->
                val approval = id("apr_")
                fun finding(kind: String) = c.errorCodeOf(
                    "INSERT INTO admin_ops_reconciliation_finding (kind, approval_id, payment_ref, detail_code, found_at) VALUES (?, ?, 'mer_x', 'REQUEST_MALFORMED', UTC_TIMESTAMP(3))",
                    kind, approval,
                )
                listOf("OWNER_UNRESOLVABLE", "OWNER_UNRESOLVABLE", "ORPHAN_DECISION", "OWNER_GUESS").map(::finding)
            } shouldBe listOf(null, 1062, null, 3819)
        }

        Then("V5 reconciliation: the checkpoint is the single row id = 1 created by the migration (watermark 0); findings take only the 6 kinds, once per (kind, approval)") {
            root().use { c ->
                listOf(
                    c.column("SELECT CONCAT(id, ':', watermark) FROM admin_ops_reconciliation_checkpoint").joinToString(),
                    c.errorCodeOf("INSERT INTO admin_ops_reconciliation_checkpoint (id, watermark, version) VALUES (2, 0, 0)").toString(),
                    c.errorCodeOf("UPDATE admin_ops_reconciliation_checkpoint SET watermark = -1 WHERE id = 1").toString(),
                )
            } shouldBe listOf("1:0", "3819", "3819")
            runtime().use { c ->
                val approval = id("apr_")
                fun finding(kind: String) = c.errorCodeOf(
                    "INSERT INTO admin_ops_reconciliation_finding (kind, approval_id, payment_ref, detail_code, found_at) VALUES (?, ?, 'pay_x', 'CONTENT_MATCH', UTC_TIMESTAMP(3))",
                    kind, approval,
                )
                listOf(
                    "ORPHAN_DECISION", "FINGERPRINT_MISMATCH", "STATE_DIVERGENCE", "MISSING_DECISION", "UNRESOLVED_WITH_DECISION", "DECISION_FOR_UNAPPROVED", "GUESS",
                    "ORPHAN_DECISION",
                ).map(::finding)
            } shouldBe listOf(null, null, null, null, null, null, 3819, 1062)
        }

        Then("V5 upgrade: approval rows that existed before V5 become ADMIN_ACCOUNT targets without a Payment reference (fill, then no default)") {
            val schema = "paycore_admin_v5_upgrade"
            root().use { c ->
                c.update("DROP DATABASE IF EXISTS $schema")
                c.update("CREATE DATABASE $schema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin")
            }
            fun flyway(target: String) = org.flywaydb.core.Flyway.configure()
                .dataSource(AdminDbAccounts.urlFor(container, schema), "root", container.password)
                .locations(AdminDbAccounts.MAIN_MIGRATIONS).target(target).load().migrate()
            // 업무 schema 위치(db/migration/admin)에는 V1·V3만 있다(V2·V4는 runtime 권한 위치) — V5 직전 = V3.
            flyway("3")
            AdminDbAccounts.root(container, schema).use { c ->
                c.update(
                    "INSERT INTO admin_approval_request (approval_id, kind, target_actor_id, target_security_version, target_snapshot, requested_by, " +
                        "requested_by_display_name, requested_at, expires_at, status, version) " +
                        "VALUES ('apr_00000000000000000000000001', 'ROLE_GRANT', 'adm_target', 4, '{}', 'adm_requester', 'R', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'PENDING', 0)",
                )
            }
            flyway("5")
            val upgraded = AdminDbAccounts.root(container, schema).use { c ->
                listOf(
                    c.column(
                        "SELECT CONCAT(target_kind, ':', target_actor_id, ':', COALESCE(target_ref, '-'), ':', COALESCE(unresolved_key, '-'), ':', pending_key) " +
                            "FROM admin_approval_request",
                    ).joinToString(),
                    c.column(
                        "SELECT COALESCE(column_default, 'NONE') FROM information_schema.columns WHERE table_schema = ? AND table_name = 'admin_approval_request' " +
                            "AND column_name = 'target_kind'",
                        schema,
                    ).joinToString(),
                )
            }
            root().use { it.update("DROP DATABASE $schema") }
            upgraded shouldBe listOf("ADMIN_ACCOUNT:adm_target:-:-:ROLE_GRANT:adm_target", "NONE")
        }

        Then("V5: the audit table has a nullable target_ref (Payment target of the new kind's audit rows — §4.10)") {
            root().use { c ->
                c.column(
                    "SELECT CONCAT(column_type, ':', is_nullable) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'admin_audit_event' " +
                        "AND column_name = 'target_ref'",
                    ADMIN_DATABASE,
                )
            } shouldBe listOf("varchar(64):YES")
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
