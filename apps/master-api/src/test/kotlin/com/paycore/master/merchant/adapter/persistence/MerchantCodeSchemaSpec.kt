package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * MERCHANT-CODE-001 MC-1-4(§3.2 DB 증명 — runtime 계정, 운영과 같은 표 단위 권한) + MC-1-5 스키마 부분(FK 동작·근거 operation CHECK):
 * 배정 표 UPDATE·DELETE 1142, seq 3 → 3819, seq 1에 replaces_code → 3819, 같은 코드 → 1062(은퇴·현재 구별 없음), 다른 가맹점 코드를 replaces_code로 → 1452,
 * `merchant` 권한은 V2 그대로(SELECT, INSERT, UPDATE), 배정 표는 SELECT, INSERT만. 오류 코드: 1062 중복, 1142 표 명령 권한 없음, 1452 FK, 3819 CHECK.
 */
@MasterDbTest
class MerchantCodeSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use(block)

    val insert = "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, '2026-10-09 00:00:00.000', 'schema-test')"

    fun Connection.seq1(merchantId: String, code: String): Int? = errorCodeOfUpdate(insert, merchantId, 1, code, null, "REGISTRATION", null, null)
    fun Connection.change(merchantId: String, code: String, replaces: String, approvalId: String = MasterSchemaRows.id("apr")): Int? =
        errorCodeOfUpdate(insert, merchantId, 2, code, replaces, "CHANGE", approvalId, "TYPO_CORRECTION")
    fun code() = "s" + MasterSchemaRows.id("x").substring(2).lowercase().take(13)

    Given("MC-1-4 the merchant_code_assignment table through the runtime account") {
        Then("MC-1-4 UPDATE, DELETE and locking reads are denied (1142) — append-only") {
            runtime { c ->
                listOf(
                    c.errorCodeOf("UPDATE merchant_code_assignment SET merchant_code = merchant_code WHERE 1 = 0"),
                    c.errorCodeOf("DELETE FROM merchant_code_assignment WHERE 1 = 0"),
                    c.errorCodeOf("SELECT * FROM merchant_code_assignment WHERE 1 = 0 FOR UPDATE"),
                )
            } shouldBe listOf(1142, 1142, 1142)
        }

        Then("MC-1-4 seq 3 and a seq-1 row with change fields are rejected by CHECK (3819); control: a well-formed seq 1 and seq 2 are accepted") {
            runtime { c ->
                val merchant = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false)
                val first = code()
                val second = code()
                mapOf(
                    "seq 1 with replaces_code" to c.errorCodeOfUpdate(insert, merchant, 1, first, code(), "REGISTRATION", null, null),
                    "seq 1 from CHANGE" to c.errorCodeOfUpdate(insert, merchant, 1, first, null, "CHANGE", null, null),
                    "control seq 1" to c.seq1(merchant, first),
                    "seq 2 equal to what it replaces" to c.change(merchant, first, first),
                    "seq 2 without approval" to c.errorCodeOfUpdate(insert, merchant, 2, second, first, "CHANGE", null, "TYPO_CORRECTION"),
                    "control seq 2" to c.change(merchant, second, first),
                    "seq 3" to c.errorCodeOfUpdate(insert, merchant, 3, code(), second, "CHANGE", MasterSchemaRows.id("apr"), "TYPO_CORRECTION"),
                    "bad format" to c.errorCodeOfUpdate(insert, MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false), 1, "Shop-1234", null, "REGISTRATION", null, null),
                )
            } shouldBe mapOf(
                "seq 1 with replaces_code" to 3819, "seq 1 from CHANGE" to 3819, "control seq 1" to null, "seq 2 equal to what it replaces" to 3819,
                "seq 2 without approval" to 3819, "control seq 2" to null, "seq 3" to 3819, "bad format" to 3819,
            )
        }

        Then("MC-1-4 a code already assigned to any merchant (current or retired) is a duplicate (1062); a second change of the same merchant is a PK duplicate (1062)") {
            runtime { c ->
                val a = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false)
                val b = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false)
                val aFirst = code()
                val aSecond = code()
                c.seq1(a, aFirst) shouldBe null
                c.change(a, aSecond, aFirst) shouldBe null
                mapOf(
                    "current code of another merchant" to c.seq1(b, aSecond),
                    "retired code of another merchant" to c.seq1(b, aFirst),
                    "second change" to c.change(a, code(), aFirst),
                )
            } shouldBe mapOf("current code of another merchant" to 1062, "retired code of another merchant" to 1062, "second change" to 1062)
        }

        Then("MC-1-4 a change whose replaced code belongs to another merchant is rejected by the self FK (1452); the same approval id twice is a duplicate (1062)") {
            runtime { c ->
                val a = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false)
                val b = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c), withCode = false)
                val aCode = code()
                val bCode = code()
                c.seq1(a, aCode) shouldBe null
                c.seq1(b, bCode) shouldBe null
                val shared = MasterSchemaRows.id("apr")
                mapOf(
                    "replaces another merchant's code" to c.change(b, code(), aCode),
                    "control own change" to c.change(b, code(), bCode, shared),
                    "same approval id" to c.change(a, code(), aCode, shared),
                    "unknown merchant" to c.seq1(MasterSchemaRows.id("mer"), code()),
                )
            } shouldBe mapOf("replaces another merchant's code" to 1452, "control own change" to null, "same approval id" to 1062, "unknown merchant" to 1452)
        }

        Then("MC-1-4 runtime privileges: merchant_code_assignment = SELECT, INSERT; merchant unchanged from V2 (SELECT, INSERT, UPDATE)") {
            root { c ->
                listOf("merchant_code_assignment", "merchant").associateWith { table ->
                    c.scalar(
                        "SELECT Table_priv FROM mysql.tables_priv WHERE Db = ? AND User = ? AND Table_name = ?",
                        MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_ROLE, table,
                    ).toString().split(',').map { it.trim() }.toSet()
                }
            } shouldBe mapOf("merchant_code_assignment" to setOf("Select", "Insert"), "merchant" to setOf("Select", "Insert", "Update"))
        }
    }

    Given("MC-1-5 schema facts of V13") {
        Then("MC-1-5 both FKs declare no ON DELETE / ON UPDATE action (NO ACTION) and the code UNIQUE covers merchant_code alone") {
            root { c ->
                c.column(
                    "SELECT CONCAT(constraint_name, ':', delete_rule, ':', update_rule) FROM information_schema.referential_constraints " +
                        "WHERE constraint_schema = 'paycore_master' AND table_name = 'merchant_code_assignment' ORDER BY constraint_name",
                ) to c.column(
                    "SELECT CONCAT(index_name, ':', non_unique, ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics " +
                        "WHERE table_schema = 'paycore_master' AND table_name = 'merchant_code_assignment' GROUP BY index_name, non_unique ORDER BY index_name",
                ).toSet()
            } shouldBe (
                listOf("fk_mca_merchant:NO ACTION:NO ACTION", "fk_mca_replaces:NO ACTION:NO ACTION") to
                    setOf("PRIMARY:0:merchant_id,assignment_seq", "fk_mca_replaces:1:merchant_id,replaces_code", "uk_mca_approval:0:approval_id", "uk_mca_code:0:merchant_code", "uk_mca_merchant_code:0:merchant_id,merchant_code")
                )
        }

        Then("MC-1-5 the evidence operation CHECK accepts ChangeMerchantCode and keeps every earlier operation; an unknown operation is 3819") {
            fun evidence(c: Connection, operation: String): Int? = c.errorCodeOfUpdate(
                "INSERT INTO admin_approval_evidence (approval_id, operation, request_record_id, requested_by, approved_by, approved_at, execute_before, evidence_ref, " +
                    "approval_fingerprint, outcome, result_code, result_reference, decided_via, recorded_at) VALUES (?, ?, ?, 'admin:adm_01JC0000000000000000000RQ1', " +
                    "'admin:adm_01JC0000000000000000000AP1', '2026-10-09 01:59:00.000', '2026-10-09 02:59:00.000', ?, ?, 'REJECTED', 'APPROVAL_EXPIRED', NULL, 'EXECUTE', " +
                    "'2026-10-09 02:00:00.000')",
                MasterSchemaRows.id("apr"), operation, java.util.UUID.randomUUID().toString(), com.paycore.master.merchant.application.ApprovedCommandFixture.SCHEMA_TEST_EVIDENCE_REF,
                "mop1:" + "cd".repeat(32),
            )
            runtime { c ->
                listOf(
                    "ApproveContractRevision", "IssueFirstDefaultMerchantAccount", "ApprovePaymentMethodActivation", "DecideSiteReview", "EnableCurrency",
                    "ApproveCardRouteActivation", "ResumePaymentMethodActivation", "ResumeCardRouteActivation", "ConfirmSiteActivation", "ChangeMerchantCode", "RegisterMerchant",
                ).associateWith { evidence(c, it) }
            } shouldBe mapOf(
                "ApproveContractRevision" to null, "IssueFirstDefaultMerchantAccount" to null, "ApprovePaymentMethodActivation" to null, "DecideSiteReview" to null,
                "EnableCurrency" to null, "ApproveCardRouteActivation" to null, "ResumePaymentMethodActivation" to null, "ResumeCardRouteActivation" to null,
                "ConfirmSiteActivation" to null, "ChangeMerchantCode" to null, "RegisterMerchant" to 3819,
            )
        }
    }
})
