package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_CONTROL
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_HOLD
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_IDEMPOTENCY
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_OUTBOX
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.INSERT_PAYMENT
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.idempotencyArgs
import com.paycore.payment.payment.adapter.persistence.PaymentSchemaRows.paymentArgs
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * PAYMENT 첫 운영 migration의 DB 제약을 실제 MySQL 8.4에서 검사한다(CP-DB-001, CP-CONTRACT-001 §12).
 * information_schema 검사(선언)와 실제 INSERT 행위(수락/거부)를 함께 본다 — 선언만 보고 동작을 가정하지 않는다.
 *
 * 오류 코드: 1062 = 중복 키, 3819 = CHECK 위반, 1406 = 길이 초과(strict mode), 1452 = FK 부모 없음.
 */
@PaymentDbTest
class PaymentSchemaSpec(private val db: DbAccess) : BehaviorSpec({

    val businessTables = listOf(
        "payment", "payment_termination_evidence", "order_payment_control", "incident_hold", "idempotency_request", "outbox_event",
    )

    /** D-CP-001: 업무 키·fingerprint 컬럼. 아래 "모든 문자열 컬럼" 검사가 빈 목록으로 통과하지 않게 대상을 명시한다. */
    val keyColumns = mapOf(
        "payment" to listOf(
            "payment_id", "payment_key", "environment", "merchant_account_id", "merchant_order_id", "merchant_site_id", "currency",
            "order_intent_fingerprint", "eligibility_snapshot_version",
        ),
        "order_payment_control" to listOf(
            "environment", "merchant_account_id", "merchant_order_id", "fixed_intent_fingerprint", "current_payment_id",
            "authorized_payment_id",
        ),
        "idempotency_request" to listOf(
            "environment", "merchant_account_id", "operation", "idempotency_key", "request_record_id", "request_fingerprint",
            "result_payment_id",
        ),
        "outbox_event" to listOf("event_id", "effect_key", "aggregate_id", "correlation_id", "causation_id"),
        "incident_hold" to listOf("environment", "merchant_account_id", "merchant_order_id", "payment_id", "reason"),
        "payment_termination_evidence" to listOf("payment_id"),
    )

    fun stringColumns(): List<Map<String, Any?>> = db.withApp { c ->
        with(db) {
            c.rows(
                """
                SELECT c.TABLE_NAME AS t, c.COLUMN_NAME AS col, c.CHARACTER_SET_NAME AS cs, c.COLLATION_NAME AS coll,
                       k.PAD_ATTRIBUTE AS pad
                FROM information_schema.COLUMNS c
                LEFT JOIN information_schema.COLLATIONS k ON k.COLLATION_NAME = c.COLLATION_NAME
                WHERE c.TABLE_SCHEMA = DATABASE() AND c.DATA_TYPE IN ('char', 'varchar', 'text')
                  AND c.TABLE_NAME IN (${businessTables.joinToString { "'$it'" }})
                """.trimIndent(),
            )
        }
    }

    Given("the PAYMENT migration is applied") {
        Then("all business tables exist with utf8mb4_0900_bin as the table default collation") {
            val tables = db.withApp { c ->
                with(db) {
                    c.rows(
                        "SELECT TABLE_NAME AS t, TABLE_COLLATION AS coll FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                    )
                }
            }.associate { it["t"] to it["coll"] }
            tables.keys shouldContainAll businessTables
            businessTables.forEach { tables[it] shouldBe "utf8mb4_0900_bin" }
        }

        Then("every business key and fingerprint column declares utf8mb4_0900_bin (NO PAD binary), not the server default") {
            val columns = stringColumns()
            val byTable = columns.groupBy({ it["t"] as String }, { it["col"] as String })
            keyColumns.forEach { (table, cols) -> byTable[table].orEmpty() shouldContainAll cols }
            // 키 컬럼뿐 아니라 업무 테이블의 모든 문자열 컬럼이 서버 기본(utf8mb4_0900_ai_ci)을 상속하지 않는다.
            columns.filter { it["coll"] != "utf8mb4_0900_bin" || it["cs"] != "utf8mb4" || it["pad"] != "NO PAD" }.shouldBeEmpty()
        }

        Then("the server default collation is different, so the explicit COLLATE matters (the check is not vacuous)") {
            db.withApp { c -> with(db) { c.scalar("SELECT @@collation_server") } } shouldBe "utf8mb4_0900_ai_ci"
        }

        Then("payment has no UNIQUE index on (environment, merchant_account_id, merchant_order_id) but has a lookup index") {
            val indexes = db.withApp { c ->
                with(db) {
                    c.rows(
                        """
                        SELECT INDEX_NAME AS n, NON_UNIQUE AS nu, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment'
                        GROUP BY INDEX_NAME, NON_UNIQUE
                        """.trimIndent(),
                    )
                }
            }
            val orderCols = "environment,merchant_account_id,merchant_order_id"
            indexes.filter { it["cols"] == orderCols && (it["nu"] as Number).toInt() == 0 }.shouldBeEmpty()
            indexes.filter { (it["cols"] as String).startsWith(orderCols) }.shouldNotBeEmpty()
        }

        Then("idempotency_request has UNIQUE(environment, merchant_account_id, operation, idempotency_key)") {
            val unique = db.withApp { c ->
                with(db) {
                    c.rows(
                        """
                        SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'idempotency_request' AND NON_UNIQUE = 0
                        GROUP BY INDEX_NAME
                        """.trimIndent(),
                    )
                }
            }.map { it["cols"] }
            unique shouldContainAll listOf("environment,merchant_account_id,operation,idempotency_key")
        }
    }

    Given("D-CP-001: keys are compared exactly (case and trailing space are significant)") {
        When("order_payment_control rows differ only in case or trailing space of merchant_order_id or MID") {
            Then("all are accepted as different orders and an exact duplicate is rejected") {
                val mid = unique("mid")
                db.withApp { c ->
                    with(db) {
                        c.errorCodeOf(INSERT_CONTROL, "TEST", mid, "Order-1", "p1") shouldBe null
                        c.errorCodeOf(INSERT_CONTROL, "TEST", mid, "order-1", "p2") shouldBe null
                        c.errorCodeOf(INSERT_CONTROL, "TEST", mid, "Order-1 ", "p3") shouldBe null
                        c.errorCodeOf(INSERT_CONTROL, "TEST", mid.uppercase(), "Order-1", "p4") shouldBe null
                        c.errorCodeOf(INSERT_CONTROL, "TEST", mid, "Order-1", "p5") shouldBe 1062
                        c.errorCodeOf(INSERT_CONTROL, "LIVE", mid, "Order-1", "p6") shouldBe null
                    }
                }
                db.count("SELECT COUNT(*) FROM order_payment_control WHERE merchant_account_id IN (?, ?)", mid, mid.uppercase()) shouldBe 5
            }
        }

        When("idempotency keys differ only in case or trailing space") {
            Then("they are different scopes and an exact duplicate scope is rejected") {
                val mid = unique("mid")
                db.withApp { c ->
                    with(db) {
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "Key-1")) shouldBe null
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "key-1")) shouldBe null
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "Key-1 ")) shouldBe null
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "Key-1", recordId = "other")) shouldBe 1062
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "Key-1", operation = "OtherOperation")) shouldBe null
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "Key-1", environment = "LIVE")) shouldBe null
                    }
                }
            }
        }

        When("payment keys differ only in case") {
            Then("both are stored and an exact duplicate payment_key is rejected") {
                val mid = unique("mid")
                db.withApp { c ->
                    with(db) {
                        c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-a", paymentKey = "$mid-PK", mid = mid, orderId = "o1")) shouldBe null
                        c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-b", paymentKey = "$mid-pk", mid = mid, orderId = "o2")) shouldBe null
                        c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-c", paymentKey = "$mid-PK", mid = mid, orderId = "o3")) shouldBe 1062
                        c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-a", paymentKey = "$mid-other", mid = mid, orderId = "o4")) shouldBe 1062
                    }
                }
            }
        }
    }

    Given("payment amount and currency constraints (D-CPC-005, D-CP-002)") {
        Then("amount_minor below 1 is rejected and 1..Long.MAX_VALUE is accepted") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-0", mid = mid, orderId = "o0", amount = 0)) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-n", mid = mid, orderId = "on", amount = -1)) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-1", mid = mid, orderId = "o1", amount = 1)) shouldBe null
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-m", mid = mid, orderId = "om", amount = Long.MAX_VALUE)) shouldBe null
                    (c.scalar("SELECT amount_minor FROM payment WHERE payment_id = ?", "$mid-m") as Number).toLong() shouldBe Long.MAX_VALUE
                }
            }
        }

        Then("currency must be exactly three upper-case letters (no case folding, no padding)") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-1", mid = mid, orderId = "o1", currency = "krw")) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-2", mid = mid, orderId = "o2", currency = "Krw")) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-3", mid = mid, orderId = "o3", currency = "KR")) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-4", mid = mid, orderId = "o4", currency = "K1W")) shouldBe 3819
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-5", mid = mid, orderId = "o5", currency = "KRWX")) shouldBe 1406
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-6", mid = mid, orderId = "o6", currency = "USD")) shouldBe null
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-7", mid = mid, orderId = "o7", currency = "KRW")) shouldBe null
                }
            }
        }

        Then("environment accepts only TEST and LIVE") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-1", mid = mid, orderId = "o1", environment = "test")) shouldBe 3819
                    c.errorCodeOf(INSERT_CONTROL, "PROD", mid, "o1", "p1") shouldBe 3819
                }
            }
        }
    }

    Given("an order has a payment history (1:N, no UNIQUE on the order key in payment)") {
        Then("several payments of the same order with different slot generations are accepted, the same generation is rejected") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-g1", mid = mid, orderId = "o1", slotGeneration = 1)) shouldBe null
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-g2", mid = mid, orderId = "o1", slotGeneration = 2)) shouldBe null
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-g2b", mid = mid, orderId = "o1", slotGeneration = 2)) shouldBe 1062
                    c.errorCodeOf(INSERT_PAYMENT, *paymentArgs("$mid-g0", mid = mid, orderId = "o1", slotGeneration = 0)) shouldBe 3819
                }
            }
            db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", mid) shouldBe 2
        }
    }

    Given("idempotency record retention and result columns (D-CPC-004, D-CPC-007)") {
        Then("expires_at must be exactly created_at + 30 days") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "k1", expiresAt = "2026-11-05 00:00:00.000")) shouldBe 3819
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "k2", expiresAt = "2026-11-06 00:00:00.001")) shouldBe 3819
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "k3")) shouldBe null
                }
            }
        }

        Then("result kind, code and payment reference must be consistent (success, business rejection or in-flight reservation)") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "s1", resultKind = "SUCCESS", resultCode = "CREATED")) shouldBe null
                    c.errorCodeOf(
                        INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "s2", resultKind = "SUCCESS", resultCode = "EXISTING_ACTIVE_PAYMENT"),
                    ) shouldBe null
                    c.errorCodeOf(
                        INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "r1", resultKind = "REJECTED", resultCode = "FIXED_INTENT_CONFLICT"),
                    ) shouldBe null
                    listOf("INCIDENT_HOLD", "AUTHORIZED_NEW_ORDER_REQUIRED", "TERMINATION_EVIDENCE_INSUFFICIENT").forEach { code ->
                        c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "r-$code", resultKind = "REJECTED", resultCode = code)) shouldBe
                            null
                    }
                    c.errorCodeOf(
                        INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "p1", resultKind = "PENDING", resultCode = null, resultPaymentId = null),
                    ) shouldBe null
                    // 불일치 조합
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "x1", resultKind = "SUCCESS", resultCode = "INCIDENT_HOLD")) shouldBe
                        3819
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "x2", resultKind = "REJECTED", resultCode = "CREATED")) shouldBe 3819
                    c.errorCodeOf(
                        INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "x3", resultKind = "SUCCESS", resultPaymentId = null),
                    ) shouldBe 3819
                    c.errorCodeOf(
                        INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "x4", resultKind = "PENDING", resultCode = "CREATED"),
                    ) shouldBe 3819
                    c.errorCodeOf(INSERT_IDEMPOTENCY, *idempotencyArgs(mid, "x5", resultKind = "DONE")) shouldBe 3819
                }
            }
        }
    }

    Given("outbox uniqueness (D-CPC-003)") {
        Then("a duplicate event_id or a duplicate effect_key is rejected") {
            val id = unique("evt")
            db.withApp { c ->
                with(db) {
                    c.errorCodeOf(INSERT_OUTBOX, "$id-1", "PaymentRequested:$id", "pay-$id") shouldBe null
                    c.errorCodeOf(INSERT_OUTBOX, "$id-1", "PaymentRequested:$id-other", "pay-$id") shouldBe 1062
                    c.errorCodeOf(INSERT_OUTBOX, "$id-2", "PaymentRequested:$id", "pay-$id") shouldBe 1062
                }
            }
        }
    }

    Given("incident hold uniqueness (CP-DOMAIN-001 M-3)") {
        Then("the same (order, payment, reason) is stored once and a hold needs its order control") {
            val mid = unique("mid")
            db.withApp { c ->
                with(db) {
                    c.update(INSERT_CONTROL, "TEST", mid, "o1", "p1")
                    c.errorCodeOf(INSERT_HOLD, "TEST", mid, "o1", "p1", "SUCCESS_AFTER_TERMINATION") shouldBe null
                    c.errorCodeOf(INSERT_HOLD, "TEST", mid, "o1", "p1", "SUCCESS_AFTER_TERMINATION") shouldBe 1062
                    c.errorCodeOf(INSERT_HOLD, "TEST", mid, "o1", "p0", "SUCCESS_AFTER_TERMINATION") shouldBe null
                    c.errorCodeOf(INSERT_HOLD, "TEST", mid, "o1", "p1", "OTHER_REASON") shouldBe 3819
                    c.errorCodeOf(INSERT_HOLD, "TEST", mid, "no-such-order", "p1", "SUCCESS_AFTER_TERMINATION") shouldBe 1452
                }
            }
        }
    }
})
