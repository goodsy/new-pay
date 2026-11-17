package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.errorCodeOf
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.ACTOR
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows.t0
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.Timestamp

/**
 * MASTER 업무 schema(V1)의 DB 제약을 실제 MySQL 8.4.11에서 **runtime 계정**(운영과 같은 표 단위 권한)으로 확인한다(MASTER-DOMAIN-001).
 * domain 검사와 별개로 DB가 같은 관계 규칙을 지키는지(이중 방어) 본다:
 * 같은 Merchant(구조 §8), 승인된 Revision만 발급 근거(초기·INV-17), Merchant당 Default 하나(초기), 열린 PRIMARY 하나(D-MD-007),
 * 같은 MID–Site 열린 연결 하나·소급 시작 금지(D-MD-005), 사업자등록번호 하나·활성 채널 식별값 하나(D-MD-008), CSA 서비스 종류 하나(D-MD-002),
 * Scope Merchant = 당사자 Business 소속(D-MD-004).
 * 오류 코드: 1062 중복, 1452 FK 위반, 3819 CHECK 위반, 1142 표 명령 권한 없음.
 */
@MasterDbTest
class MasterSchemaSpec(private val container: MySQLContainer) : BehaviorSpec({

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun <T> root(block: (Connection) -> T): T = MasterDbAccounts.root(container).use(block)

    // 기대값 변경(MERCHANT-OPS-002 B2-2 V9, 명세 §9.3·§8.2 B2-2-4): 새 업무 표 `admin_approval_evidence`(INSERT-only 승인 근거) — 아래 DELETE 1142 검사 대상에도 들어간다.
    val businessTables = listOf(
        // 기대값 변경(BUSINESS-PROFILE-001 V15 — 목록 증가, 명세 §5.2): 사업자 등록정보·대표자·업태/종목·주소·등록 담당자 표 — DELETE 1142 검사 대상에도 들어간다.
        "admin_approval_evidence", "admin_request", "business", "business_address", "business_category", "business_contact", "business_registration",
        "business_representative", "card_route_activation", "card_route_activation_event", "contract", "contract_revision",
        "contract_revision_approval", "contract_revision_scope_site", "contract_service_agreement", "currency_enablement", "currency_unit",
        "default_designation", "entitlement_snapshot_ack", "entitlement_snapshot_issue", "master_change_history", "merchant", "merchant_account",
        // 기대값 변경(MERCHANT-CODE-001 MC-1 V13): 새 업무 표 `merchant_code_assignment`(append-only 코드 배정 이력) — DELETE 1142 검사 대상에도 들어간다(표 목록 증가, 완화 아님).
        "merchant_account_site_assignment", "merchant_code_assignment", "merchant_site", "merchant_site_review_decision", "outbox_event", "payment_method_activation",
        "payment_method_activation_event", "policy_assignment", "policy_version", "site_activation_confirmation",
        "site_activation_confirmation_revocation", "transaction_limit_policy",
    )

    Given("the MASTER business schema (V1 + V3 MASTER-SNAPSHOT-001)") {
        Then("all business tables exist") {
            root { c ->
                c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'paycore_master' " +
                        "AND table_name NOT IN ('flyway_schema_history', 'tech_session_probe') ORDER BY table_name",
                )
            } shouldBe businessTables
        }

        Then("every string column and table uses utf8mb4_0900_bin (identifiers compare as entered, no server default collation)") {
            root { c ->
                c.column(
                    "SELECT CONCAT(table_name, '.', column_name, '=', IFNULL(collation_name, '')) FROM information_schema.columns " +
                        "WHERE table_schema = 'paycore_master' AND data_type IN ('varchar', 'char') " +
                        "AND table_name NOT IN ('flyway_schema_history', 'tech_session_probe') " +
                        "AND collation_name <> 'utf8mb4_0900_bin'",
                ) + c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'paycore_master' " +
                        "AND table_name NOT IN ('flyway_schema_history', 'tech_session_probe') AND table_collation <> 'utf8mb4_0900_bin'",
                )
            } shouldBe emptyList()
        }

        Then("a consistent graph (business, merchant, sites, approved revision, agreement, MID, Default, PRIMARY) is accepted by the runtime account") {
            runtime { c -> MasterSchemaRows.graph(c) }
        }
    }

    Given("MID and Site of different Merchants (structure §8)") {
        Then("an assignment linking a MID to another Merchant's Site is rejected by the composite FKs (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val otherMerchant = MasterSchemaRows.merchant(c, g.businessId)
                val otherSite = MasterSchemaRows.site(c, otherMerchant)
                mapOf(
                    "claimed as MID merchant" to c.errorCodeOfUpdate(
                        INSERT_ASSIGNMENT, MasterSchemaRows.id("asg"), g.accountId, otherSite, g.merchantId, "ADDITIONAL",
                    ),
                    "claimed as site merchant" to c.errorCodeOfUpdate(
                        INSERT_ASSIGNMENT, MasterSchemaRows.id("asg"), g.accountId, otherSite, otherMerchant, "ADDITIONAL",
                    ),
                )
            } shouldBe mapOf("claimed as MID merchant" to 1452, "claimed as site merchant" to 1452)
        }

        Then("a Default designation pointing at another Merchant's MID is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val otherMerchant = MasterSchemaRows.merchant(c, g.businessId)
                c.errorCodeOfUpdate(INSERT_DEFAULT, otherMerchant, g.accountId)
            } shouldBe 1452
        }

        Then("a MID of a Merchant whose Business is not the contract party is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val strangerBusiness = MasterSchemaRows.business(c)
                val strangerMerchant = MasterSchemaRows.merchant(c, strangerBusiness)
                c.errorCodeOfUpdate(
                    INSERT_ACCOUNT, MasterSchemaRows.id("mid"), strangerMerchant, strangerBusiness, g.contractId, g.agreementId, 1,
                )
            } shouldBe 1452
        }
    }

    Given("the issuing basis of a MID (initial decisions, A10 INV-17)") {
        Then("a MID issued on a registered but not approved Revision is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                MasterSchemaRows.revision(c, g.contractId, 2, g.businessId, g.merchantId)
                c.errorCodeOfUpdate(INSERT_ACCOUNT, MasterSchemaRows.id("mid"), g.merchantId, g.businessId, g.contractId, g.agreementId, 2)
            } shouldBe 1452
        }

        Then("a MID issued with an agreement of another contract is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val otherContract = MasterSchemaRows.contract(c, g.businessId, g.merchantId)
                MasterSchemaRows.revision(c, otherContract, 1, g.businessId, g.merchantId)
                MasterSchemaRows.approve(c, otherContract, 1)
                c.errorCodeOfUpdate(INSERT_ACCOUNT, MasterSchemaRows.id("mid"), g.merchantId, g.businessId, otherContract, g.agreementId, 1)
            } shouldBe 1452
        }

        Then("a second Default for the same Merchant is rejected (1062)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val second = MasterSchemaRows.account(c, g.merchantId, g.businessId, g.contractId, g.agreementId, 1)
                c.errorCodeOfUpdate(INSERT_DEFAULT, g.merchantId, second)
            } shouldBe 1062
        }
    }

    Given("one target Merchant per contract (D-MD-013)") {
        Then("a revision scoped to another Merchant of the same Business is rejected by the contract-merchant FK (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val sibling = MasterSchemaRows.merchant(c, g.businessId)
                runCatching { MasterSchemaRows.revision(c, g.contractId, 2, g.businessId, sibling) }
                    .exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode }
            } shouldBe 1452
        }

        Then("a MID of another Merchant of the same Business citing the contract is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val sibling = MasterSchemaRows.merchant(c, g.businessId)
                c.errorCodeOfUpdate(INSERT_ACCOUNT, MasterSchemaRows.id("mid"), sibling, g.businessId, g.contractId, g.agreementId, 1)
            } shouldBe 1452
        }
    }

    Given("open site assignments (D-MD-005, D-MD-007)") {
        Then("a second open PRIMARY for the same MID is rejected (1062); an ADDITIONAL to another Site is accepted") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                mapOf(
                    "second primary" to c.errorCodeOfUpdate(
                        INSERT_ASSIGNMENT, MasterSchemaRows.id("asg"), g.accountId, g.secondSiteId, g.merchantId, "PRIMARY",
                    ),
                    "additional" to c.errorCodeOfUpdate(
                        INSERT_ASSIGNMENT, MasterSchemaRows.id("asg"), g.accountId, g.secondSiteId, g.merchantId, "ADDITIONAL",
                    ),
                )
            } shouldBe mapOf("second primary" to 1062, "additional" to null)
        }

        Then("a second open assignment of the same MID–Site pair is rejected (1062)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                c.errorCodeOfUpdate(INSERT_ASSIGNMENT, MasterSchemaRows.id("asg"), g.accountId, g.siteId, g.merchantId, "ADDITIONAL")
            } shouldBe 1062
        }

        Then("an ENDED assignment without an end instant is rejected (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                c.errorCodeOfUpdate(
                    "INSERT INTO merchant_account_site_assignment (assignment_id, merchant_account_id, merchant_site_id, merchant_id, role, status, " +
                        "effective_from, effective_to, created_at, created_by) " +
                        "VALUES (?, ?, ?, ?, 'ADDITIONAL', 'ENDED', '2026-10-09 00:00:00.000', NULL, '2026-10-09 00:00:00.000', 'schema-test')",
                    MasterSchemaRows.id("asg"), g.accountId, g.secondSiteId, g.merchantId,
                )
            } shouldBe 3819
        }

        Then("an assignment starting before it was recorded (retroactive) or with an empty period is rejected (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                mapOf(
                    "retroactive" to runCatching {
                        MasterSchemaRows.assignment(
                            c, g.accountId, g.secondSiteId, g.merchantId, role = "ADDITIONAL",
                            from = t0.minusMillis(1), createdAt = t0,
                        )
                    }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                    "empty period" to runCatching {
                        MasterSchemaRows.assignment(c, g.accountId, g.secondSiteId, g.merchantId, role = "ADDITIONAL", to = t0)
                    }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                )
            } shouldBe mapOf("retroactive" to 3819, "empty period" to 3819)
        }
    }

    Given("registration duplicates (D-MD-008)") {
        Then("the same business registration number twice is rejected (1062); a non 10-digit form is rejected (3819)") {
            runtime { c ->
                val regNo = MasterSchemaRows.registrationNumber()
                MasterSchemaRows.business(c, regNo)
                mapOf(
                    "duplicate" to runCatching { MasterSchemaRows.business(c, regNo) }
                        .exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                    "hyphenated" to runCatching { MasterSchemaRows.business(c, regNo.substring(0, 3) + "-" + regNo.substring(3, 9)) }
                        .exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                )
            } shouldBe mapOf("duplicate" to 1062, "hyphenated" to 3819)
        }

        Then("an active Site channel identifier is unique within a Merchant and across Merchants (1062); upper case is rejected (3819)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val identifier = MasterSchemaRows.channel()
                MasterSchemaRows.site(c, g.merchantId, identifier)
                val otherMerchant = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c))
                fun codeOf(block: () -> Unit) = runCatching(block).exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode }
                mapOf(
                    "same merchant" to codeOf { MasterSchemaRows.site(c, g.merchantId, identifier) },
                    "other merchant" to codeOf { MasterSchemaRows.site(c, otherMerchant, identifier) },
                    "same value as APP is another channel" to codeOf { MasterSchemaRows.site(c, otherMerchant, identifier, type = "APP") },
                    "upper case" to codeOf { MasterSchemaRows.site(c, otherMerchant, identifier.uppercase()) },
                    "WEB IPv4 literal (D-MD-014)" to codeOf { MasterSchemaRows.site(c, otherMerchant, "192.168.0.1") },
                    "WEB hex-number last label (review n-2)" to codeOf { MasterSchemaRows.site(c, otherMerchant, "a.0x7f") },
                    "WEB hex-number labels (review n-2)" to codeOf { MasterSchemaRows.site(c, otherMerchant, "0x7f.0x1") },
                )
            } shouldBe mapOf(
                "same merchant" to 1062, "other merchant" to 1062, "same value as APP is another channel" to null, "upper case" to 3819,
                "WEB IPv4 literal (D-MD-014)" to 3819,
                "WEB hex-number last label (review n-2)" to 3819, "WEB hex-number labels (review n-2)" to 3819,
            )
        }
    }

    Given("contract structure (D-MD-001, D-MD-002, D-MD-004)") {
        Then("a second agreement of the same service kind in one contract is rejected (1062)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                runCatching { MasterSchemaRows.agreement(c, g.contractId) }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode }
            } shouldBe 1062
        }

        Then("a revision scoped to a Merchant of another Business, or listing another Merchant's Site, is rejected (1452)") {
            runtime { c ->
                val g = MasterSchemaRows.graph(c)
                val strangerMerchant = MasterSchemaRows.merchant(c, MasterSchemaRows.business(c))
                val strangerSite = MasterSchemaRows.site(c, strangerMerchant)
                MasterSchemaRows.revision(c, g.contractId, 2, g.businessId, g.merchantId, scopeKind = "SITES")
                mapOf(
                    "scope merchant of another business" to runCatching {
                        MasterSchemaRows.revision(c, g.contractId, 3, g.businessId, strangerMerchant)
                    }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                    "scope site of another merchant" to c.errorCodeOfUpdate(
                        "INSERT INTO contract_revision_scope_site (contract_id, revision_no, merchant_site_id, merchant_id) VALUES (?, 2, ?, ?)",
                        g.contractId, strangerSite, g.merchantId,
                    ),
                    "own site" to c.errorCodeOfUpdate(
                        "INSERT INTO contract_revision_scope_site (contract_id, revision_no, merchant_site_id, merchant_id) VALUES (?, 2, ?, ?)",
                        g.contractId, g.siteId, g.merchantId,
                    ),
                )
            } shouldBe mapOf("scope merchant of another business" to 1452, "scope site of another merchant" to 1452, "own site" to null)
        }

        Then("identifiers outside the issued format are rejected (3819)") {
            runtime { c ->
                c.errorCodeOfUpdate(
                    // BUSINESS-PROFILE-001 V15: NOT NULL 새 열(기본값 없음)을 함께 넣는다 — 행 작성 열 목록만 갱신, 기대값(3819) 불변.
                    "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, " +
                        "registration_status, verification_status, updated_at, updated_by) " +
                        "VALUES ('biz_lowercase0000000000000000', ?, 'x', 'ACTIVE', ?, ?, 0, 'DRAFT', 'NOT_CHECKED', ?, ?)",
                    MasterSchemaRows.registrationNumber(), Timestamp.from(t0), ACTOR, Timestamp.from(t0), ACTOR,
                )
            } shouldBe 3819
        }
    }

    Given("append-only and no-delete tables under the runtime account (ARCHITECTURE-CONVENTIONS §9.2)") {
        // MASTER-SNAPSHOT-001: payment_method_activation(상태 전이, D-MS-003)·outbox_event(Relay, AC m-7)는 V4가 UPDATE를 받아 목록에서 빠지고
        // (MasterDatabaseAccountSpec 기대값), 새 append-only 표 6개가 들어온다.
        val insertOnly = listOf(
            "contract_revision", "contract_revision_scope_site", "contract_revision_approval", "contract_service_agreement",
            "merchant_site", "default_designation", "merchant_account_site_assignment", "master_change_history",
            "payment_method_activation_event", "site_activation_confirmation", "site_activation_confirmation_revocation",
            "merchant_site_review_decision", "entitlement_snapshot_issue", "entitlement_snapshot_ack",
            // MASTER-SNAPSHOT-002 V6: 정책 버전 등록부·카드사 개통 이력.
            "policy_version", "card_route_activation_event",
            // 기대값 변경(MERCHANT-OPS-002 B2-2 V10, 명세 §9.3·§8.2 B2-2-4): 승인 근거(append-only — SELECT, INSERT만).
            "admin_approval_evidence",
            // 기대값 변경(MERCHANT-CODE-001 MC-1 V14): 코드 배정 이력(append-only — SELECT, INSERT만).
            "merchant_code_assignment",
        )

        Then("UPDATE and locking reads on insert-only tables are denied (1142)") {
            runtime { c ->
                insertOnly.associateWith { table ->
                    listOf(
                        c.errorCodeOf("UPDATE $table SET ${firstColumn(table)} = ${firstColumn(table)} WHERE 1 = 0"),
                        c.errorCodeOf("SELECT * FROM $table WHERE 1 = 0 FOR UPDATE"),
                    )
                }
            } shouldBe insertOnly.associateWith { listOf(1142, 1142) }
        }

        Then("the currency unit reference table is read-only for the runtime account (INSERT/UPDATE denied 1142)") {
            runtime { c ->
                listOf(
                    c.errorCodeOf("INSERT INTO currency_unit (currency_code, minor_unit) VALUES ('EUR', 2)"),
                    c.errorCodeOf("UPDATE currency_unit SET minor_unit = 3 WHERE currency_code = 'KRW'"),
                )
            } shouldBe listOf(1142, 1142)
        }

        Then("DELETE is denied on every business table (1142)") {
            runtime { c -> businessTables.associateWith { c.errorCodeOf("DELETE FROM $it WHERE 1 = 0") } } shouldBe
                businessTables.associateWith { 1142 }
        }
    }

    Given("MERCHANT-OPS-002 B2-2 V9 admin_approval_evidence (§9.3 — CHECK 3819, PK/UNIQUE 1062, formats; runtime account)") {
        val requester = "admin:adm_01JC0000000000000000000RQ1"
        val approver = "admin:adm_01JC0000000000000000000AP1"
        val fingerprint = "mop1:" + "ab".repeat(32)

        /** 근거 행 INSERT(열을 바꿔 가며 제약을 시험한다). 성공이면 null, 실패면 MySQL 오류 코드. */
        @Suppress("LongParameterList")
        fun insert(
            c: Connection,
            approvalId: String = MasterSchemaRows.id("apr"),
            operation: String = "ApproveContractRevision",
            requestRecordId: String = java.util.UUID.randomUUID().toString(),
            requestedBy: String = requester,
            approvedBy: String = approver,
            approvedAt: String = "2026-10-09 01:59:00.000",
            executeBefore: String = "2026-10-09 02:59:00.000",
            // 직접 INSERT 표식(admin_request 없는 시험 행 — ApprovedCommandAtomicitySpec 불변식 n-a가 이 값으로 구별한다).
            evidenceRef: String = com.paycore.master.merchant.application.ApprovedCommandFixture.SCHEMA_TEST_EVIDENCE_REF,
            approvalFingerprint: String = fingerprint,
            outcome: String = "EXECUTED",
            resultCode: String? = null,
            resultReference: String? = "ctr_01JC0000000000000000000CT1#1",
            decidedVia: String? = "EXECUTE",
        ): Int? = c.errorCodeOfUpdate(
            "INSERT INTO admin_approval_evidence (approval_id, operation, request_record_id, requested_by, approved_by, approved_at, execute_before, evidence_ref, " +
                "approval_fingerprint, outcome, result_code, result_reference, decided_via, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-10-09 02:00:00.000')",
            approvalId, operation, requestRecordId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, approvalFingerprint, outcome, resultCode, resultReference,
            decidedVia,
        )

        Then("control: EXECUTED, REJECTED and DEADLINE_CONFIRM rows of the allowed shapes are accepted by the runtime account") {
            runtime { c ->
                listOf(
                    insert(c),
                    insert(c, outcome = "REJECTED", resultCode = "REVISION_ALREADY_APPROVED", resultReference = null),
                    insert(c, outcome = "REJECTED", resultCode = "APPROVAL_EXPIRED", resultReference = null, decidedVia = "DEADLINE_CONFIRM"),
                    insert(c, outcome = "REJECTED", resultCode = "APPROVAL_SAME_ACTOR", resultReference = null, approvedBy = requester, decidedVia = "DEADLINE_CONFIRM"),
                    insert(c, outcome = "REJECTED", resultCode = "APPROVAL_WINDOW_INVALID", resultReference = null, executeBefore = "2026-10-09 01:59:00.000"),
                    insert(c, operation = "IssueFirstDefaultMerchantAccount", resultReference = "mid_01JC0000000000000000000MD1"),
                )
            } shouldBe listOf(null, null, null, null, null, null)
        }

        Then("EXECUTED with the same actor or a deadline not after the approval is rejected by CHECK (3819 — DB defence line, §8.2 B2-2-3)") {
            runtime { c ->
                mapOf(
                    "executed same actor" to insert(c, approvedBy = requester),
                    "executed deadline = approval" to insert(c, executeBefore = "2026-10-09 01:59:00.000"),
                    "executed deadline before approval" to insert(c, executeBefore = "2026-10-09 01:58:59.999"),
                )
            } shouldBe mapOf("executed same actor" to 3819, "executed deadline = approval" to 3819, "executed deadline before approval" to 3819)
        }

        Then("outcome/code/reference consistency and the DEADLINE_CONFIRM code set are enforced (3819); decided_via other values 3819, NULL refused by NOT NULL (1048)") {
            runtime { c ->
                mapOf(
                    "executed with code" to insert(c, resultCode = "REVISION_ENDED"),
                    "executed without reference" to insert(c, resultReference = null),
                    "rejected without code" to insert(c, outcome = "REJECTED", resultReference = null),
                    "rejected with reference" to insert(c, outcome = "REJECTED", resultCode = "REVISION_ENDED"),
                    "deadline confirm with REVISION_ENDED (n-b/m-a)" to
                        insert(c, outcome = "REJECTED", resultCode = "REVISION_ENDED", resultReference = null, decidedVia = "DEADLINE_CONFIRM"),
                    "deadline confirm executed" to insert(c, decidedVia = "DEADLINE_CONFIRM"),
                    "decided_via other" to insert(c, decidedVia = "MANUAL"),
                    "decided_via NULL" to insert(c, decidedVia = null),
                    "unknown outcome" to insert(c, outcome = "UNKNOWN"),
                    "unknown operation" to insert(c, operation = "RegisterBusiness"),
                )
            } shouldBe mapOf(
                "executed with code" to 3819, "executed without reference" to 3819, "rejected without code" to 3819, "rejected with reference" to 3819,
                "deadline confirm with REVISION_ENDED (n-b/m-a)" to 3819, "deadline confirm executed" to 3819, "decided_via other" to 3819,
                // NOT NULL 열(명세 n-b "열도 NOT NULL")은 CHECK보다 먼저 NULL을 거부한다 — 같은 거부, 오류 코드만 1048(명세 표기 3819와 다름 — 보고 대상).
                "decided_via NULL" to 1048,
                "unknown outcome" to 3819, "unknown operation" to 3819,
            )
        }

        Then("MERCHANT-OPS-002 B3 V11 (§9.22, C-42): the operation CHECK accepts exactly the nine approval operations; 1-person and other operations are 3819") {
            val allowed = listOf(
                "ApproveContractRevision", "IssueFirstDefaultMerchantAccount", "ApprovePaymentMethodActivation", "DecideSiteReview", "EnableCurrency",
                "ApproveCardRouteActivation", "ResumePaymentMethodActivation", "ResumeCardRouteActivation", "ConfirmSiteActivation",
            )
            runtime { c -> allowed.associateWith { insert(c, operation = it) } } shouldBe allowed.associateWith { null }
            val refused = listOf("RejectSiteReview", "RegisterPaymentMethodActivation", "SuspendPaymentMethodActivation", "EndCurrencyEnablement", "decideSiteReview")
            runtime { c -> refused.associateWith { insert(c, operation = it) } } shouldBe refused.associateWith { 3819 }
        }

        Then("formats: approval id, canonical actors (no lower case), evidence reference (space, empty), fingerprint (3819); 129 characters exceed the column (1406)") {
            runtime { c ->
                mapOf(
                    "approval id" to insert(c, approvalId = "apr_short"),
                    "lower case approval id" to insert(c, approvalId = MasterSchemaRows.id("apr").lowercase()),
                    "requested_by lower case" to insert(c, requestedBy = requester.lowercase()),
                    "approved_by namespace" to insert(c, approvedBy = "system:demo-seed"),
                    "evidence ref with space" to insert(c, evidenceRef = "has space"),
                    "evidence ref empty" to insert(c, evidenceRef = ""),
                    "fingerprint upper case" to insert(c, approvalFingerprint = "mop1:" + "AB".repeat(32)),
                    "fingerprint other tag" to insert(c, approvalFingerprint = "acd1:" + "ab".repeat(32)),
                    "evidence ref 129" to insert(c, evidenceRef = "x".repeat(129)),
                )
            } shouldBe mapOf(
                "approval id" to 3819, "lower case approval id" to 3819, "requested_by lower case" to 3819, "approved_by namespace" to 3819,
                "evidence ref with space" to 3819, "evidence ref empty" to 3819, "fingerprint upper case" to 3819, "fingerprint other tag" to 3819,
                "evidence ref 129" to 1406,
            )
        }

        Then("the same approval id twice (also under another operation) and the same request record id twice are rejected (1062)") {
            runtime { c ->
                val approvalId = MasterSchemaRows.id("apr")
                val record = java.util.UUID.randomUUID().toString()
                insert(c, approvalId = approvalId, requestRecordId = record) shouldBe null
                mapOf(
                    "same approval id, other operation" to insert(c, approvalId = approvalId, operation = "IssueFirstDefaultMerchantAccount"),
                    "same request record" to insert(c, requestRecordId = record),
                )
            } shouldBe mapOf("same approval id, other operation" to 1062, "same request record" to 1062)
        }
    }
}) {
    companion object {
        const val INSERT_ASSIGNMENT =
            "INSERT INTO merchant_account_site_assignment (assignment_id, merchant_account_id, merchant_site_id, merchant_id, role, status, " +
                "effective_from, effective_to, created_at, created_by) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE', '2026-10-09 00:00:00.000', NULL, '2026-10-09 00:00:00.000', 'schema-test')"

        const val INSERT_DEFAULT =
            "INSERT INTO default_designation (merchant_id, merchant_account_id, designated_at, designated_by) " +
                "VALUES (?, ?, '2026-10-09 00:00:00.000', 'schema-test')"

        const val INSERT_ACCOUNT =
            "INSERT INTO merchant_account (merchant_account_id, merchant_id, business_id, issuing_contract_id, issuing_agreement_id, " +
                "issuing_revision_no, lifecycle, issued_at, issued_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', '2026-10-09 00:00:00.000', 'schema-test', 0)"

        private val FIRST_COLUMNS = mapOf(
            "contract_revision" to "registered_by",
            "contract_revision_scope_site" to "merchant_id",
            "contract_revision_approval" to "approved_by",
            "contract_service_agreement" to "added_by",
            "merchant_site" to "created_by",
            "default_designation" to "designated_by",
            "merchant_account_site_assignment" to "created_by",
            "master_change_history" to "actor_ref",
            "payment_method_activation_event" to "actor_ref",
            "site_activation_confirmation" to "confirmed_by",
            "site_activation_confirmation_revocation" to "revoked_by",
            "merchant_site_review_decision" to "decided_by",
            "entitlement_snapshot_issue" to "priority",
            "entitlement_snapshot_ack" to "consumer",
            "policy_version" to "registered_by",
            "card_route_activation_event" to "actor_ref",
            "admin_approval_evidence" to "approved_by",
            "merchant_code_assignment" to "assigned_by",
        )

        fun firstColumn(table: String): String = FIRST_COLUMNS.getValue(table)
    }
}
