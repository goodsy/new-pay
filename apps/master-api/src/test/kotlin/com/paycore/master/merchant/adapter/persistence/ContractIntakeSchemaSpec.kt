package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.ACTOR
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.t0
import com.paycore.master.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-FLOW-001 FLOW-1(§4A.3·§4A.4 n-2·§4A.9·§4A.14 M-2, §6 FLOW-1 수용 기준 — DB 측): V19 접수 건·전이 이력 표의 제약을 실제 MySQL 8.4.11에서 **runtime 계정**(운영과 같은
 * 표 단위 권한 — V20)으로 확인한다(Domain 규칙의 이중 방어).
 * - 생성 열 UNIQUE `uk_cin_active`: 사업자당 열린·완료 건 ≤ 1(동시 INSERT 포함), 불가·해지 건은 여러 개.
 * - 이관 건만 근거·담당자 NULL(접수 건 표 `ck_cin_manager`·전이 이력 표 `ck_cint_document` 각각), 표에 결정된 열만(대행사 열 없음 — m-2).
 * - 전이 이력 CHECK: 허용 전이 표·생성 행·사유 필수 전이·근거 위치·형식. INSERT 전용(UPDATE·DELETE 1142), 접수 건 DELETE 1142.
 * 오류 코드: 1062 중복, 1452 FK, 3819 CHECK, 1142 권한 없음.
 */
@MasterDbTest
class ContractIntakeSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use(block)

    val ts = Timestamp.from(t0)

    fun business(c: Connection): String = MasterSchemaRows.business(c).also { MasterSchemaRows.businessRegistration(c, it) }

    /** 일반(이관 아님) 접수 건 행. 기본값은 정상 상담 중 건. */
    @Suppress("LongParameterList")
    fun intake(
        c: Connection,
        businessId: String,
        seq: Int,
        status: String = "CONSULTING",
        migrated: Int = 0,
        manager: String? = "admin:adm_01JCFL0000000000000000OP01",
        channel: String? = "DIRECT_SALES",
        receivedOn: String? = "2026-11-25",
    ): Int? = c.errorCodeOfUpdate(
        "INSERT INTO contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
            "created_at, created_by, updated_at, updated_by, version) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, 0)",
        businessId, seq, status, migrated, receivedOn, channel, manager, ts, ACTOR, ts, ACTOR,
    )

    fun migratedIntake(c: Connection, businessId: String, seq: Int = 1, status: String = "COMPLETED"): Int? =
        intake(c, businessId, seq, status, migrated = 1, manager = null, channel = null, receivedOn = null)

    @Suppress("LongParameterList")
    fun transition(
        c: Connection,
        businessId: String,
        intakeSeq: Int,
        seq: Int,
        from: String?,
        to: String,
        migrated: Int = 0,
        reason: String? = null,
        document: String? = null,
    ): Int? = c.errorCodeOfUpdate(
        "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        businessId, intakeSeq, seq, from, to, migrated, reason, document, ACTOR, ts,
    )

    Given("V19 contract_intake: the decided columns only and the generated UNIQUE key") {
        Then("m-2: the intake table has exactly the decided columns (no sales-agency column before FLOW-4); the history table has the n-2 columns") {
            root { c ->
                c.column(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_master' AND TABLE_NAME = 'contract_intake' ORDER BY ORDINAL_POSITION",
                ) to c.column(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_master' AND TABLE_NAME = 'contract_intake_transition' ORDER BY ORDINAL_POSITION",
                )
            } shouldBe (
                listOf(
                    "business_id", "intake_seq", "status", "migrated", "received_on", "intake_channel", "contract_manager", "desires_card_auth", "desires_card_keyin",
                    "created_at", "created_by", "updated_at", "updated_by", "version", "active_key",
                ) to listOf(
                    "business_id", "intake_seq", "transition_seq", "from_status", "to_status", "migrated", "reason", "document_ref", "actor_ref", "occurred_at",
                )
                )
            // 수수료율·등급·그룹·대행사 열 없음(D-FLOW-001 ③, D-FLOW-003 ⑦, m-2).
            root { c ->
                c.column(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_master' AND TABLE_NAME IN ('contract_intake', 'contract_intake_transition') " +
                        "AND REGEXP_LIKE(COLUMN_NAME, '(^|_)(agency|fee|rate|grade|group)(_|$)')",
                )
            }.shouldBeEmpty()
        }

        Then("D-FLOW-003 ①·D-FLOW-004 ①: a second open or completed intake of the same business is a 1062 (uk_cin_active); rejected/terminated intakes do not count") {
            runtime { c ->
                val b = business(c)
                intake(c, b, 1) shouldBe null
                intake(c, b, 2) shouldBe 1062 // 열린 건 둘
                intake(c, b, 2, status = "DOCUMENT_RECEIVED") shouldBe 1062
                intake(c, b, 2, status = "COMPLETED") shouldBe 1062 // 열린 건 + 완료 건
                intake(c, b, 2, status = "REJECTED") shouldBe null
                intake(c, b, 3, status = "TERMINATED") shouldBe null
                // 열린 건이 계약 불가가 되면(상태 UPDATE — runtime UPDATE 허용) 새 상담 중 건을 넣을 수 있다.
                c.update("UPDATE contract_intake SET status = 'REJECTED', version = version + 1 WHERE business_id = ? AND intake_seq = 1", b) shouldBe 1
                intake(c, b, 4) shouldBe null
                // 같은 순번은 PK 중복.
                intake(c, b, 4, status = "REJECTED") shouldBe 1062
                // 다른 사업자의 열린 건은 서로 막지 않는다.
                intake(c, business(c), 1) shouldBe null
            }
        }

        Then("concurrency: two transactions inserting an open intake for the same business at once — exactly one commits, the other gets 1062") {
            val b = runtime { c -> business(c) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val results = (1..2).map { n ->
                pool.submit<Int?> {
                    runtime { c ->
                        c.autoCommit = false
                        start.await()
                        val code = intake(c, b, n)
                        if (code == null) c.commit() else c.rollback()
                        code
                    }
                }
            }
            start.countDown()
            val codes = results.map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            codes.sortedBy { it ?: 0 } shouldBe listOf(null, 1062)
            runtime { c -> c.column("SELECT COUNT(*) FROM contract_intake WHERE business_id = '$b' AND active_key IS NOT NULL").single() } shouldBe "1"
        }

        Then("§4A.4 n-2 / R-6: only a migrated intake may lack the contract manager and intake items; a migrated intake is intake 1 in COMPLETED/TERMINATED") {
            runtime { c ->
                val b = business(c)
                intake(c, b, 1, manager = null) shouldBe 3819 // ck_cin_manager
                intake(c, b, 1, receivedOn = null) shouldBe 3819 // ck_cin_items
                intake(c, b, 1, channel = null) shouldBe 3819
                migratedIntake(c, b, seq = 2) shouldBe 3819 // ck_cin_migrated — 이관 건은 순번 1
                migratedIntake(c, b, status = "CONSULTING") shouldBe 3819
                intake(c, b, 1, status = "COMPLETED", migrated = 1) shouldBe 3819 // 이관 건에 접수 항목 값
                migratedIntake(c, b) shouldBe null
            }
        }

        Then("m-2: the channel 'SALES_AGENCY' cannot be stored before FLOW-4 adds the agency column (fail-closed); unknown status / channel are refused") {
            runtime { c ->
                val b = business(c)
                intake(c, b, 1, channel = "SALES_AGENCY") shouldBe 3819
                intake(c, b, 1, channel = "UNKNOWN") shouldBe 3819
                intake(c, b, 1, status = "APPROVED") shouldBe 3819
                listOf("DIRECT_SALES", "PHONE", "WEBSITE", "OTHER").mapIndexed { i, ch -> intake(c, b, i + 1, status = "REJECTED", channel = ch) } shouldBe
                    listOf(null, null, null, null)
            }
        }
    }

    Given("V19 contract_intake_transition: INSERT-only history with the transition table as a CHECK") {
        Then("§4A.3 table: creation rows (none → CONSULTING, migrated none → COMPLETED) and the 6 moves are accepted; every other pair and same-state rows are 3819") {
            val statuses = listOf("CONSULTING", "DOCUMENT_RECEIVED", "COMPLETED", "REJECTED", "TERMINATED")
            val allowed = setOf(
                "CONSULTING" to "DOCUMENT_RECEIVED", "DOCUMENT_RECEIVED" to "CONSULTING", "DOCUMENT_RECEIVED" to "COMPLETED", "CONSULTING" to "REJECTED",
                "DOCUMENT_RECEIVED" to "REJECTED", "COMPLETED" to "TERMINATED",
            )
            runtime { c ->
                val b = business(c)
                intake(c, b, 1, status = "REJECTED") shouldBe null
                transition(c, b, 1, 1, null, "CONSULTING") shouldBe null
                var seq = 2
                statuses.forEach { from ->
                    statuses.forEach { to ->
                        withClue("$from → $to") {
                            // 사유·근거는 필요한 전이에만 채운다(그 CHECK와 분리해 표 CHECK만 본다).
                            val code = transition(c, b, 1, seq, from, to, reason = "사유 및 비고", document = if (to == "COMPLETED") "DOC-2026-0001" else null)
                            if ((from to to) in allowed) {
                                code shouldBe null
                                seq++
                            } else {
                                code shouldBe 3819
                            }
                        }
                    }
                }
                // 생성 행은 순번 1만, 전 상태 없는 행은 상담 중(이관 행만 계약 완료).
                transition(c, b, 1, seq, null, "CONSULTING") shouldBe 3819 // ck_cint_first
                transition(c, b, 1, 1, null, "CONSULTING") shouldBe 1062 // 같은 순번 PK
                val other = business(c)
                intake(c, other, 1) shouldBe null
                transition(c, other, 1, 1, null, "COMPLETED") shouldBe 3819 // 이관 아님 + 생성 → 계약 완료
                transition(c, other, 1, 1, null, "DOCUMENT_RECEIVED") shouldBe 3819
            }
        }

        Then("§4A.4 n-2: a COMPLETED row needs a document reference unless it is the migration row; document only on COMPLETED/TERMINATED, fixed format") {
            runtime { c ->
                val b = business(c)
                intake(c, b, 1, status = "REJECTED") shouldBe null
                transition(c, b, 1, 1, null, "CONSULTING") shouldBe null
                transition(c, b, 1, 2, "CONSULTING", "DOCUMENT_RECEIVED") shouldBe null
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "COMPLETED") shouldBe 3819 // 근거 없음
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "COMPLETED", document = "bad ref with spaces") shouldBe 3819
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "COMPLETED", document = "x".repeat(129)) shouldBe 1406 // 열 길이(strict mode)가 먼저 막는다
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "COMPLETED", document = "CONTRACT/2026-11:A#1_v.2") shouldBe null
                transition(c, b, 1, 4, "COMPLETED", "TERMINATED", reason = "해지 요청") shouldBe null // 해지 근거는 선택
                val other = business(c)
                intake(c, other, 1, status = "REJECTED") shouldBe null
                transition(c, other, 1, 1, null, "CONSULTING", document = "DOC-1") shouldBe 3819 // 근거 위치
                // 이관 행: 근거·사유 NULL만, 순번 1만.
                val migrated = business(c)
                migratedIntake(c, migrated) shouldBe null
                transition(c, migrated, 1, 1, null, "COMPLETED", migrated = 1, document = "DOC-1") shouldBe 3819
                transition(c, migrated, 1, 1, null, "COMPLETED", migrated = 1, reason = "이관") shouldBe 3819
                transition(c, migrated, 1, 1, null, "COMPLETED", migrated = 1) shouldBe null
                transition(c, migrated, 1, 2, "COMPLETED", "TERMINATED", migrated = 1, reason = "해지") shouldBe 3819 // 이관 표시는 생성 행만
                transition(c, migrated, 1, 2, "COMPLETED", "TERMINATED", reason = "해지") shouldBe null
            }
        }

        Then("D-FLOW-004 ②·D-FLOW-005 ②: 되돌리기·계약 불가·계약 해지 rows need '사유 및 비고' (1..200 code points); 계약서 접수 does not") {
            runtime { c ->
                val b = business(c)
                intake(c, b, 1, status = "REJECTED") shouldBe null
                transition(c, b, 1, 1, null, "CONSULTING") shouldBe null
                transition(c, b, 1, 2, "CONSULTING", "DOCUMENT_RECEIVED") shouldBe null
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "CONSULTING") shouldBe 3819
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "CONSULTING", reason = "") shouldBe 3819
                transition(c, b, 1, 3, "DOCUMENT_RECEIVED", "CONSULTING", reason = "가".repeat(200)) shouldBe null
                transition(c, b, 1, 4, "CONSULTING", "REJECTED") shouldBe 3819
                transition(c, b, 1, 4, "CONSULTING", "REJECTED", reason = "서류 미비") shouldBe null
            }
        }

        Then("§4A.14 M-2: runtime UPDATE and DELETE on the history table are 1142; DELETE on the intake table is 1142 (no physical delete)") {
            runtime { c ->
                val b = business(c)
                migratedIntake(c, b) shouldBe null
                transition(c, b, 1, 1, null, "COMPLETED", migrated = 1) shouldBe null
                c.errorCodeOf("UPDATE contract_intake_transition SET actor_ref = 'tampered' WHERE business_id = '$b'") shouldBe 1142
                c.errorCodeOf("DELETE FROM contract_intake_transition WHERE business_id = '$b'") shouldBe 1142
                c.errorCodeOf("DELETE FROM contract_intake WHERE business_id = '$b'") shouldBe 1142
                c.column("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = '$b' AND actor_ref = '$ACTOR'").single() shouldBe "1"
            }
        }

        Then("FK: a history row needs its intake; an intake needs its business (1452)") {
            runtime { c ->
                transition(c, MasterSchemaRows.id("biz"), 1, 1, null, "CONSULTING") shouldBe 1452
                intake(c, MasterSchemaRows.id("biz"), 1) shouldBe 1452
            }
        }
    }
})
