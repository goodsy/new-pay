package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.ACTOR
import com.paycore.master.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * MASTER-SNAPSHOT-002 V5 제약을 실제 MySQL 8.4.11에서 **runtime 계정**으로 확인한다(domain 검사와 별개의 이중 방어). 오류 코드: 1062 중복,
 * 1452 FK 위반, 3819 CHECK 위반, 1142 권한 없음. 값은 Demo.
 */
@MasterDbTest
class AuthorizationTermsSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    // 2026-10-09T15:00Z = 2026-10-10 00:00 KST(Seoul 자정). created_at은 그보다 앞선다.
    val insertLimit =
        "INSERT INTO transaction_limit_policy (policy_id, merchant_id, scope_kind, contract_id, agreement_id, merchant_account_id, currency_code, per_tx_kind, " +
            "per_tx_amount, daily_kind, daily_amount, effective_from, effective_to, reason, created_at, created_by, request_record_id, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'KRW', ?, ?, ?, ?, ?, ?, 'demo', '2026-10-09 01:00:00.000', ?, 'r-1', 0)"

    fun limit(
        c: Connection,
        g: MasterSchemaRows.Graph,
        scope: String = "CSA_DEFAULT",
        contract: String? = g.contractId,
        agreement: String? = g.agreementId,
        mid: String? = null,
        perTx: Pair<String, Long?> = "LIMITED" to 1_000_000L,
        daily: Pair<String, Long?> = "UNLIMITED" to null,
        from: String = "2026-10-09 15:00:00.000",
        to: String? = null,
    ): Int? = c.errorCodeOfUpdate(
        insertLimit, MasterSchemaRows.id("tlp"), g.merchantId, scope, contract, agreement, mid, perTx.first, perTx.second, daily.first, daily.second, from, to, ACTOR,
    )

    fun version(c: Connection, kind: String = "FEE_SCHEDULE", code: String = "FEE-" + MasterSchemaRows.id("x").takeLast(6), no: Int = 1): String =
        MasterSchemaRows.id("pv").also {
            c.update(
                "INSERT INTO policy_version (policy_version_id, policy_kind, policy_code, version_no, content_status, reason, registered_at, registered_by, " +
                    "request_record_id) VALUES (?, ?, ?, ?, 'DEMO', 'demo', '2026-10-09 01:00:00.000', ?, 'r-1')",
                it, kind, code, no, ACTOR,
            )
        }

    fun assignment(c: Connection, g: MasterSchemaRows.Graph, versionId: String, kind: String = "FEE_SCHEDULE", agreement: String = g.agreementId): Int? = c.errorCodeOfUpdate(
        "INSERT INTO policy_assignment (assignment_id, merchant_id, agreement_id, merchant_account_id, currency_code, policy_kind, policy_version_id, " +
            "effective_from, reason, created_at, created_by, request_record_id, version) VALUES (?, ?, ?, ?, 'KRW', ?, ?, '2026-10-09 01:00:00.000', 'demo', " +
            "'2026-10-09 01:00:00.000', ?, 'r-1', 0)",
        MasterSchemaRows.id("pas"), g.merchantId, agreement, g.accountId, kind, versionId, ACTOR,
    )

    fun route(
        c: Connection,
        g: MasterSchemaRows.Graph,
        status: String = "UNDER_REVIEW",
        evidence: String? = null,
        acquirer: String = "SIMCARD01",
        routeRef: String = "SIMVAN01",
    ): Int? = c.errorCodeOfUpdate(
        "INSERT INTO card_route_activation (activation_id, merchant_account_id, merchant_id, payment_method, acquirer_code, route_ref, institution_merchant_ref, " +
            "status, evidence_ref, registered_at, registered_by, status_changed_at, request_record_id, version) VALUES (?, ?, ?, 'CARD', ?, ?, " +
            "'SIMMERCH0001', ?, ?, '2026-10-09 01:00:00.000', ?, '2026-10-09 01:00:00.000', 'r-1', 0)",
        MasterSchemaRows.id("cra"), g.accountId, g.merchantId, acquirer, routeRef, status, evidence, ACTOR,
    )

    Given("transaction limit policies (D-AUTH-006, D-MS2-001·002)") {
        Then("a CSA default from a Seoul midnight with explicit values is accepted; a second open row of the same key is 1062 (same-level overlap)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                listOf(limit(c, g), limit(c, g), limit(c, g, scope = "MID_OVERRIDE", contract = null, agreement = null, mid = g.accountId))
            } shouldBe listOf(null, 1062, null)
        }

        Then("CHECKs: boundaries off Seoul midnight, inconsistent scope columns, limited without amount, unlimited with amount, negative amount (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                listOf(
                    limit(c, g, from = "2026-10-09 16:00:00.000"),
                    limit(c, g, to = "2026-10-11 00:00:00.000"),
                    limit(c, g, mid = g.accountId),
                    limit(c, g, perTx = "LIMITED" to null),
                    limit(c, g, daily = "UNLIMITED" to 5L),
                    limit(c, g, perTx = "LIMITED" to -1L),
                    limit(c, g, from = "2026-10-08 15:00:00.000"),
                )
            } shouldBe List(7) { 3819 }
        }

        Then("a CSA of another contract or a MID of another merchant is a FK violation (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val other = MasterSchemaRows.graph(c)
                listOf(
                    limit(c, g, agreement = other.agreementId),
                    limit(c, g, scope = "MID_OVERRIDE", contract = null, agreement = null, mid = other.accountId),
                )
            } shouldBe listOf(1452, 1452)
        }

        Then("the runtime account may end a policy but the table has no DELETE (1142)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                limit(c, g)
                listOf(
                    c.errorCodeOfUpdate("UPDATE transaction_limit_policy SET effective_to = '2026-10-10 15:00:00.000', ended_at = '2026-10-09 02:00:00.000', ended_by = ?, version = 1 WHERE merchant_id = ?", ACTOR, g.merchantId),
                    c.errorCodeOfUpdate("DELETE FROM transaction_limit_policy WHERE merchant_id = ?", g.merchantId),
                )
            } shouldBe listOf(null, 1142)
        }
    }

    Given("policy versions and assignments (D-MS2-003, D-MD-018)") {
        Then("(kind, code, number) is unique; an assignment must use the MID's issuing CSA and a version of the same kind (1452); one open row per (MID, currency, kind)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val fee = version(c, code = "FEE-SCHEMA1")
                val other = MasterSchemaRows.graph(c)
                listOf(
                    c.errorCodeOfUpdate(
                        "INSERT INTO policy_version (policy_version_id, policy_kind, policy_code, version_no, content_status, reason, registered_at, registered_by, " +
                            "request_record_id) VALUES (?, 'FEE_SCHEDULE', 'FEE-SCHEMA1', 1, 'DEMO', 'demo', '2026-10-09 01:00:00.000', ?, 'r-1')",
                        MasterSchemaRows.id("pv"), ACTOR,
                    ),
                    assignment(c, g, fee, agreement = other.agreementId),
                    assignment(c, g, fee, kind = "SETTLEMENT_POLICY"),
                    assignment(c, g, fee),
                    assignment(c, g, fee),
                    c.errorCodeOfUpdate("UPDATE policy_version SET reason = 'x' WHERE policy_version_id = ?", fee),
                )
            } shouldBe listOf(1062, 1452, 1452, null, 1062, 1142)
        }
    }

    Given("card route activations (D-MS2-004)") {
        Then("one non-terminated route per (MID, method, acquirer) (1062); ACTIVE needs evidence and the acquirer code format is enforced (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                listOf(
                    route(c, g),
                    route(c, g),
                    route(c, g, acquirer = "OTHERCARD"),
                    route(c, g, status = "ACTIVE", acquirer = "THIRD"),
                    route(c, g, acquirer = "lower"),
                )
            } shouldBe listOf(null, 1062, null, 3819, 3819)
        }

        // INST-CODE-001 IC-2·IC-3(D-INST-CODE-001, V7): 매입사·Route 코드 CHECK = ^[A-Z0-9]{1,32}$. Simulator 인증 결과 golden의 매입사는 그대로 등록된다.
        Then("acquirer and route codes with a hyphen, an underscore or lower case are 3819; the Simulator authentication result acquirer is accepted") {
            val simulatorAcquirer = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(org.springframework.core.io.ClassPathResource("contracts/authentication-contract-v1.golden.json").inputStream)
                .get("scenarios").get("authenticated").get("lookup").get("acquirerCode").stringValue()
            runtime { c ->
                listOf(
                    route(c, MasterSchemaRows.graph(c), acquirer = "SIMACQ-A"),
                    route(c, MasterSchemaRows.graph(c), acquirer = "SIM_ACQ_A"),
                    route(c, MasterSchemaRows.graph(c), routeRef = "SIM-VAN-01"),
                    route(c, MasterSchemaRows.graph(c), routeRef = "SIM_VAN_01"),
                    route(c, MasterSchemaRows.graph(c), routeRef = "simvan01"),
                    route(c, MasterSchemaRows.graph(c), routeRef = "SIMVAN.01"),
                    route(c, MasterSchemaRows.graph(c), routeRef = "R".repeat(33)),
                    route(c, MasterSchemaRows.graph(c), acquirer = simulatorAcquirer, routeRef = "SIMVAN01"),
                    route(c, MasterSchemaRows.graph(c), acquirer = "A".repeat(32), routeRef = "R".repeat(32)),
                )
            } shouldBe listOf(3819, 3819, 3819, 3819, 3819, 3819, 3819, null, null)
        }
    }

    Given("the snapshot issue record (§5.2)") {
        Then("rows written before this migration are schemaVersion 1 and the column has no default afterwards") {
            MasterDbAccounts.root(container).use { c ->
                c.prepareStatement(
                    "SELECT COLUMN_DEFAULT IS NULL, IS_NULLABLE FROM information_schema.columns WHERE table_schema = 'paycore_master' " +
                        "AND table_name = 'entitlement_snapshot_issue' AND column_name = 'schema_version'",
                ).use { ps -> ps.executeQuery().use { rs -> rs.next(); listOf(rs.getBoolean(1), rs.getString(2)) } }
            } shouldBe listOf(true, "NO")
        }
    }
})
