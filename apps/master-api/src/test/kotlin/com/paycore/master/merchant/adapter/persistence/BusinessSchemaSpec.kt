package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.ACTOR
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.t0
import com.paycore.master.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.Timestamp

/**
 * BUSINESS-PROFILE-001 BIZ-1-2(빈 DB에 V1~V16 — 공유 시험 컨텍스트가 빈 DB에서 migration한 schema)·BIZ-1-6(권한)·BIZ-1-10(TC-21 DB 측):
 * V15 CHECK(진위 NOT_CHECKED만·REGISTERED는 구분 필수·국가 KR·형식), 현재 행 생성 열 UNIQUE(기본 담당자·순서·주소 유형·업태 이름 — 1062), CHECK 이름 접두 분리,
 * runtime 계정(운영과 같은 표 단위 권한)의 DELETE 1142. 오류 코드: 1062 중복, 1452 FK, 3819 CHECK, 1142 권한 없음.
 * 변이 (d)(생성 열 UNIQUE 제거 migration) → "current-row generated UNIQUE keys" RED.
 */
@MasterDbTest
class BusinessSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use(block)

    val ts = Timestamp.from(t0)
    val later = Timestamp.from(t0.plusSeconds(60))

    fun businessRow(c: Connection, status: String = "DRAFT", type: String? = null, verification: String = "NOT_CHECKED"): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, business_type, registration_status, " +
            "verification_status, updated_at, updated_by) VALUES (?, ?, 'Demo business', 'ACTIVE', ?, ?, 0, ?, ?, ?, ?, ?)",
        MasterSchemaRows.id("biz"), MasterSchemaRows.registrationNumber(), ts, ACTOR, type, status, verification, ts, ACTOR,
    )

    fun representative(c: Connection, businessId: String, order: Int, type: String = "JOINT", to: Timestamp? = null, endedBy: String? = null): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_representative (business_id, name, representative_type, display_order, effective_from, effective_to, created_by, ended_by) " +
            "VALUES (?, '대표', ?, ?, ?, ?, ?, ?)",
        businessId, type, order, ts, to, ACTOR, endedBy,
    )

    fun contact(c: Connection, businessId: String, order: Int, primary: Int, to: Timestamp? = null, email: String = "a@example.test", phone: String = "01012345678"): Int? =
        c.errorCodeOfUpdate(
            "INSERT INTO business_contact (business_id, contact_role, name, email, phone_number, is_primary, display_order, effective_from, effective_to, created_by, ended_by) " +
                "VALUES (?, 'REGISTRATION', '담당', ?, ?, ?, ?, ?, ?, ?, ?)",
            businessId, email, phone, primary, order, ts, to, ACTOR, to?.let { ACTOR },
        )

    fun category(c: Connection, registrationId: Long, type: String, name: String, order: Int): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_category (registration_id, category_type, name, display_order, effective_from, created_by) VALUES (?, ?, ?, ?, ?, ?)",
        registrationId, type, name, order, ts, ACTOR,
    )

    fun address(c: Connection, registrationId: Long, type: String, postal: String = "06236"): Int? = c.errorCodeOfUpdate(
        "INSERT INTO business_address (registration_id, address_type, postal_code, base_address, detail_address, effective_from, created_by) VALUES (?, ?, ?, '서울 강남구', NULL, ?, ?)",
        registrationId, type, postal, ts, ACTOR,
    )

    Given("BIZ-1-2 V15 on the business table") {
        Then("new columns exist with the expected nullability (business_type NULL allowed for migration rows only)") {
            root { c ->
                c.column(
                    "SELECT CONCAT(column_name, ':', is_nullable, ':', column_type) FROM information_schema.columns WHERE table_schema = 'paycore_master' " +
                        "AND table_name = 'business' AND column_name IN ('business_type', 'registration_status', 'verification_status', 'updated_at', 'updated_by') ORDER BY column_name",
                )
            } shouldBe listOf(
                "business_type:YES:varchar(16)", "registration_status:NO:varchar(16)", "updated_at:NO:datetime(3)", "updated_by:NO:varchar(64)",
                "verification_status:NO:varchar(16)",
            )
        }

        Then("TC-21 / D-BIZ-001 CHECKs: verification other than NOT_CHECKED, REGISTERED without type, unknown type/status are 3819; DRAFT with NULL type is accepted") {
            runtime { c ->
                mapOf(
                    "draft without type" to businessRow(c),
                    "registered individual" to businessRow(c, status = "REGISTERED", type = "INDIVIDUAL"),
                    "verified" to businessRow(c, verification = "VERIFIED"),
                    "registered without type" to businessRow(c, status = "REGISTERED"),
                    "inactive without type" to businessRow(c, status = "INACTIVE"),
                    "unknown type" to businessRow(c, type = "FOREIGN"),
                    "unknown status" to businessRow(c, status = "DELETED", type = "INDIVIDUAL"),
                    "updated before created" to c.errorCodeOfUpdate(
                        "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, registration_status, " +
                            "verification_status, updated_at, updated_by) VALUES (?, ?, 'x', 'ACTIVE', ?, ?, 0, 'DRAFT', 'NOT_CHECKED', ?, ?)",
                        MasterSchemaRows.id("biz"), MasterSchemaRows.registrationNumber(), later, ACTOR, ts, ACTOR,
                    ),
                )
            } shouldBe mapOf(
                "draft without type" to null, "registered individual" to null, "verified" to 3819, "registered without type" to 3819,
                "inactive without type" to 3819, "unknown type" to 3819, "unknown status" to 3819, "updated before created" to 3819,
            )
        }

        Then("the new CHECK names use per-table prefixes and do not collide with V1 names") {
            root { c ->
                c.column(
                    "SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema = 'paycore_master' AND (constraint_name LIKE 'ck\\_business\\_%' " +
                        "OR constraint_name LIKE 'ck\\_breg\\_%' OR constraint_name LIKE 'ck\\_brep\\_%' OR constraint_name LIKE 'ck\\_bcat\\_%' OR constraint_name LIKE 'ck\\_badr\\_%' " +
                        "OR constraint_name LIKE 'ck\\_bcon\\_%') ORDER BY constraint_name",
                )
            } shouldBe listOf(
                "ck_badr_ended", "ck_badr_period", "ck_badr_postal_code", "ck_badr_type",
                "ck_bcat_ended", "ck_bcat_order", "ck_bcat_period", "ck_bcat_type",
                "ck_bcon_email", "ck_bcon_ended", "ck_bcon_order", "ck_bcon_period", "ck_bcon_phone", "ck_bcon_primary", "ck_bcon_role",
                "ck_breg_corporate_number", "ck_breg_country", "ck_breg_email", "ck_breg_english_name", "ck_breg_phone",
                "ck_brep_ended", "ck_brep_order", "ck_brep_period", "ck_brep_type",
                "ck_business_id", "ck_business_lifecycle", "ck_business_reg_status", "ck_business_registration_number", "ck_business_type",
                "ck_business_type_required", "ck_business_updated", "ck_business_verification", "ck_business_version",
            )
        }

        Then("list indexes for the BIZ-2 directory exist") {
            root { c ->
                c.column(
                    "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = 'paycore_master' AND table_name = 'business' " +
                        "AND index_name LIKE 'ix\\_business\\_%' ORDER BY index_name",
                )
            } shouldBe listOf("ix_business_created_id", "ix_business_name_id", "ix_business_status_id")
        }
    }

    Given("BIZ-1-2 V15 registration and child tables (runtime account)") {
        Then("registration CHECKs: country KR only, corporate number 13 digits, phone and email formats, printable English name; one row per business") {
            runtime { c ->
                val businessId = MasterSchemaRows.business(c)
                val registrationId = MasterSchemaRows.businessRegistration(c, businessId)
                fun set(column: String, value: String) = c.errorCodeOfUpdate("UPDATE business_registration SET $column = ? WHERE registration_id = ?", value, registrationId)
                mapOf(
                    "valid corporate" to set("corporate_registration_number", "1101110000001"),
                    "corporate 12 digits" to set("corporate_registration_number", "110111000000"),
                    "country US" to set("country_code", "US"),
                    "phone starting with 2" to set("phone_number", "212345678"),
                    "phone 7 digits" to set("phone_number", "0212345"),
                    "valid phone" to set("phone_number", "0212345678"),
                    "email without dot" to set("email", "a@example"),
                    "valid email" to set("email", "Office.Team+1@Example.TEST"),
                    "english non-ascii" to set("english_name", "데모"),
                    "second registration row" to c.errorCodeOfUpdate("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')", businessId),
                    "unknown business" to c.errorCodeOfUpdate("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')", MasterSchemaRows.id("biz")),
                )
            } shouldBe mapOf(
                "valid corporate" to null, "corporate 12 digits" to 3819, "country US" to 3819, "phone starting with 2" to 3819, "phone 7 digits" to 3819,
                "valid phone" to null, "email without dot" to 3819, "valid email" to null, "english non-ascii" to 3819, "second registration row" to 1062,
                "unknown business" to 1452,
            )
        }

        Then("child row CHECKs: types, order range, period, ended pair") {
            runtime { c ->
                val businessId = MasterSchemaRows.business(c)
                mapOf(
                    "valid" to representative(c, businessId, 1),
                    "unknown type" to representative(c, businessId, 2, type = "CEO"),
                    "order 0" to representative(c, businessId, 0),
                    "order 6" to representative(c, businessId, 6),
                    "ended before start" to c.errorCodeOfUpdate(
                        "INSERT INTO business_representative (business_id, name, representative_type, display_order, effective_from, effective_to, created_by, ended_by) " +
                            "VALUES (?, '대표', 'JOINT', 3, ?, ?, ?, ?)",
                        businessId, later, ts, ACTOR, ACTOR,
                    ),
                    "ended without actor" to representative(c, businessId, 4, to = later, endedBy = null),
                    "actor without end" to representative(c, businessId, 4, endedBy = ACTOR),
                    "contact bad role" to c.errorCodeOfUpdate(
                        "INSERT INTO business_contact (business_id, contact_role, name, email, phone_number, is_primary, display_order, effective_from, created_by) " +
                            "VALUES (?, 'INTERNAL', '담당', 'a@example.test', '01012345678', 0, 1, ?, ?)",
                        businessId, ts, ACTOR,
                    ),
                    "contact bad email" to contact(c, businessId, 2, 0, email = "nope"),
                    "contact bad phone" to contact(c, businessId, 3, 0, phone = "9999"),
                    "contact primary 2" to contact(c, businessId, 4, 2),
                )
            } shouldBe mapOf(
                "valid" to null, "unknown type" to 3819, "order 0" to 3819, "order 6" to 3819, "ended before start" to 3819, "ended without actor" to 3819,
                "actor without end" to 3819, "contact bad role" to 3819, "contact bad email" to 3819, "contact bad phone" to 3819, "contact primary 2" to 3819,
            )
        }

        Then("current-row generated UNIQUE keys (mutation (d)): one current primary contact, one current row per order, one address per type, one current category name per type") {
            runtime { c ->
                val businessId = MasterSchemaRows.business(c)
                val registrationId = MasterSchemaRows.businessRegistration(c, businessId)
                mapOf(
                    "first primary" to contact(c, businessId, 1, 1),
                    "second current primary" to contact(c, businessId, 2, 1),
                    "an ended primary and a non-primary row do not count" to listOf(contact(c, businessId, 3, 1, to = later), contact(c, businessId, 4, 0)).firstOrNull { it != null },
                    "same current order" to contact(c, businessId, 1, 0),
                    "representative order 1" to representative(c, businessId, 1),
                    "representative same current order" to representative(c, businessId, 1),
                    "ended representative keeps its order free" to representative(c, businessId, 2, to = later, endedBy = ACTOR),
                    "registered address" to address(c, registrationId, "REGISTERED"),
                    "second current registered address" to address(c, registrationId, "REGISTERED", postal = "04524"),
                    "actual address" to address(c, registrationId, "ACTUAL", postal = "04524"),
                    "business type 1" to category(c, registrationId, "BUSINESS_TYPE", "도소매", 1),
                    "same type same order" to category(c, registrationId, "BUSINESS_TYPE", "서비스", 1),
                    "same type same name" to category(c, registrationId, "BUSINESS_TYPE", "도소매", 2),
                    "item with the same name and order" to category(c, registrationId, "BUSINESS_ITEM", "도소매", 1),
                )
            } shouldBe mapOf(
                "first primary" to null, "second current primary" to 1062, "an ended primary and a non-primary row do not count" to null, "same current order" to 1062,
                "representative order 1" to null, "representative same current order" to 1062, "ended representative keeps its order free" to null,
                "registered address" to null, "second current registered address" to 1062, "actual address" to null,
                "business type 1" to null, "same type same order" to 1062, "same type same name" to 1062, "item with the same name and order" to null,
            )
        }

        Then("BIZ-1-6 the runtime account cannot DELETE from the business profile tables (1142) and has no column-level grants there") {
            val tables = listOf("business_registration", "business_representative", "business_category", "business_address", "business_contact")
            runtime { c -> tables.associateWith { c.errorCodeOf("DELETE FROM $it WHERE 1 = 0") } } shouldBe tables.associateWith { 1142 }
            root { c ->
                c.column("SELECT COUNT(*) FROM mysql.columns_priv WHERE Db = 'paycore_master' AND Table_name LIKE 'business%'").single()
            } shouldBe "0"
        }

        Then("an ended row's content columns can still be updated by the table-level grant (L-10 — defended by the adapter, not the DB)") {
            runtime { c ->
                val businessId = MasterSchemaRows.business(c)
                representative(c, businessId, 1)
                c.update("UPDATE business_representative SET name = '변경' WHERE business_id = ?", businessId)
            } shouldBe 1
        }
    }
})
