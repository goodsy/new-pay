package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.ACTOR
import com.paycore.master.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * MASTER-SNAPSHOT-001 V3 제약을 실제 MySQL 8.4.11에서 **runtime 계정**으로 확인한다(domain 검사와 별개의 이중 방어).
 * 오류 코드: 1062 중복, 1452 FK 위반, 3819 CHECK 위반.
 */
@MasterDbTest
class EntitlementSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun activation(c: Connection, accountId: String, status: String = "UNDER_REVIEW", evidence: String? = null): String =
        MasterSchemaRows.id("act").also {
            c.update(
                "INSERT INTO payment_method_activation (activation_id, merchant_account_id, payment_method, registered_at, registered_by, status, " +
                    "evidence_ref, status_changed_at, version) VALUES (?, ?, 'CARD', '2026-10-09 00:00:00.000', ?, ?, ?, '2026-10-09 00:00:00.000', 0)",
                it, accountId, ACTOR, status, evidence,
            )
        }

    val insertConfirmation =
        "INSERT INTO site_activation_confirmation (confirmation_id, merchant_account_id, merchant_site_id, merchant_id, payment_method, activation_id, " +
            "evidence_ref, confirmed_at, confirmed_by, request_record_id, confirmation_seq) VALUES (?, ?, ?, ?, 'CARD', ?, 'ev-1', '2026-10-09 00:00:00.000', ?, 'r-1', 1)"

    Given("payment method activations (D-MS-003)") {
        Then("one non-terminated activation per (MID, method): a second open row is 1062, after TERMINATED a new row is accepted") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val first = activation(c, g.accountId)
                val duplicate = c.errorCodeOfUpdate(
                    "INSERT INTO payment_method_activation (activation_id, merchant_account_id, payment_method, registered_at, registered_by, status, " +
                        "status_changed_at, version) VALUES (?, ?, 'CARD', '2026-10-09 00:00:00.000', ?, 'UNDER_REVIEW', '2026-10-09 00:00:00.000', 0)",
                    MasterSchemaRows.id("act"), g.accountId, ACTOR,
                )
                c.update("UPDATE payment_method_activation SET status = 'TERMINATED', version = 1 WHERE activation_id = ?", first)
                activation(c, g.accountId)
                duplicate
            } shouldBe 1062
        }

        Then("ACTIVE without institution evidence and an unknown status are rejected by CHECK (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                listOf(
                    runCatching { activation(c, g.accountId, status = "ACTIVE") }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                    runCatching { activation(c, g.accountId, status = "PAUSED") }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                )
            } shouldBe listOf(3819, 3819)
        }
    }

    Given("site activation confirmations (D-MD-016)") {
        Then("the activation must be of the same MID (composite FK) and the site of the same merchant (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val other = MasterSchemaRows.graph(c)
                val mine = activation(c, g.accountId)
                val foreign = activation(c, other.accountId)
                mapOf(
                    "own" to c.errorCodeOfUpdate(insertConfirmation, MasterSchemaRows.id("sac"), g.accountId, g.siteId, g.merchantId, mine, ACTOR),
                    "other MID activation" to c.errorCodeOfUpdate(
                        insertConfirmation, MasterSchemaRows.id("sac"), g.accountId, g.siteId, g.merchantId, foreign, ACTOR,
                    ),
                    "other merchant site" to c.errorCodeOfUpdate(
                        insertConfirmation, MasterSchemaRows.id("sac"), g.accountId, other.siteId, g.merchantId, mine, ACTOR,
                    ),
                    // review M-1: 같은 (Activation, Site)의 같은 확인 순번은 하나(유효 확인 둘을 DB가 막는다).
                    "same activation-site sequence" to c.errorCodeOfUpdate(
                        insertConfirmation, MasterSchemaRows.id("sac"), g.accountId, g.siteId, g.merchantId, mine, ACTOR,
                    ),
                )
            } shouldBe mapOf("own" to null, "other MID activation" to 1452, "other merchant site" to 1452, "same activation-site sequence" to 1062)
        }
    }

    Given("currency units and enablements (D-MD-018)") {
        Then("the reference units are KRW 0 and USD 2 only (ISO facts, no MID is enabled by the migration)") {
            runtime { c -> c.column("SELECT CONCAT(currency_code, '=', minor_unit) FROM currency_unit ORDER BY currency_code") } shouldBe
                listOf("KRW=0", "USD=2")
        }

        Then("one open record per (MID, currency) (1062), retroactive start and unknown currency rejected (3819 / 1452)") {
            val insert =
                "INSERT INTO currency_enablement (enablement_id, merchant_account_id, currency_code, effective_from, effective_to, evidence_ref, " +
                    "created_at, created_by, request_record_id, version) VALUES (?, ?, ?, ?, NULL, 'ev-1', '2026-10-09 00:00:00.000', ?, 'r-1', 0)"
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                mapOf(
                    "first" to c.errorCodeOfUpdate(insert, MasterSchemaRows.id("cur"), g.accountId, "KRW", "2026-10-09 00:00:00.000", ACTOR),
                    "second open" to c.errorCodeOfUpdate(insert, MasterSchemaRows.id("cur"), g.accountId, "KRW", "2026-10-10 00:00:00.000", ACTOR),
                    "retroactive" to c.errorCodeOfUpdate(insert, MasterSchemaRows.id("cur"), g.accountId, "USD", "2026-10-08 00:00:00.000", ACTOR),
                    "unknown" to c.errorCodeOfUpdate(insert, MasterSchemaRows.id("cur"), g.accountId, "EUR", "2026-10-09 00:00:00.000", ACTOR),
                )
            } shouldBe mapOf("first" to null, "second open" to 1062, "retroactive" to 3819, "unknown" to 1452)
        }
    }

    Given("snapshot issues, ACKs, site review decisions and the outbox relay columns") {
        Then("issue content hash format, lease order and ACK outcome are checked; an ACK must reference an issued event (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val issue =
                    // MASTER-SNAPSHOT-002 V5: schema_version은 기본값 없이 명시한다(발행 기록이 자기 Snapshot 버전을 남긴다).
                    "INSERT INTO entitlement_snapshot_issue (merchant_id, revision, lease_seq, content_hash, issued_at, lease_until, priority, outbox_event_id, schema_version) " +
                        "VALUES (?, ?, 0, ?, '2026-10-09 00:00:00.000', ?, 'HIGH', ?, 2)"
                val hash = "ec1:" + "a".repeat(64)
                val event = java.util.UUID.randomUUID().toString()
                mapOf(
                    "ok" to c.errorCodeOfUpdate(issue, g.merchantId, 1, hash, "2026-10-09 00:00:02.000", event),
                    "bad hash" to c.errorCodeOfUpdate(issue, g.merchantId, 2, "sha:x", "2026-10-09 00:00:02.000", java.util.UUID.randomUUID().toString()),
                    "lease not after issue" to c.errorCodeOfUpdate(issue, g.merchantId, 3, hash, "2026-10-09 00:00:00.000", java.util.UUID.randomUUID().toString()),
                    "ack ok" to c.errorCodeOfUpdate(
                        "INSERT INTO entitlement_snapshot_ack (outbox_event_id, consumer, outcome, applied_revision, acked_at) VALUES (?, 'payment-api', 'APPLIED', 1, NOW(3))",
                        event,
                    ),
                    "ack unknown event" to c.errorCodeOfUpdate(
                        "INSERT INTO entitlement_snapshot_ack (outbox_event_id, consumer, outcome, applied_revision, acked_at) VALUES (?, 'payment-api', 'APPLIED', 1, NOW(3))",
                        java.util.UUID.randomUUID().toString(),
                    ),
                    "ack bad outcome" to c.errorCodeOfUpdate(
                        "INSERT INTO entitlement_snapshot_ack (outbox_event_id, consumer, outcome, applied_revision, acked_at) VALUES (?, 'payment-api', 'OK', 1, NOW(3))",
                        event,
                    ),
                )
            } shouldBe mapOf(
                "ok" to null, "bad hash" to 3819, "lease not after issue" to 3819, "ack ok" to null, "ack unknown event" to 1452, "ack bad outcome" to 3819,
            )
        }

        Then("a review decision value PENDING is rejected (initial state, not a decision) and the site must belong to the merchant") {
            val insert =
                "INSERT INTO merchant_site_review_decision (merchant_site_id, seq, merchant_id, decision, reason, decided_at, decided_by, request_record_id) " +
                    "VALUES (?, 1, ?, ?, 'r', '2026-10-09 00:00:00.000', ?, 'r-1')"
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val other = MasterSchemaRows.graph(c)
                mapOf(
                    "pending" to c.errorCodeOfUpdate(insert, g.siteId, g.merchantId, "PENDING", ACTOR),
                    "other merchant" to c.errorCodeOfUpdate(insert, g.siteId, other.merchantId, "APPROVED", ACTOR),
                    "approved" to c.errorCodeOfUpdate(insert, g.siteId, g.merchantId, "APPROVED", ACTOR),
                )
            } shouldBe mapOf("pending" to 3819, "other merchant" to 1452, "approved" to null)
        }

        Then("the outbox accepts QUARANTINED only without published_at and keeps claim owner and claim expiry together (3819)") {
            runtime { c ->
                val insert =
                    "INSERT INTO outbox_event (event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, " +
                        "aggregate_version, occurred_at, correlation_id, causation_id, payload, publish_status, published_at, created_at, next_attempt_at, " +
                        "claim_owner, claim_until) VALUES (?, ?, 'T', 1, 'TEST_CATEGORY', 'test', 'X', 'x', 0, NOW(3), 'c', 'c', '{}', ?, ?, NOW(3), NOW(3), ?, ?)"
                fun attempt(status: String, publishedAt: String?, owner: String?, until: String?) = java.util.UUID.randomUUID().toString().let {
                    c.errorCodeOfUpdate(insert, it, "k-$it", status, publishedAt, owner, until)
                }
                mapOf(
                    "quarantined" to attempt("QUARANTINED", null, null, null),
                    "quarantined published" to attempt("QUARANTINED", "2026-10-09 00:00:00.000", null, null),
                    "claim without expiry" to attempt("PENDING", null, "relay-1", null),
                )
            } shouldBe mapOf("quarantined" to null, "quarantined published" to 3819, "claim without expiry" to 3819)
        }
    }
})
