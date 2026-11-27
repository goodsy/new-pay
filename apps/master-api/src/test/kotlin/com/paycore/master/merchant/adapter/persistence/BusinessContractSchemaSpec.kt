package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection

/**
 * MERCHANT-FLOW-001 FLOW-2a(§4B.5 불변식 DB 방어·§4B.10 4·V22, §4B.13 `BusinessContractSchemaSpec`): V21 사업자 계약 표 5개의 제약을 실제 MySQL 8.4.11에서 **runtime 계정**(V22 표 단위
 * 권한)으로 확인한다(Domain 규칙의 이중 방어).
 * - I-2 생성 접수 건 UNIQUE(계약 표·버전 표 각각), I-3 닫기 번호(닫은 = 닫힌 + 1), I-5 범위 값, I-6 해지 1회(terminate_key), I-7 이관 행만 시작일·근거 NULL, I-8 KST 시작 시각 CHECK.
 * - INSERT 전용: UPDATE·DELETE → 1142(BR-BIZ-329 — 버전 내용 불변, §4A.14 M-2). 표에 요율·결제수단 열 없음(BR-BIZ-322·347).
 * 오류 코드: 1062 중복, 1452 FK, 3819 CHECK, 1142 권한 없음, 1406 길이.
 */
@MasterDbTest
class BusinessContractSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use(block)

    val at = "2026-11-26 01:00:00.000"
    val actor = MasterSchemaRows.ACTOR
    val tables = listOf("business_contract", "business_contract_event", "business_contract_version", "business_contract_version_close", "business_contract_version_service")

    /** 사업자 + 계약 완료(이관 아님) 접수 건 [intakes]개 — 계약·버전 행의 FK 부모. */
    fun businessWithIntakes(c: Connection, intakes: Int = 3): String = MasterSchemaRows.business(c).also { b ->
        MasterSchemaRows.businessRegistration(c, b)
        (1..intakes).forEach { seq ->
            c.errorCodeOfUpdate(
                "INSERT INTO contract_intake (business_id, intake_seq, status, kind, target_contract_seq, migrated, received_on, intake_channel, contract_manager, " +
                    "desires_card_auth, desires_card_keyin, created_at, created_by, updated_at, updated_by, version) " +
                    "VALUES (?, ?, 'COMPLETED', 'NEW', NULL, 0, '2026-11-25', 'PHONE', 'admin:adm_01JCFL0000000000000000OP01', 0, 0, ?, ?, ?, ?, 2)",
                b, seq, at, actor, at, actor,
            ) shouldBe null
        }
    }

    fun contract(c: Connection, b: String, seq: Int, origin: Int, migrated: Int = 0): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?)",
        b, seq, origin, migrated, at, actor,
    )

    /** 버전 행. 기본값 = 정상 일반 버전(시작일 2026-11-26 → 시작 시각 2026-11-25 15:00 UTC, 근거 있음). */
    @Suppress("LongParameterList")
    fun version(
        c: Connection,
        b: String,
        seq: Int,
        no: Int,
        origin: Int,
        migrated: Int = 0,
        startsAt: String = "2026-11-25 15:00:00.000",
        startDate: String? = "2026-11-26",
        endDate: String? = null,
        document: String? = "DOC-2026-11-0001",
        reason: String? = null,
    ): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, reason, " +
            "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        b, seq, no, origin, migrated, startsAt, startDate, endDate, document, reason, at, actor,
    )

    fun service(c: Connection, b: String, seq: Int, no: Int, kind: String = "CARD_PG_PAYMENT"): Int? =
        c.errorCodeOfUpdate("INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind) VALUES (?, ?, ?, ?)", b, seq, no, kind)

    fun close(c: Connection, b: String, seq: Int, no: Int, by: Int): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_contract_version_close (business_id, contract_seq, version_no, closed_at, closed_by_version_no, created_at, created_by) " +
            "VALUES (?, ?, ?, '2026-11-26 15:00:00.000', ?, ?, ?)",
        b, seq, no, by, at, actor,
    )

    fun event(c: Connection, b: String, seq: Int, eventSeq: Int, kind: String, reason: String? = "사유 및 비고", evidence: String? = null): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_contract_event (business_id, contract_seq, event_seq, kind, reason, evidence_ref, actor_ref, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        b, seq, eventSeq, kind, reason, evidence, actor, at,
    )

    Given("V21 business contract tables: decided columns only") {
        Then("the 5 tables have exactly the §4B.10 columns; no fee / rate / settlement / payment-method column (BR-BIZ-322·347)") {
            root { c ->
                tables.associateWith { t ->
                    c.column(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_master' AND TABLE_NAME = '$t' ORDER BY ORDINAL_POSITION",
                    )
                }
            } shouldBe mapOf(
                "business_contract" to listOf("business_id", "contract_seq", "origin_intake_seq", "migrated", "created_at", "created_by"),
                "business_contract_event" to listOf(
                    "business_id", "contract_seq", "event_seq", "kind", "reason", "evidence_ref", "actor_ref", "occurred_at", "terminate_key",
                ),
                "business_contract_version" to listOf(
                    "business_id", "contract_seq", "version_no", "origin_intake_seq", "migrated", "starts_at", "start_date", "end_date", "document_ref", "reason",
                    "created_at", "created_by",
                ),
                "business_contract_version_close" to listOf(
                    "business_id", "contract_seq", "version_no", "closed_at", "closed_by_version_no", "created_at", "created_by",
                ),
                "business_contract_version_service" to listOf("business_id", "contract_seq", "version_no", "service_kind"),
            )
            root { c ->
                c.column(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_master' AND TABLE_NAME LIKE 'business_contract%' " +
                        "AND REGEXP_LIKE(COLUMN_NAME, '(^|_)(fee|rate|settlement|payout|method|cycle|limit|amount)(_|$)')",
                )
            }.shouldBeEmpty()
        }
    }

    Given("business_contract / business_contract_version (I-2·I-7·I-8)") {
        Then("I-2: one origin intake creates at most one contract and at most one version (1062); PK duplicates are 1062; FKs need the business / intake / contract (1452)") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                contract(c, b, 1, 2) shouldBe 1062 // PK
                contract(c, b, 2, 1) shouldBe 1062 // uk_bc_origin_intake — 신규 완료 1건 = 계약 1개
                contract(c, b, 2, 9) shouldBe 1452 // 없는 접수 건
                contract(c, MasterSchemaRows.id("biz"), 1, 1) shouldBe 1452
                contract(c, b, 0, 2) shouldBe 3819 // ck_bc_seq
                version(c, b, 1, 1, 1) shouldBe null
                version(c, b, 1, 2, 2, startsAt = "2026-11-26 15:00:00.000", startDate = "2026-11-27") shouldBe null
                version(c, b, 1, 3, 2, startsAt = "2026-11-27 15:00:00.000", startDate = "2026-11-28") shouldBe 1062 // uk_bcv_origin_intake — 변경 완료 1건 = 버전 1개
                version(c, b, 1, 2, 3) shouldBe 1062 // PK
                version(c, b, 9, 1, 3) shouldBe 1452 // 없는 계약
                version(c, b, 1, 0, 3) shouldBe 3819 // ck_bcv_seq
            }
        }

        Then("I-8: a normal version starts at its start date 00:00 KST = start date - 9h UTC; I-7 needs start date and document unless migrated; end ≥ start") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                version(c, b, 1, 1, 1, startsAt = "2026-11-26 00:00:00.000") shouldBe 3819 // UTC 00:00 ≠ KST 00:00
                version(c, b, 1, 1, 1, startsAt = "2026-11-25 15:00:00.001") shouldBe 3819
                version(c, b, 1, 1, 1, startDate = null) shouldBe 3819
                version(c, b, 1, 1, 1, document = null) shouldBe 3819
                version(c, b, 1, 1, 1, endDate = "2026-11-25") shouldBe 3819 // 종료일 < 시작일
                version(c, b, 1, 1, 1, document = "bad ref") shouldBe 3819 // 근거 형식(V19 관례)
                version(c, b, 1, 1, 1, document = "x".repeat(129)) shouldBe 1406
                version(c, b, 1, 1, 1, reason = "") shouldBe 3819
                version(c, b, 1, 1, 1, reason = "가".repeat(201)) shouldBe 1406
                version(c, b, 1, 1, 1, endDate = "2026-11-26", reason = "가".repeat(200)) shouldBe null // 종료일 = 시작일 허용
            }
        }

        Then("I-7: migrated rows — contract 1 of intake 1 only, version 1 only, no start date / end date / document / reason; normal rows may not look migrated") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 2, 2, migrated = 1) shouldBe 3819 // 이관 계약은 순번 1
                contract(c, b, 1, 2, migrated = 1) shouldBe 3819 // 이관 계약은 생성 접수 건 1
                contract(c, b, 1, 1, migrated = 1) shouldBe null
                val migratedAt = "2026-11-26 03:12:45.123"
                version(c, b, 1, 1, 1, migrated = 1, startsAt = migratedAt, startDate = "2026-11-26", document = null) shouldBe 3819
                version(c, b, 1, 1, 1, migrated = 1, startsAt = migratedAt, startDate = null, document = "DOC-1") shouldBe 3819
                version(c, b, 1, 1, 1, migrated = 1, startsAt = migratedAt, startDate = null, document = null, reason = "이관") shouldBe 3819
                version(c, b, 1, 1, 1, migrated = 1, startsAt = migratedAt, startDate = null, document = null, endDate = "2026-12-31") shouldBe 3819
                version(c, b, 1, 2, 2, migrated = 1, startsAt = migratedAt, startDate = null, document = null) shouldBe 3819 // 이관 버전은 1만
                version(c, b, 1, 1, 1, migrated = 1, startsAt = migratedAt, startDate = null, document = null) shouldBe null
                version(c, b, 1, 2, 2, migrated = 0, startsAt = migratedAt, startDate = null, document = null) shouldBe 3819 // 일반 버전은 시작일·근거 필수
            }
        }
    }

    Given("business_contract_version_service / _close / _event (I-3·I-5·I-6)") {
        Then("I-5: scope rows only CARD_PG_PAYMENT for now (3819), one per (version, kind), FK to the version") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                version(c, b, 1, 1, 1) shouldBe null
                service(c, b, 1, 1) shouldBe null
                service(c, b, 1, 1) shouldBe 1062
                service(c, b, 1, 1, "CARD_KEYIN") shouldBe 3819
                service(c, b, 1, 2) shouldBe 1452
            }
        }

        Then("I-3: a close row is written by the next version (closed_by = version + 1), one per version, both versions must exist") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                version(c, b, 1, 1, 1) shouldBe null
                close(c, b, 1, 1, 2) shouldBe 1452 // 닫는 버전 2가 아직 없다
                version(c, b, 1, 2, 2, startsAt = "2026-11-26 15:00:00.000", startDate = "2026-11-27") shouldBe null
                close(c, b, 1, 1, 3) shouldBe 3819 // ck_bcvc_next
                close(c, b, 1, 1, 2) shouldBe null
                close(c, b, 1, 1, 2) shouldBe 1062 // 버전당 닫기 1개
            }
        }

        // 기대값 변경(FLOW-2c V23 — 사용자 답 D-GOV-022): 정지·정지 해제 사건도 근거 참조 허용(NULL 허용 유지). 근거 형식 CHECK는 모든 종류에 그대로.
        Then("I-6 / BR-BIZ-336·339: reason required (1..200), evidence optional on every kind (D-GOV-022, V23), TERMINATE at most once per contract (uk_bce_terminate 1062)") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                contract(c, b, 2, 2) shouldBe null
                event(c, b, 1, 1, "SUSPEND", reason = null) shouldBe 1048 // NOT NULL
                event(c, b, 1, 1, "SUSPEND", reason = "") shouldBe 3819
                event(c, b, 1, 1, "SUSPEND", evidence = "bad ref") shouldBe 3819 // 근거 형식은 모든 종류에
                event(c, b, 1, 1, "CANCEL") shouldBe 3819
                event(c, b, 1, 1, "SUSPEND", evidence = "SUSP/2026-11:1") shouldBe null // D-GOV-022: 정지 근거 선택
                event(c, b, 1, 2, "RESUME", evidence = "RESU/2026-11:1") shouldBe null
                event(c, b, 1, 3, "SUSPEND") shouldBe null // 정지 계열은 여러 번, 근거 없음도 허용
                event(c, b, 1, 4, "TERMINATE", evidence = "bad ref") shouldBe 3819
                event(c, b, 1, 4, "TERMINATE", evidence = "TERM/2026-11:1") shouldBe null
                event(c, b, 1, 5, "TERMINATE") shouldBe 1062 // 해지 2회 — DB도 막는다
                event(c, b, 2, 1, "TERMINATE") shouldBe null // 다른 계약은 따로
                event(c, b, 9, 1, "SUSPEND") shouldBe 1452
                c.column("SELECT COUNT(*) FROM business_contract_event WHERE business_id = '$b' AND terminate_key IS NOT NULL").single() shouldBe "2"
            }
        }
    }

    Given("V22 runtime privileges: INSERT-only (BR-BIZ-329, §4A.14 M-2)") {
        Then("UPDATE and DELETE on the 5 tables are 1142 for the runtime account; SELECT and INSERT work") {
            runtime { c ->
                val b = businessWithIntakes(c)
                contract(c, b, 1, 1) shouldBe null
                version(c, b, 1, 1, 1) shouldBe null
                service(c, b, 1, 1) shouldBe null
                event(c, b, 1, 1, "SUSPEND") shouldBe null
                tables.associateWith { t -> listOf(c.errorCodeOf("UPDATE $t SET business_id = business_id WHERE business_id = '$b'"), c.errorCodeOf("DELETE FROM $t WHERE business_id = '$b'")) } shouldBe
                    tables.associateWith { listOf(1142, 1142) }
                c.column("SELECT COUNT(*) FROM business_contract_version WHERE business_id = '$b'").single() shouldBe "1"
            }
        }
    }
})
