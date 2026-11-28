package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.errorCodeOfUpdate
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * BIZ-CRIT-APPROVAL-001 S2 V26(§3.1 — 실제 MySQL 8.4, runtime 계정): 근거 operation CHECK에 `ApplyBusinessCriticalChange`만 더해졌고(제안은 1인 명령이라 근거 행 없음 — 넣지 않음),
 * 제안 표 형식 CHECK(ID·digest·base version·요청자)·FK, 1인 근거 표 FK(이력 행·사업자). 위반은 3819·1452. 기대값은 명세 리터럴.
 */
@MasterDbTest
class BusinessCriticalChangeSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T = MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun evidence(c: Connection, operation: String): Int? = c.errorCodeOfUpdate(
        "INSERT INTO admin_approval_evidence (approval_id, operation, request_record_id, requested_by, approved_by, approved_at, execute_before, evidence_ref, " +
            "approval_fingerprint, outcome, result_code, result_reference, decided_via, recorded_at) VALUES (?, ?, ?, 'admin:adm_01JC0000000000000000000RQ1', " +
            "'admin:adm_01JC0000000000000000000AP1', '2026-10-09 01:59:00.000', '2026-10-09 02:59:00.000', ?, ?, 'REJECTED', 'APPROVAL_EXPIRED', NULL, 'EXECUTE', " +
            "'2026-10-09 02:00:00.000')",
        MasterSchemaRows.id("apr"), operation, java.util.UUID.randomUUID().toString(), com.paycore.master.merchant.application.ApprovedCommandFixture.SCHEMA_TEST_EVIDENCE_REF,
        "mop1:" + "cd".repeat(32),
    )

    @Suppress("LongParameterList")
    fun proposal(c: Connection, businessId: String, id: String = MasterSchemaRows.id("bcp"), digest: String = "h1:" + "ab".repeat(32), baseVersion: Long = 0,
        requestedBy: String = "admin:adm_01JC0000000000000000000RQ1"): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_change_proposal (proposal_id, business_id, base_version, content, content_digest, changes, important_fields, representative_changed, " +
            "subject_correction, reason, evidence_ref, requested_by, requested_at, request_record_id) " +
            "VALUES (?, ?, ?, JSON_OBJECT(), ?, JSON_ARRAY(), JSON_ARRAY('displayName'), 0, 0, '사유', 'DOC-1', ?, '2026-10-09 02:00:00.000', ?)",
        id, businessId, baseVersion, digest, requestedBy, java.util.UUID.randomUUID().toString(),
    )

    Given("V26 business critical change tables") {
        Then("the evidence operation CHECK accepts ApplyBusinessCriticalChange (and keeps ChangeMerchantCode); ProposeBusinessCriticalChange is 3819") {
            runtime { c -> listOf("ApplyBusinessCriticalChange", "ChangeMerchantCode", "ProposeBusinessCriticalChange").associateWith { evidence(c, it) } } shouldBe
                mapOf("ApplyBusinessCriticalChange" to null, "ChangeMerchantCode" to null, "ProposeBusinessCriticalChange" to 3819)
        }

        Then("proposal rows: valid insert; bad id / digest / negative base version / non-canonical requester are 3819; unknown business is 1452") {
            runtime { c ->
                val business = MasterSchemaRows.business(c)
                listOf(
                    proposal(c, business),
                    proposal(c, business, id = "bcp_short"),
                    proposal(c, business, digest = "m1:" + "ab".repeat(32)),
                    proposal(c, business, baseVersion = -1),
                    proposal(c, business, requestedBy = "operator-1"),
                    proposal(c, MasterSchemaRows.id("biz")),
                )
            } shouldBe listOf(null, 3819, 3819, 3819, 3819, 1452)
        }

        Then("1-person evidence rows reference an existing history row and business (1452 otherwise)") {
            runtime { c ->
                val business = MasterSchemaRows.business(c)
                c.errorCodeOfUpdate(
                    "INSERT INTO business_change_evidence (change_history_id, business_id, admin_request_id, evidence_ref, recorded_by, recorded_at) " +
                        "VALUES (?, ?, 'r-1', 'DOC-1', 'admin:x', '2026-10-09 02:00:00.000')",
                    Long.MAX_VALUE, business,
                )
            } shouldBe 1452
        }
    }
})
