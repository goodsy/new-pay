package com.paycore.admin.merchantops

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.merchantops.adapter.web.MerchantRegistrationController
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 가맹점 등록 HTTP(MERCHANT-OPS-002 §4.3·§6·§8 B1-1 — 실제 2단계 로그인 세션, 실제 MySQL 감사 행, MASTER는 실제 HTTP 대역 [StubMasterOpsServer]):
 * 역할별 3종(OPERATOR만 200), 중복 등록번호 응답·감사 어디에도 기존 businessId 없음(대역이 일부러 넣어 보낸 경우 포함 — fail-closed), CSRF 없는 POST 거부, 413·415(감사 없음),
 * 감사 표 등록번호 원문 0건, 403 brn 행렬(M-1), **응답 유실(M-2b — 대역이라 MASTER 효과는 단언하지 않는다)**: 첫 요청 read timeout → 503 `MASTER_OUTCOME_UNKNOWN` → 같은 submissionId 재전송 →
 * 대역이 받은 두 요청의 MASTER 키·상관 ID·명령 필드 동일, 감사 (REQUESTED, OUTCOME_UNKNOWN)·(REQUESTED, OK) 두 쌍이 같은 correlationId.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantRegistrationHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val json = JsonMapper.builder().build()
    var subSeq = System.nanoTime() % 1_000_000L
    fun sub() = "%032x".format(++subSeq)
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', correlation_id) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action IN ('MERCHANT_BUSINESS_REGISTER', 'MERCHANT_REGISTER', 'MERCHANT_SITE_REGISTER') ORDER BY event_id",
            actorId,
        )
    }
    fun register(client: AdminHttpClient, kind: String, body: String, xsrf: Boolean = true) =
        client.perform(post("/api/merchant-registrations/$kind").contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
    fun obj(vararg pairs: Pair<String, String>) = AdminHttpClient.json(*pairs)

    val brn = "7781234560"
    val biz = "biz_01JC0000000000000000000001"
    val mer = "mer_01JC0000000000000000000001"
    val existingCanary = "biz_01JC00000000000000000CANRY".replace("CANRY", "CNRY0")
    fun businessBody(s: String, number: String = brn) = obj("submissionId" to s, "registrationNumber" to number, "displayName" to "시험 사업자")
    // MERCHANT-CODE-001 MC-1: 가맹점 등록 본문에 `merchantCode` 필수.
    fun merchantBody(s: String, code: String = "shopabcd12") = obj("submissionId" to s, "businessId" to biz, "displayName" to "시험 가맹점", "merchantCode" to code)
    fun siteBody(s: String) = obj("submissionId" to s, "merchantId" to mer, "channelType" to "WEB", "channelIdentifier" to "demo-shop2.paycore.test")

    Given("roles (OPERATOR only — D-MOPS-006 ①)") {
        Then("VIEWER, APPROVER and SECURITY_ADMIN get 403 for all three kinds, each audited FORBIDDEN; MASTER is not called") {
            listOf(withRole(AdminRole.VIEWER), ready.bootstrapped(AdminRole.APPROVER), ready.bootstrapped(AdminRole.SECURITY_ADMIN)).forEach { account ->
                val client = loggedIn(account)
                val before = StubMasterOpsServer.received.size
                val s = sub()
                listOf("businesses" to businessBody(s), "merchants" to merchantBody(s), "merchant-sites" to siteBody(s)).forEach { (kind, body) ->
                    register(client, kind, body).let {
                        it.status shouldBe 403
                        it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                        it.getHeader("Cache-Control")!! shouldContain "no-store"
                    }
                }
                StubMasterOpsServer.received.size shouldBe before
                audits(account.account.id.value).map { it.substringBeforeLast('|') } shouldBe listOf(
                    "MERCHANT_BUSINESS_REGISTER|FORBIDDEN|sub=${s.take(8)};brn=778*******|-",
                    // MERCHANT-CODE-001 MC-2-2: 가맹점 등록 감사 detail에 코드(공개값).
                    "MERCHANT_REGISTER|FORBIDDEN|sub=${s.take(8)};code=shopabcd12|$biz",
                    "MERCHANT_SITE_REGISTER|FORBIDDEN|sub=${s.take(8)};channel=WEB|$mer",
                )
            }
        }
        Then("M-1 403 brn matrix: well-formed → masked, malformed number or strict-parse failure → INVALID") {
            val viewer = withRole(AdminRole.VIEWER)
            val client = loggedIn(viewer)
            register(client, "businesses", businessBody(sub(), number = "778-12-34560")).status shouldBe 403
            register(client, "businesses", """{"submissionId":"${sub()}","registrationNumber":$brn,"displayName":"x"}""").status shouldBe 403
            register(client, "businesses", """{"displayName":"x"}""").status shouldBe 400
            audits(viewer.account.id.value).map { it.split('|')[2].substringAfterLast(';') } shouldBe listOf("brn=INVALID", "brn=INVALID")
        }
        Then("an OPERATOR registers business → merchant → site (200, reference only on success); each MASTER call is signed, has no query, and is audited as REQUESTED then OK") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.BUSINESSES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("biz_01JC0000000000000000000011")))
            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("mer_01JC0000000000000000000011")))
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000011")))
            val s1 = sub()
            register(client, "businesses", businessBody(s1)).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"biz_01JC0000000000000000000011","replayed":false}"""
            }
            register(client, "merchants", merchantBody(sub())).contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"mer_01JC0000000000000000000011","replayed":false}"""
            val s3 = sub()
            register(client, "merchant-sites", siteBody(s3)).contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"site_01JC0000000000000000000011","replayed":false}"""
            listOf(StubMasterOpsServer.BUSINESSES, StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.SITES).forEach { p ->
                StubMasterOpsServer.requestsForPath(p).last().let { listOf(it.signatureValid, it.query) shouldBe listOf(true, null) }
            }
            json.readTree(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.BUSINESSES).last().body).get("actor").asString() shouldBe "admin:${operator.account.id.value}"
            val rows = audits(operator.account.id.value)
            rows.map { it.split('|').let { c -> c[0] + "|" + c[1] } } shouldBe listOf(
                "MERCHANT_BUSINESS_REGISTER|REQUESTED", "MERCHANT_BUSINESS_REGISTER|OK", "MERCHANT_REGISTER|REQUESTED", "MERCHANT_REGISTER|OK",
                "MERCHANT_SITE_REGISTER|REQUESTED", "MERCHANT_SITE_REGISTER|OK",
            )
            rows[1] shouldBe "MERCHANT_BUSINESS_REGISTER|OK|sub=${s1.take(8)};replayed=false;brn=778*******|biz_01JC0000000000000000000011|mos.$s1"
            rows[5] shouldBe "MERCHANT_SITE_REGISTER|OK|sub=${s3.take(8)};replayed=false;channel=WEB|site_01JC0000000000000000000011|mos.$s3"
        }

        Then("MC-1-9 merchant registration over HTTP: the signed MASTER body carries merchantCode; a missing or malformed code is 400 INVALID_INPUT without a MASTER call; MASTER TAKEN is a stored rejection") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("mer_01JC0000000000000000000011")))
            register(client, "merchants", merchantBody(sub(), code = "shopabcd12")).status shouldBe 200
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).last()
            sent.signatureValid shouldBe true
            json.readTree(sent.body).let { n -> listOf(n.get("businessId").asString(), n.get("displayName").asString(), n.get("merchantCode").asString()) } shouldBe
                listOf(biz, "시험 가맹점", "shopabcd12")
            val before = StubMasterOpsServer.received.size
            listOf(
                merchantBody(sub(), code = "SHOPABCD12"), merchantBody(sub(), code = "shop-abcd12"), merchantBody(sub(), code = "abcde"),
                obj("submissionId" to sub(), "businessId" to biz, "displayName" to "시험 가맹점"),
                """{"submissionId":"${sub()}","businessId":"$biz","displayName":"x","merchantCode":null}""",
            ).forEach { body -> withClue(body) { register(client, "merchants", body).let { (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT"}""") } } }
            StubMasterOpsServer.received.size shouldBe before
            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("MERCHANT_CODE_TAKEN")))
            register(client, "merchants", merchantBody(sub(), code = "shopabcd12")).contentAsString shouldBe """{"outcome":"REJECTED","rejection":"MERCHANT_CODE_TAKEN","replayed":false}"""
        }
    }

    Given("a duplicate registration number (D-MOPS-006 ③ — no existing businessId anywhere)") {
        Then("the response is REJECTED with the code only; a MASTER reply that smuggles a reference is unreadable (503 MASTER_OUTCOME_UNKNOWN); the id is in no response or audit row") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.BUSINESSES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("BUSINESS_REGISTRATION_NUMBER_DUPLICATE")))
            val first = register(client, "businesses", businessBody(sub()))
            first.status shouldBe 200
            first.contentAsString shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_REGISTRATION_NUMBER_DUPLICATE","replayed":false}"""
            StubMasterOpsServer.reply(
                StubMasterOpsServer.BUSINESSES,
                StubMasterOpsServer.Reply(200, """{"outcome":"REJECTED","rejection":"BUSINESS_REGISTRATION_NUMBER_DUPLICATE","reference":"$existingCanary","replayed":false}"""),
            )
            val smuggled = register(client, "businesses", businessBody(sub()))
            smuggled.status shouldBe 503
            smuggled.contentAsString shouldBe """{"error":"MASTER_OUTCOME_UNKNOWN"}"""
            (first.contentAsString + smuggled.contentAsString) shouldNotContain existingCanary
            val rows = audits(operator.account.id.value)
            rows.map { it.split('|')[1] } shouldBe listOf("REQUESTED", "REJECTED", "REQUESTED", "OUTCOME_UNKNOWN")
            rows[1].split('|')[3] shouldBe "-"
            AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT COUNT(*) FROM admin_audit_event WHERE CONCAT_WS('|', action, result_code, detail, target_ref, reason, correlation_id, target_actor_id, approval_id) LIKE ?",
                    "%CNRY0%",
                )
            } shouldBe listOf("0")
        }
        Then("no audit row of any column carries a plain registration number (control: the number really was sent to MASTER)") {
            String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.BUSINESSES).last().body, Charsets.UTF_8) shouldContain brn
            AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT COUNT(*) FROM admin_audit_event WHERE CONCAT_WS('|', action, result_code, detail, target_ref, reason, correlation_id, target_actor_id, approval_id, remote_ip) LIKE ?",
                    "%$brn%",
                )
            } shouldBe listOf("0")
        }
    }

    Given("requests refused before the use case") {
        Then("no CSRF token → 403; non-JSON → 415; over 16 KiB → 413; none audited and MASTER not called") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val before = StubMasterOpsServer.received.size
            register(client, "businesses", businessBody(sub()), xsrf = false).status shouldBe 403
            client.perform(post("/api/merchant-registrations/merchants").contentType(MediaType.TEXT_PLAIN).content(merchantBody(sub()))).let {
                it.status shouldBe 415
                it.contentAsString shouldBe """{"error":"UNSUPPORTED_MEDIA_TYPE"}"""
            }
            val prefix = """{"submissionId":"${sub()}","registrationNumber":"$brn","displayName":""""
            val over = prefix + "a".repeat(MerchantRegistrationController.MAX_BODY_BYTES + 1 - prefix.length - 2) + "\"}"
            register(client, "businesses", over).let {
                it.status shouldBe 413
                it.contentAsString shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
                it.contentAsString shouldNotContain brn
            }
            audits(operator.account.id.value).shouldBeEmpty()
            StubMasterOpsServer.received.size shouldBe before
        }
        Then("strict parsing: unknown, missing or duplicate keys, null and coerced types are 400 INVALID_INPUT without a MASTER call") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val before = StubMasterOpsServer.received.size
            val s = sub()
            listOf(
                "merchants" to """{"submissionId":"$s","businessId":"$biz","displayName":"x","extra":"y"}""",
                "merchants" to """{"submissionId":"$s","businessId":"$biz"}""",
                "merchants" to """{"submissionId":"$s","businessId":"$biz","displayName":"x","displayName":"y"}""",
                "merchant-sites" to """{"submissionId":"$s","merchantId":"$mer","channelType":null,"channelIdentifier":"a.b"}""",
                "merchant-sites" to """{"submissionId":1,"merchantId":"$mer","channelType":"WEB","channelIdentifier":"a.b"}""",
            ).forEach { (kind, body) -> withClue(body) { register(client, kind, body).contentAsString shouldBe """{"error":"INVALID_INPUT"}""" } }
            StubMasterOpsServer.received.size shouldBe before
            audits(operator.account.id.value).shouldBeEmpty()
        }
    }

    Given("B1-2 contract draft and service agreement over HTTP (§4.3·§8.1 B1-2-1·3·4)") {
        val ctr = "ctr_01JC0000000000000000000011"
        val site = "site_01JC0000000000000000000011"
        fun contractBody(s: String, sites: String = "[\"$site\"]", to: String = ",\"effectiveToDate\":\"2027-01-01\"") =
            """{"submissionId":"$s","partyBusinessId":"$biz","scopeMerchantId":"$mer","scopeSiteIds":$sites,"effectiveFromDate":"2026-11-09"$to}"""
        fun agreementBody(s: String) = obj("submissionId" to s, "contractId" to ctr, "serviceKind" to "CARD_PG_PAYMENT")
        fun b12Audits(actorId: String) = AdminDbAccounts.root(container).use {
            it.column(
                "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', correlation_id) FROM admin_audit_event " +
                    "WHERE actor_id = ? AND action IN ('MERCHANT_CONTRACT_REGISTER', 'MERCHANT_AGREEMENT_ADD') ORDER BY event_id",
                actorId,
            )
        }

        Then("an OPERATOR registers a contract (KST dates converted, C-7) and adds a CSA; two audit rows each with targetRef = contract / CSA id; other roles are 403 audited") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded(ctr)))
            StubMasterOpsServer.reply(StubMasterOpsServer.AGREEMENTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("csa_01JC0000000000000000000011")))
            val s1 = sub()
            register(client, "contracts", contractBody(s1)).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"$ctr","replayed":false}"""
            }
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACTS).last()
            sent.signatureValid shouldBe true
            json.readTree(sent.body).let { n ->
                listOf(n.get("effectiveFrom").asString(), n.get("effectiveTo").asString(), n.get("scopeSiteIds").get(0).asString(), n.get("correlationId").asString()) shouldBe
                    listOf("2026-11-08T15:00:00.000Z", "2026-12-31T15:00:00.000Z", site, "mos.$s1")
            }
            val s2 = sub()
            register(client, "service-agreements", agreementBody(s2)).contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"csa_01JC0000000000000000000011","replayed":false}"""
            b12Audits(operator.account.id.value) shouldBe listOf(
                "MERCHANT_CONTRACT_REGISTER|REQUESTED|sub=${s1.take(8)}|$mer|mos.$s1",
                "MERCHANT_CONTRACT_REGISTER|OK|sub=${s1.take(8)};replayed=false|$ctr|mos.$s1",
                "MERCHANT_AGREEMENT_ADD|REQUESTED|sub=${s2.take(8)}|$ctr|mos.$s2",
                "MERCHANT_AGREEMENT_ADD|OK|sub=${s2.take(8)};replayed=false|csa_01JC0000000000000000000011|mos.$s2",
            )

            val viewer = withRole(AdminRole.VIEWER)
            val viewerClient = loggedIn(viewer)
            val before = StubMasterOpsServer.received.size
            register(viewerClient, "contracts", contractBody(sub())).status shouldBe 403
            register(viewerClient, "service-agreements", agreementBody(sub())).status shouldBe 403
            StubMasterOpsServer.received.size shouldBe before
            b12Audits(viewer.account.id.value).map { it.split('|')[1] } shouldBe listOf("FORBIDDEN", "FORBIDDEN")
        }

        Then("a duplicate kind and a retroactive start are REJECTED with the code only; 51 scope sites, a bad date and a non-array site list are 400 without a MASTER call") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.AGREEMENTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("SERVICE_KIND_DUPLICATE")))
            register(client, "service-agreements", agreementBody(sub())).contentAsString shouldBe """{"outcome":"REJECTED","rejection":"SERVICE_KIND_DUPLICATE","replayed":false}"""
            StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("CONTRACT_START_RETROACTIVE")))
            register(client, "contracts", contractBody(sub(), to = "")).contentAsString shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":false}"""
            json.readTree(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACTS).last().body).has("effectiveTo") shouldBe false

            val before = StubMasterOpsServer.received.size
            val fiftyOne = (1..51).joinToString(",", "[", "]") { "\"site_01JC" + it.toString().padStart(22, '0') + "\"" }
            listOf(contractBody(sub(), sites = fiftyOne), contractBody(sub()).replace("2026-11-09", "2026-02-30"), contractBody(sub(), sites = "\"$site\"")).forEach { body ->
                withClue(body.take(80)) { register(client, "contracts", body).contentAsString shouldBe """{"error":"INVALID_INPUT"}""" }
            }
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("B2-1 contract revision over HTTP (MERCHANT-OPS-002 §9.2·§8.2 B2-1-4)") {
        val ctr = "ctr_01JC0000000000000000000021"
        fun revisionBody(s: String, from: String = "2026-11-09") =
            """{"submissionId":"$s","contractId":"$ctr","scopeMerchantId":"$mer","scopeSiteIds":[],"effectiveFromDate":"$from"}"""
        fun revisionAudits(actorId: String) = AdminDbAccounts.root(container).use {
            it.column(
                "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', correlation_id) FROM admin_audit_event " +
                    "WHERE actor_id = ? AND action = 'MERCHANT_CONTRACT_REVISION_REGISTER' ORDER BY event_id",
                actorId,
            )
        }

        Then("an OPERATOR registers a revision (signed MASTER call to contract-revisions, C-7 date); audit rows keep the contract id with rev=2; a retroactive start passes through REJECTED; VIEWER is 403 audited") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACT_REVISIONS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("$ctr#2")))
            val s1 = sub()
            register(client, "contract-revisions", revisionBody(s1)).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"$ctr#2","replayed":false}"""
            }
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISIONS).last()
            sent.signatureValid shouldBe true
            json.readTree(sent.body).let { n -> listOf(n.get("contractId").asString(), n.get("effectiveFrom").asString(), n.get("correlationId").asString()) } shouldBe
                listOf(ctr, "2026-11-08T15:00:00.000Z", "mos.$s1")

            StubMasterOpsServer.reply(
                StubMasterOpsServer.CONTRACT_REVISIONS,
                StubMasterOpsServer.Reply(200, """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":false}"""),
            )
            val s2 = sub()
            register(client, "contract-revisions", revisionBody(s2, from = "2020-01-01")).contentAsString shouldBe
                """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":false}"""
            revisionAudits(operator.account.id.value) shouldBe listOf(
                "MERCHANT_CONTRACT_REVISION_REGISTER|REQUESTED|sub=${s1.take(8)}|$ctr|mos.$s1",
                "MERCHANT_CONTRACT_REVISION_REGISTER|OK|sub=${s1.take(8)};replayed=false;rev=2|$ctr|mos.$s1",
                "MERCHANT_CONTRACT_REVISION_REGISTER|REQUESTED|sub=${s2.take(8)}|$ctr|mos.$s2",
                "MERCHANT_CONTRACT_REVISION_REGISTER|REJECTED|sub=${s2.take(8)};replayed=false;rejection=CONTRACT_START_RETROACTIVE|$ctr|mos.$s2",
            )

            val viewer = withRole(AdminRole.VIEWER)
            val before = StubMasterOpsServer.received.size
            val s3 = sub()
            register(loggedIn(viewer), "contract-revisions", revisionBody(s3)).status shouldBe 403
            StubMasterOpsServer.received.size shouldBe before
            revisionAudits(viewer.account.id.value).single() shouldStartWith "MERCHANT_CONTRACT_REVISION_REGISTER|FORBIDDEN|sub=${s3.take(8)}|$ctr|"
        }
    }

    Given("M-2b lost response (stub — MASTER effects are not asserted here)") {
        Then("a read timeout is 503 MASTER_OUTCOME_UNKNOWN; resending the same submissionId sends the same MASTER key, correlation id and command fields; audits are two pairs with one correlation id") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val s = sub()
            val before = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITES).size
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000012"), delayMillis = 3_000))
            register(client, "merchant-sites", siteBody(s)).let {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"MASTER_OUTCOME_UNKNOWN"}"""
            }
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000012", replayed = true)))
            register(client, "merchant-sites", siteBody(s)).contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"site_01JC0000000000000000000012","replayed":true}"""
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITES).drop(before)
            sent.size shouldBe 2
            val (a, b) = sent.map { json.readTree(it.body) }
            a shouldBe b
            a.get("idempotencyKey").asString() shouldBe "mo1.${operator.account.id.value.removePrefix("adm_")}.$s"
            a.get("correlationId").asString() shouldBe "mos.$s"
            sent.map { it.signatureValid } shouldBe listOf(true, true)
            audits(operator.account.id.value).map { it.split('|').let { c -> c[1] + "|" + c[4] } } shouldBe listOf(
                "REQUESTED|mos.$s", "OUTCOME_UNKNOWN|mos.$s", "REQUESTED|mos.$s", "OK|mos.$s",
            )
            // MASTER 키 원문은 감사 어디에도 없다.
            AdminDbAccounts.root(container).use {
                it.column("SELECT COUNT(*) FROM admin_audit_event WHERE CONCAT_WS('|', detail, target_ref, correlation_id) LIKE ?", "%mo1.%")
            } shouldBe listOf("0")
        }
    }
})
