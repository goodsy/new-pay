package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedContractRevisionApprovalCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.2 B2-2-2 / §9.3 본문 공통 필드: 승인 명령 본문 엄격 해석(단위). 키 = `mop1.<approvalId>`(C-17), `actor` 필드 금지, 행위자 정규형(소문자 ULID 거부),
 * MASTER `mop1` 재계산과 다른 지문 거부, evidenceRef 필수·형식(D-MOPS-008 ①), 모르는/중복 key·`null`·자료형 강제 변환 거부, revisionNo INT 범위(C-30). 위반은 모두 null(→ 400,
 * 값 없음). `mop1` **독립 벡터**(C-23): 아래 고정 입력의 hex는 Python `hashlib.sha256`으로 따로 계산한 값이다 — ADMIN 시험이 같은 입력·같은 hex를 쓴다.
 */
class MerchantApprovalCommandParserSpec : BehaviorSpec({

    val window = ApprovalEvidenceFixtures.DEMO_WINDOW
    val contractId = "ctr_01JCCT00000000000000000CT1"
    fun parse(body: String) = MerchantApprovalCommandParser.contractRevisionApproval(body.toByteArray(Charsets.UTF_8), window)

    Given("the mop1 independent vector (C-23 — same input and hex in the ADMIN tests)") {
        Then("the fixed canonical lines hash to the fixed hex; one evidenceRef character changes the fingerprint") {
            fun vector(evidenceRef: String) = ApprovalFingerprint.mop1(
                AdminOperation.APPROVE_CONTRACT_REVISION,
                "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",
                "admin:adm_01JCRQ0000000000000000REQ1",
                "admin:adm_01JCAP0000000000000000APR1",
                Instant.parse("2026-11-10T01:00:00.000Z"),
                Instant.parse("2026-11-10T02:00:00.000Z"),
                evidenceRef,
                listOf(contractId, "2"),
            )
            // 정규 문자열: "mop1\nApproveContractRevision\napr_01JCZ8V3N6Q2R4T7W9XB5D1F3H\nadmin:adm_01JCRQ0000000000000000REQ1\nadmin:adm_01JCAP0000000000000000APR1\n
            //               1794272400000\n1794276000000\nCONTRACT-2026/0001#p1\nctr_01JCCT00000000000000000CT1\n2"(끝 줄바꿈 없음)
            vector("CONTRACT-2026/0001#p1") shouldBe "mop1:f1e2434f5df65194d29af33187e5fb1d947d48d11a7cc444c5764a639c106695"
            vector("CONTRACT-2026/0001#p2") shouldBe "mop1:4ffc17ff7a0f5e2f1c1ddecc6ba4057683e3f57c6c89bf40d85723b295b4123d"
        }
    }

    Given("a well-formed contract-revision-approvals body") {
        Then("it parses into the approved command: actor = approvedBy (C-17), key mop1.<approvalId>, the command fields in §9.4 order") {
            val a = Approval()
            val command = parse(MerchantApprovalBodies.contractRevisionApproval(contractId, "2", a)).shouldNotBeNull()
            command.request.actor.value shouldBe a.approvedBy
            command.request.idempotencyKey.value shouldBe "mop1.${a.approvalId}"
            command.request.correlationId shouldBe "mop.${a.approvalId}"
            command.evidence.approvalId shouldBe a.approvalId
            command.evidence.requestedBy.value shouldBe a.requestedBy
            command.evidence.evidenceRef.value shouldBe a.evidenceRef
            command.evidence.approvedAt shouldBe Instant.parse(a.approvedAt)
            command.evidence.executeBefore shouldBe Instant.parse(a.executeBefore)
            command.evidence.fingerprint shouldBe a.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "2"))
            command.window shouldBe window
            command.contractId.value shouldBe contractId
            command.revisionNo shouldBe 2
            command.commandFields shouldBe listOf(contractId, "2")
        }

        Then("a stored requested=approved body still parses (judgment 1 is a stored rejection, not a 400 — §9.3)") {
            val a = Approval(approvedBy = ApprovalEvidenceFixtures.REQUESTER.value)
            parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)).shouldNotBeNull()
        }

        Then("revisionNo accepts 1..2147483647 written canonically (C-30)") {
            parse(MerchantApprovalBodies.contractRevisionApproval(contractId, "2147483647")).shouldNotBeNull().revisionNo shouldBe Int.MAX_VALUE
            listOf("0", "01", "2147483648", "9999999999", "-1", "1.0", " 1", "١").forEach { n ->
                withClue(n) { parse(MerchantApprovalBodies.contractRevisionApproval(contractId, n)).shouldBeNull() }
            }
        }

        Then("the same bytes are accepted by the deadline-confirmation parser and give the same command") {
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, "3")
            val execute = parse(body).shouldNotBeNull()
            val deadline = MerchantApprovalCommandParser.deadlineConfirmation(body.toByteArray(Charsets.UTF_8), window).shouldNotBeNull()
            (deadline as ApprovedContractRevisionApprovalCommand) shouldBe execute
        }
    }

    Given("malformed approval bodies (all → null, nothing echoed)") {
        val a = Approval()
        val fields = MerchantApprovalBodies.contractRevisionApprovalFields(contractId, "1", a)

        Then("idempotencyKey other than mop1.<approvalId> and correlationId other than mop.<approvalId> are refused (C-17)") {
            parse(MerchantApprovalBodies.with(fields, "idempotencyKey", str("mo1.${a.approvalId}"))).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "idempotencyKey", str("mop1." + ApprovalEvidenceFixtures.approvalId()))).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "correlationId", str("mos.${a.approvalId}"))).shouldBeNull()
        }

        Then("an actor field is refused (C-17 — MASTER sets the actor from approvedBy)") {
            parse(MerchantApprovalBodies.with(fields, "actor", str(a.approvedBy))).shouldBeNull()
        }

        Then("requestedBy/approvedBy outside the canonical admin:adm_<upper ULID> form are refused, including a lower case ULID") {
            listOf(
                a.requestedBy.lowercase(), "admin:" + a.requestedBy.removePrefix("admin:").lowercase(), "Admin:" + a.requestedBy.removePrefix("admin:"),
                "system:demo-seed", "admin:adm_01JC00000000000000000000I1", "admin:adm_short",
            ).forEach { actor ->
                withClue(actor) {
                    parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(requestedBy = actor))).shouldBeNull()
                    parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(approvedBy = actor))).shouldBeNull()
                }
            }
        }

        Then("an approvalFingerprint different from the MASTER recomputation is refused (C-23)") {
            val good = a.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "1"))
            val flipped = good.dropLast(1) + (if (good.last() == '0') '1' else '0')
            parse(MerchantApprovalBodies.with(fields, "approvalFingerprint", str(flipped))).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "approvalFingerprint", str(good.uppercase().replace("MOP1:", "mop1:")))).shouldBeNull()
            // 다른 operation·다른 명령 필드로 계산한 지문도 다르다.
            parse(MerchantApprovalBodies.with(fields, "approvalFingerprint", str(a.fingerprint(AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, listOf(contractId, "1"))))).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "approvalFingerprint", str(a.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "2"))))).shouldBeNull()
        }

        Then("evidenceRef missing or outside ^[A-Za-z0-9._:#/-]{1,128}$ (space, control character, 129 characters, empty) is refused; 128 characters parse (D-MOPS-008 ①)") {
            parse(MerchantApprovalBodies.without(fields, "evidenceRef")).shouldBeNull()
            listOf("has space", "tab\u0009x", "line\nfeed", "x".repeat(129), "", "한글").forEach { ref ->
                // 줄바꿈이 든 값은 지문 정규 문자열 자체가 될 수 없어(줄 구분자) 자리값 지문을 보낸다 — 거부 이유는 형식이다.
                val fingerprint = if ('\n' in ref) ApprovalEvidenceFixtures.PLACEHOLDER_FINGERPRINT else null
                withClue(ref.length) { parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(evidenceRef = ref), fingerprint = fingerprint)).shouldBeNull() }
            }
            parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(evidenceRef = "x".repeat(128)))).shouldNotBeNull()
        }

        Then("unknown keys, duplicate keys, null values, coerced types, missing fields and trailing tokens are refused") {
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)
            parse(MerchantApprovalBodies.with(fields, "extra", str("x"))).shouldBeNull()
            parse(body.dropLast(1) + ""","contractId":"$contractId"}""").shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "evidenceRef", "null")).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "revisionNo", "1")).shouldBeNull()
            parse(MerchantApprovalBodies.with(fields, "approvalId", "[\"${a.approvalId}\"]")).shouldBeNull()
            listOf("approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "approvalFingerprint", "contractId", "revisionNo", "correlationId", "idempotencyKey")
                .forEach { name -> withClue(name) { parse(MerchantApprovalBodies.without(fields, name)).shouldBeNull() } }
            parse("$body {}").shouldBeNull()
            parse("[]").shouldBeNull()
            parse("").shouldBeNull()
        }

        Then("approvedAt/executeBefore other than the canonical ms Z form are refused; approvalId and contractId formats are enforced") {
            listOf("2026-10-09T01:59:00Z", "2026-10-09T01:59:00.000+00:00", "2026-10-09T01:59:00.0000Z", "2026-13-09T01:59:00.000Z").forEach { t ->
                withClue(t) {
                    parse(MerchantApprovalBodies.with(fields, "approvedAt", str(t))).shouldBeNull()
                    parse(MerchantApprovalBodies.with(fields, "executeBefore", str(t))).shouldBeNull()
                }
            }
            parse(MerchantApprovalBodies.contractRevisionApproval("ctr_short", a = a)).shouldBeNull()
            parse(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(approvalId = "apr_01jc00000000000000000000a1"))).shouldBeNull()
        }
    }

    Given("B2-3 first-default-merchant-accounts body (§9.4 — merchantId, contractId, agreementId, revisionNo, primarySiteId)") {
        val mer = "mer_01JCME00000000000000000001"
        val csa = "csa_01JCCS00000000000000000CS1"
        val site = "site_01JCST00000000000000000ST1"
        fun parseMid(body: String) = MerchantApprovalCommandParser.firstDefaultIssuance(body.toByteArray(Charsets.UTF_8), window)

        Then("the independent mop1 vector for IssueFirstDefaultMerchantAccount (Python hashlib — same input and hex in the ADMIN tests)") {
            fun vector(evidenceRef: String) = ApprovalFingerprint.mop1(
                AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1",
                "admin:adm_01JCAP0000000000000000APR1", Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), evidenceRef,
                listOf(mer, contractId, csa, "1", site),
            )
            vector("MID-2026/0001#p1") shouldBe "mop1:5763462d2ae22f336190ba12a3f873d48651beaf391fd783c020032b96d82a89"
            vector("MID-2026/0001#p2") shouldBe "mop1:1121aa32a7f3aa3e4e50c22b8feba568d3d78d4cd224cf928a443f66027e9b34"
        }

        Then("it parses into the approved first-default command (actor = approver, key mop1.<id>, command fields in §9.4 order); the deadline confirmation accepts the same bytes") {
            val a = Approval()
            val body = MerchantApprovalBodies.firstDefaultIssuance(mer, contractId, csa, site, a = a)
            val command = parseMid(body).shouldNotBeNull()
            listOf(command.request.actor.value, command.request.idempotencyKey.value, command.merchantId.value, command.agreementId.value, command.primarySiteId.value) shouldBe
                listOf(a.approvedBy, "mop1.${a.approvalId}", mer, csa, site)
            command.operation shouldBe AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT
            command.commandFields shouldBe listOf(mer, contractId, csa, "1", site)
            MerchantApprovalCommandParser.deadlineConfirmation(body.toByteArray(), window).shouldNotBeNull().operation shouldBe AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT
            // 계약 승인 해석기는 MID 본문을 받지 않는다(반대도 같다).
            parse(body).shouldBeNull()
            parseMid(MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)).shouldBeNull()
        }

        Then("a fingerprint computed for the other operation, a bad id, an actor field or revisionNo out of INT range is refused") {
            val a = Approval()
            val fields = MerchantApprovalBodies.firstDefaultIssuanceFields(mer, contractId, csa, site, a = a)
            listOf(
                MerchantApprovalBodies.firstDefaultIssuance(mer, contractId, csa, site, a = a, fingerprint = a.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(mer, contractId, csa, "1", site))),
                MerchantApprovalBodies.firstDefaultIssuance("mer_x", contractId, csa, site, a = a),
                MerchantApprovalBodies.firstDefaultIssuance(mer, contractId, "csa_x", site, a = a),
                MerchantApprovalBodies.firstDefaultIssuance(mer, contractId, csa, "site_x", a = a),
                MerchantApprovalBodies.firstDefaultIssuance(mer, contractId, csa, site, revisionNo = "2147483648", a = a),
                MerchantApprovalBodies.with(fields, "actor", str(a.approvedBy)),
                MerchantApprovalBodies.without(fields, "primarySiteId"),
            ).forEach { body -> withClue(body) { parseMid(body).shouldBeNull() } }
        }
    }

    Given("the approval-decisions body (approvalId + correlationId only)") {
        Then("a well-formed body gives the approval id; any key, fingerprint or evidence field, a bad id or a null is refused") {
            val id = ApprovalEvidenceFixtures.approvalId()
            MerchantApprovalCommandParser.decisionApprovalId(MerchantApprovalBodies.decision(id).toByteArray()) shouldBe id
            listOf(
                MerchantApprovalBodies.decision(id).dropLast(1) + ""","idempotencyKey":"mop1.$id"}""",
                """{"approvalId":"$id"}""",
                """{"approvalId":"$id","correlationId":null}""",
                MerchantApprovalBodies.decision("apr_bad"),
                MerchantApprovalBodies.decision(id, correlationId = "has space"),
            ).forEach { body -> withClue(body) { MerchantApprovalCommandParser.decisionApprovalId(body.toByteArray()).shouldBeNull() } }
        }

        Then("different evidence references give different commands' fingerprints (control for the 409 path)") {
            val x = Approval()
            x.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "1")) shouldNotBe
                x.copy(evidenceRef = x.evidenceRef + "x").fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "1"))
        }
    }
})
