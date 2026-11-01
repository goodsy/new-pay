package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_PAYMENT
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.paymentArgs
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.sql.Connection

/**
 * AUTHN-001 V7·V8_1 DB 제약(명세 §10.1)을 실제 MySQL 8.4에서 검사한다. 선언(information_schema, root)과 행위(INSERT, runtime 계정)를 함께 본다.
 * 오류 코드: 1062 = 중복 키(생성 컬럼 UNIQUE 포함), 3819 = CHECK 위반. 값은 Demo(digest는 형식만 맞춘 시험 값 — 원문 비밀 아님).
 */
@PaymentDbTest
class CheckoutSchemaSpec(private val db: DbAccess) : BehaviorSpec({

    val tables = listOf("payment_checkout_window_pin", "checkout_token", "checkout_session", "authentication_session", "authentication_session_event")
    val hex = "a".repeat(64)
    fun digest(prefix: String, n: Int) = prefix + n.toString().padStart(4, '0') + hex.drop(4)

    fun Connection.code(sql: String, vararg args: Any?): Int? = with(db) { errorCodeOf(sql, *args) }
    fun Connection.exec(sql: String, vararg args: Any?) = with(db) { update(sql, *args) }

    /** 시험 Payment + 토큰 + 결제창 세션 하나(부모 행). */
    data class Parents(val paymentId: String, val tokenId: String, val sessionId: String)

    var seq = 0
    fun next() = ++seq

    fun Connection.payment(): String {
        val id = unique("pay")
        exec(INSERT_PAYMENT, *paymentArgs(paymentId = id, mid = unique("mid"), orderId = unique("order")))
        return id
    }

    fun tokenSql(status: String = "ISSUED", consumed: Boolean = false, closed: Boolean = false, url: String = "https://shop.example.test/ok", d: String? = null) =
        Pair(
            """INSERT INTO checkout_token (checkout_token_id, token_digest, payment_id, environment, merchant_account_id, merchant_order_id, merchant_site_id,
               amount_minor, currency, success_url, fail_url, site_host, site_snapshot_version, status, issued_at, expires_at, consumed_at, closed_at,
               issued_by_credential_id, policy_version, version)
               VALUES (?, ?, ?, 'TEST', 'mid-x', 'order-x', 'demo-site-001', 10000, 'KRW', ?, 'https://shop.example.test/fail', 'shop.example.test', 'es1:m:1',
               ?, '2026-10-07 00:00:00.000', '2026-10-07 00:05:00.000', ?, ?, 'demoActor0001', 'checkout-demo-1', 0)""",
            listOf(url, status, if (consumed) "2026-10-07 00:01:00.000" else null, if (closed) "2026-10-07 00:01:00.000" else null, d),
        )

    fun Connection.token(paymentId: String, status: String = "ISSUED", consumed: Boolean = false, closed: Boolean = false, url: String = "https://shop.example.test/ok", digestValue: String? = null): Pair<String, Int?> {
        val id = "ctk_" + unique("t").replace("-", "")
        val (sql, a) = tokenSql(status, consumed, closed, url)
        return id to code(sql, id, digestValue ?: digest("cth1:", next()), paymentId, a[0], a[1], a[2], a[3])
    }

    fun Connection.session(paymentId: String, tokenId: String, status: String = "ACTIVE", reason: String? = null, handle: String? = null): Pair<String, Int?> {
        val id = "cks_" + unique("s").replace("-", "")
        val h = handle ?: unique("h").replace("-", "").padEnd(22, 'x').take(22)
        return id to code(
            """INSERT INTO checkout_session (checkout_session_id, handle, session_digest, checkout_token_id, payment_id, success_url, fail_url, site_host,
               status, close_reason, created_at, expires_at, closed_at, policy_version, version)
               VALUES (?, ?, ?, ?, ?, 'https://shop.example.test/ok', 'https://shop.example.test/fail', 'shop.example.test', ?, ?, '2026-10-07 00:00:00.000',
               '2026-10-07 00:20:00.000', ?, 'checkout-demo-1', 0)""",
            id, h, digest("cks1:", next()), tokenId, paymentId, status, reason, if (reason != null) "2026-10-07 00:02:00.000" else null,
        )
    }

    fun Connection.parents(): Parents {
        val p = payment()
        val (t, tc) = token(p)
        tc shouldBe null
        val (s, sc) = session(p, t)
        sc shouldBe null
        return Parents(p, t, s)
    }

    /** 인증 세션 행. [evidence] = 증거 컬럼 전부 채움, [rrd] = 결과 참조 digest. */
    fun Connection.authSession(
        parents: Parents,
        status: String,
        providerRef: String? = if (status == "CREATED") null else "sat_demo0001",
        evidence: Boolean = false,
        rrd: Boolean = false,
        failure: String? = null,
        cause: String? = null,
    ): Pair<String, Int?> {
        val id = "aus_" + unique("a").replace("-", "")
        val n = next()
        return id to code(
            """INSERT INTO authentication_session (authentication_session_id, payment_id, checkout_session_id, environment, merchant_account_id, merchant_order_id,
               amount_minor, currency, plan_kind, installment_months, card_company, status, state_digest, provider_transaction_ref, result_ref_digest,
               failure_reason, expiry_cause, evidence_ref, card_type, issuer_code, acquirer_code, provider_code, provider_valid_until, verified_at,
               authentication_ref, bound_authorization_id, profile_id, plan_policy_version, policy_version, created_at, expires_at, version)
               VALUES (?, ?, ?, 'TEST', 'mid-x', 'order-x', 10000, 'KRW', 'LUMP_SUM', NULL, 'SIMISSA', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL,
               'sim-auth-demo', 'plan-demo-1', 'checkout-demo-1', '2026-10-07 00:00:00.000', '2026-10-07 00:10:00.000', 0)""",
            id, parents.paymentId, parents.sessionId, status, digest("std1:", n), providerRef, if (rrd) digest("rrd1:", n) else null, failure, cause,
            *(if (evidence) arrayOf("ev-1", "CREDIT", "SIMISSA", "SIMACQA", "SIMAUTH1", "2026-10-07 00:30:00.000", "2026-10-07 00:01:00.000", "aref_" + n.toString().padStart(22, 'A'))
            else arrayOfNulls<Any?>(8)),
        )
    }

    Given("the V7 and V8_1 migrations are applied") {
        Then("the new tables exist with utf8mb4_0900_bin and every string column uses the NO PAD binary collation") {
            val found = db.withRoot { c ->
                with(db) { c.rows("SELECT TABLE_NAME AS t, TABLE_COLLATION AS coll FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()") }
            }.associate { it["t"] to it["coll"] }
            found.keys shouldContainAll tables
            tables.forEach { found[it] shouldBe "utf8mb4_0900_bin" }
            db.withRoot { c ->
                with(db) {
                    c.rows(
                        "SELECT TABLE_NAME AS t, COLUMN_NAME AS col FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND DATA_TYPE IN ('char','varchar') AND COLLATION_NAME <> 'utf8mb4_0900_bin' AND TABLE_NAME IN (${(tables + "payment").joinToString { "'$it'" }})",
                    )
                }
            }.shouldBeEmpty()
        }
        Then("no PAYMENT column can hold card data or raw secrets (names: card number, PAN, CVC/CVV, expiry month, token/state/cookie raw)") {
            db.withRoot { c ->
                with(db) {
                    c.rows(
                        "SELECT TABLE_NAME AS t, COLUMN_NAME AS col FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND " +
                            "REGEXP_LIKE(COLUMN_NAME, '(^|_)(card_number|pan|cvc|cvv|expiry_month|raw_token|token_value|state_value|cookie_secret)($|_)')",
                    )
                }
            }.shouldBeEmpty()
        }
    }

    Given("the payment checkout window columns (D-AUTHN-005·007)") {
        Then("pairing, ordering and the REQUESTED NOT NULL rule (V8_1) are enforced; a non-REQUESTED legacy row may stay without a window") {
            db.withApp { c ->
                val id = c.payment()
                mapOf(
                    "expiry without version" to c.code("UPDATE payment SET checkout_validity_version = NULL WHERE payment_id = ?", id),
                    "expiry not after creation" to c.code("UPDATE payment SET checkout_expires_at = created_at WHERE payment_id = ?", id),
                    "REQUESTED without a window" to c.code("UPDATE payment SET checkout_expires_at = NULL, checkout_validity_version = NULL WHERE payment_id = ?", id),
                    "AUTHORIZING without a window" to c.code(
                        "UPDATE payment SET status = 'AUTHORIZING', checkout_expires_at = NULL, checkout_validity_version = NULL WHERE payment_id = ?", id,
                    ),
                ) shouldBe mapOf("expiry without version" to 3819, "expiry not after creation" to 3819, "REQUESTED without a window" to 3819, "AUTHORIZING without a window" to null)
            }
        }
        Then("the pin table holds one row with a positive duration") {
            db.withRoot { c ->
                c.code("INSERT INTO payment_checkout_window_pin VALUES (2, 'v', 1000, '2026-10-07 00:00:00.000')") shouldBe 3819
                c.code("INSERT INTO payment_checkout_window_pin VALUES (1, 'v', 0, '2026-10-07 00:00:00.000')") shouldBe 3819
            }
        }
    }

    Given("checkout tokens") {
        Then("one ISSUED token per payment; a superseded one frees the slot; digest, URL and status/time pairing are checked") {
            db.withApp { c ->
                val p = c.payment()
                val (first, ok) = c.token(p)
                ok shouldBe null
                c.token(p).second shouldBe 1062
                c.exec("UPDATE checkout_token SET status = 'SUPERSEDED', closed_at = '2026-10-07 00:01:00.000' WHERE checkout_token_id = ?", first)
                c.token(p).second shouldBe null
                val q = c.payment()
                mapOf(
                    "raw token as digest" to c.token(q, digestValue = "ckt_rawtoken").second,
                    "http url" to c.token(q, url = "http://shop.example.test/ok").second,
                    "CONSUMED without consumed_at" to c.token(q, status = "CONSUMED").second,
                    "SUPERSEDED without closed_at" to c.token(q, status = "SUPERSEDED").second,
                    "unknown status" to c.token(q, status = "USED").second,
                ) shouldBe mapOf("raw token as digest" to 3819, "http url" to 3819, "CONSUMED without consumed_at" to 3819, "SUPERSEDED without closed_at" to 3819, "unknown status" to 3819)
            }
        }
    }

    Given("checkout sessions") {
        Then("one ACTIVE session per payment, one session per token, handle grammar and status/close-reason pairing are checked") {
            db.withApp { c ->
                val p = c.payment()
                val (t1, _) = c.token(p)
                c.session(p, t1).second shouldBe null
                c.session(p, t1).second shouldBe 1062 // 토큰 1개 = 세션 1개
                c.exec("UPDATE checkout_token SET status = 'CONSUMED', consumed_at = '2026-10-07 00:01:00.000' WHERE checkout_token_id = ?", t1)
                val (t2, _) = c.token(p)
                c.session(p, t2).second shouldBe 1062 // ACTIVE는 Payment당 1
                c.session(p, t2, status = "REVOKED", reason = "SUPERSEDED").second shouldBe null
                val q = c.payment()
                val (t3, _) = c.token(q)
                mapOf(
                    "bad handle" to c.session(q, t3, handle = "short").second,
                    "REVOKED with TTL_ELAPSED" to c.session(q, t3, status = "REVOKED", reason = "TTL_ELAPSED").second,
                    "ACTIVE with a reason" to c.session(q, t3, status = "ACTIVE", reason = "CANCELLED").second,
                    "EXPIRED without a reason" to c.session(q, t3, status = "EXPIRED").second,
                ) shouldBe mapOf("bad handle" to 3819, "REVOKED with TTL_ELAPSED" to 3819, "ACTIVE with a reason" to 3819, "EXPIRED without a reason" to 3819)
            }
        }
    }

    Given("authentication sessions (one-way rules H-4, n-h)") {
        Then("one open session per payment; a superseded VERIFIED session keeps its evidence as EXPIRED") {
            db.withApp { c ->
                val parents = c.parents()
                c.authSession(parents, "IN_PROGRESS").second shouldBe null
                c.authSession(parents, "CREATED").second shouldBe 1062
                c.authSession(parents, "EXPIRED", evidence = true, rrd = true, cause = "SUPERSEDED").second shouldBe null
                c.authSession(parents, "FAILED", rrd = true, failure = "PROVIDER_EXPIRED").second shouldBe null
            }
        }
        Then("each impossible combination is rejected by a CHECK") {
            db.withApp { c ->
                val parents = c.parents()
                mapOf(
                    "VERIFIED without evidence" to c.authSession(parents, "VERIFIED", rrd = true).second,
                    "VERIFIED without result reference" to c.authSession(parents, "VERIFIED", evidence = true).second,
                    "evidence on FAILED" to c.authSession(parents, "FAILED", evidence = true, rrd = true, failure = "AUTHENTICATION_FAILED").second,
                    "result reference on EXPIRED without evidence" to c.authSession(parents, "EXPIRED", rrd = true, cause = "TTL_ELAPSED").second,
                    "FAILED without a reason" to c.authSession(parents, "FAILED").second,
                    "EXPIRED without a cause" to c.authSession(parents, "EXPIRED").second,
                    "CREATED with a provider reference" to c.authSession(parents, "CREATED", providerRef = "sat_x").second,
                    "IN_PROGRESS without a provider reference" to c.authSession(parents, "IN_PROGRESS", providerRef = null).second,
                ).forEach { (name, code) -> withClue(name) { code shouldBe 3819 } }
            }
        }
    }

    Given("authentication session events") {
        Then("repeatable kinds are one row per (kind, detail); state-transition kinds and different details are separate rows (n-b)") {
            db.withApp { c ->
                val parents = c.parents()
                val (sid, _) = c.authSession(parents, "IN_PROGRESS")
                fun event(seq: Int, kind: String, detail: String?) = c.code(
                    "INSERT INTO authentication_session_event (authentication_session_id, seq, kind, detail_code, occurred_at, correlation_id) VALUES (?, ?, ?, ?, '2026-10-07 00:00:00.000', 'corr')",
                    sid, seq, kind, detail,
                )
                mapOf(
                    "created" to event(0, "CREATED", null),
                    "conflict" to event(1, "REJECTED_RESULT", "EVIDENCE_CONFLICT"),
                    "conflict again" to event(2, "REJECTED_RESULT", "EVIDENCE_CONFLICT"),
                    "not started" to event(3, "REJECTED_RESULT", "SESSION_NOT_STARTED"),
                    "same seq" to event(3, "STARTED", null),
                    "unknown kind" to event(4, "LOOKUP", null),
                ) shouldBe mapOf("created" to null, "conflict" to null, "conflict again" to 1062, "not started" to null, "same seq" to 1062, "unknown kind" to 3819)
            }
        }
    }
})
