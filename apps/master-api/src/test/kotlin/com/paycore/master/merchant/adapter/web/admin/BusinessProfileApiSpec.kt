package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.JooqStatementRecorder
import com.paycore.master.merchant.application.MasterFaultInjector
import com.paycore.master.merchant.application.MasterFaultPoint
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessContractEventCommand
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.BusinessFieldErrorCode
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessProfileRuleViolation
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * BUSINESS-PROFILE-001 BIZ-2(§6·§14 — 실제 보안 필터 체인 + MVC + MySQL 8.4, 공용 시험 컨텍스트에 MockMvc):
 * BIZ-2-1 채널(서명 401·`ADMIN_SERVICE` 체인 규칙·query 400·16 KiB 413·조회/명령 permit 독립 503·경로 ID 400), BIZ-2-2 create(TC-01·02·04·06 — 중복만 `existing`, C-12 대조),
 * BIZ-2-3 해석(REQUEST_MALFORMED·fieldErrors TC-03·09·11, `admin_request` 0행, 최대 본문 HTTP 성공), BIZ-2-4 HTTP 멱등(재생·409·existing 요약 실패 503 → 재전송 재생),
 * BIZ-2-6 등록 완료(M-1), BIZ-2-7 조회 endpoint 모양, BIZ-2-8 비노출(canary), BIZ-2-11 규칙 위반 경계(`BusinessProfileRuleViolation`만 400, 저장된 거절이 먼저).
 * 공유 DB이므로 시험마다 사업자등록번호·키를 새로 만든다. 기대값은 명세 리터럴(구현 상수와 독립).
 */
@MasterDbTest
class BusinessProfileApiSpec(
    context: WebApplicationContext,
    private val mappings: List<RequestMappingHandlerMapping>,
    private val chains: List<SecurityFilterChain>,
    private val container: MySQLContainer,
    private val permits: AdminChannelPermits,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
    private val recorder: JooqStatementRecorder,
    private val contracts: BusinessContractCommandUseCase,
    private val intakes: ContractIntakeCommandUseCase,
) : BehaviorSpec({

    beforeSpec { faults.reset(); clock.reset() }
    afterAny { faults.reset(); clock.reset(); recorder.recording = false }

    /** 리뷰 MINOR-1: `registration_number`의 모든 등장이 마스킹 함수(`LEFT(`) 안이거나 바인드 비교(`` = ?``)인가 — 원문 SELECT면 false. */
    fun numberOnlyMaskedOrBound(statement: String): Boolean {
        val token = "`registration_number`"
        var i = statement.indexOf(token)
        while (i >= 0) {
            val masked = Regex("(?i)left\\((`[A-Za-z0-9_]+`\\.)*$").containsMatchIn(statement.substring(maxOf(0, i - 80), i))
            if (!masked && !statement.startsWith(" = ?", i + token.length)) return false
            i = statement.indexOf(token, i + token.length)
        }
        return true
    }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String, header: String? = MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))): MvcResult {
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body.toByteArray(Charsets.UTF_8))
        return mvc.perform(if (header == null) b else b.header(AdminCallSignature.HEADER, header)).andReturn()
    }
    fun get(path: String, header: String? = MasterAdminChannelFixture.springHeader("GET", path)): MvcResult {
        val b = MockMvcRequestBuilders.get(path)
        return mvc.perform(if (header == null) b else b.header(AdminCallSignature.HEADER, header)).andReturn()
    }
    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun MvcResult.tree(): JsonNode = json.readTree(body())
    fun JsonNode.keys(): List<String> = propertyNames().toList()
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun adminRows(key: String) = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key)
    fun number() = BusinessFixtures.registrationNumber().value

    /** 새 사업자(HTTP create — 성공 단언) → businessId. */
    fun created(status: String = "REGISTERED", content: Map<String, Any?> = BusinessBodies.individual(), n: String = number()): String {
        val r = post(BusinessBodies.CREATE, BusinessBodies.create(status = status, number = n, content = content))
        withClue(r.body()) { r.response.status shouldBe 200 }
        r.tree().get("outcome").asString() shouldBe "SUCCEEDED"
        return r.tree().get("reference").asString()
    }
    fun versionOf(businessId: String): Long = get(BusinessBodies.detail(businessId)).tree().get("business").get("version").asLong()

    val allPaths = listOf(
        "POST" to BusinessBodies.SEARCHES, "POST" to BusinessBodies.LOOKUPS, "GET" to BusinessBodies.detail("biz_01JC0000000000000000000001"),
        "GET" to BusinessBodies.history("biz_01JC0000000000000000000001"), "POST" to BusinessBodies.CREATE, "POST" to BusinessBodies.UPDATE, "POST" to BusinessBodies.COMPLETE,
        // 기대값 변경(MERCHANT-FLOW-001 FLOW-1 — 목록 증가): '계약 진행' 섹션 조회도 같은 서명 채널·401·403 규칙 대상.
        "GET" to BusinessBodies.contractIntakes("biz_01JC0000000000000000000001"),
    )

    Given("BIZ-2-1 the signed admin channel in front of the business endpoints") {
        // 기대값 변경(MERCHANT-FLOW-001 FLOW-1 — 목록 증가): §6.1의 7개 + 사업자 상세 '계약 진행' 섹션 조회(읽기 전용) 1개.
        Then("the handlers under /internal/v1/admin/businesses are exactly the seven endpoints of §6.1 plus the FLOW-1 contract-intake section") {
            val routes = mappings.flatMap { it.handlerMethods.keys }.flatMap { info ->
                info.patternValues.filter { it.startsWith("/internal/v1/admin/businesses") }.flatMap { p -> info.methodsCondition.methods.map { "${it.name} $p" } }
            }.toSet()
            routes shouldBe setOf(
                "POST /internal/v1/admin/businesses/searches",
                "POST /internal/v1/admin/businesses/registration-number-lookups",
                "GET /internal/v1/admin/businesses/{businessId}",
                "GET /internal/v1/admin/businesses/{businessId}/history/after/{seq}",
                "GET /internal/v1/admin/businesses/{businessId}/contract-intakes",
                // 기대값 변경(BIZ-CRIT-APPROVAL-001 S2 §5.1 — 목록 증가, 완화 아님): 중요정보 변경 제안 명령 1개 + 조회 4개(A 단계 상태·제안·원문·1인 근거).
                "POST /internal/v1/admin/businesses/commands/critical-change-proposals",
                "GET /internal/v1/admin/businesses/{businessId}/approval-state",
                "GET /internal/v1/admin/businesses/{businessId}/critical-change-proposals/{proposalId}",
                "GET /internal/v1/admin/businesses/{businessId}/critical-change-proposals/{proposalId}/original",
                "GET /internal/v1/admin/businesses/{businessId}/business-change-evidence/{historyId}",
                "POST /internal/v1/admin/businesses/commands/create",
                "POST /internal/v1/admin/businesses/commands/update",
                "POST /internal/v1/admin/businesses/commands/complete-registration",
                // 기대값 변경(MERCHANT-FLOW-001 FLOW-2b §4B.7 — 목록 증가): 계약 접수 건 명령 3개(1인 OPERATOR, 이력 전용 — 완화 아님).
                "POST /internal/v1/admin/businesses/contract-intakes/commands/open",
                "POST /internal/v1/admin/businesses/contract-intakes/commands/move",
                "POST /internal/v1/admin/businesses/contract-intakes/commands/complete",
                // 기대값 변경(MERCHANT-FLOW-001 FLOW-2d §4B.7 — 목록 증가): 접수 항목 수정 명령 1개(1인 OPERATOR, 이력 전용 — 완화 아님).
                "POST /internal/v1/admin/businesses/contract-intakes/commands/update-items",
                // 기대값 변경(MERCHANT-FLOW-001 FLOW-2c §4B.7 — 목록 증가): 사업자 계약 정지·정지 해제·해지 명령 3개(1인 OPERATOR, 이력 전용 — 완화 아님).
                "POST /internal/v1/admin/businesses/business-contracts/commands/suspend",
                "POST /internal/v1/admin/businesses/business-contracts/commands/resume",
                "POST /internal/v1/admin/businesses/business-contracts/commands/terminate",
            )
        }

        Then("unsigned, foreign-key and stale calls are 401 UNAUTHENTICATED on every endpoint without reaching the use case (no admin_request row)") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val body = BusinessBodies.create(number = number())
            allPaths.forEach { (method, path) ->
                val bytes = if (method == "POST") body.toByteArray(Charsets.UTF_8) else ByteArray(0)
                listOf(
                    null,
                    MasterAdminChannelFixture.header(method, path, bytes),
                    MasterAdminChannelFixture.springHeader(method, path, bytes, t = java.time.Instant.now().epochSecond - 400),
                ).forEach { header ->
                    val r = if (method == "POST") post(path, body, header) else get(path, header)
                    withClue("$method $path") {
                        r.response.status shouldBe 401
                        r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                    }
                }
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }

        Then("403 defence in depth: the admin chain matches the business paths and its rule grants only ADMIN_SERVICE") {
            fun request(method: String, path: String) = MockHttpServletRequest(method, path).apply { servletPath = path }
            fun AuthorizationFilter.granted(auth: Authentication, req: MockHttpServletRequest) = authorizationManager.authorize({ auth }, req)?.isGranted ?: false
            val admin = chains.single { chain -> chain.filters.any { it is AdminChannelAuthenticationFilter } }
            val rule = admin.filters.filterIsInstance<AuthorizationFilter>().single()
            val service = TestingAuthenticationToken("adm", "n/a", AdminChannelAuthenticationFilter.ADMIN_SERVICE)
            val someoneElse = TestingAuthenticationToken("svc", "n/a", "ROLE_USER", "ADMIN_SERVICE_X")
            allPaths.forEach { (method, path) ->
                withClue("$method $path") {
                    admin.matches(request(method, path)) shouldBe true
                    rule.granted(service, request(method, path)) shouldBe true
                    rule.granted(someoneElse, request(method, path)) shouldBe false
                }
            }
        }

        Then("a query string is 400 QUERY_NOT_ALLOWED before the signature; a body over 16 KiB is 413 REQUEST_TOO_LARGE (no row)") {
            val path = BusinessBodies.detail("biz_01JC0000000000000000000001")
            val q = mvc.perform(MockMvcRequestBuilders.get("$path?x=1").header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()
            q.response.status shouldBe 400
            q.body() shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
            val before = count("SELECT COUNT(*) FROM admin_request")
            val big = BusinessBodies.create(number = number(), content = BusinessBodies.individual(name = "x")).dropLast(1) + ",\"pad\":\"" + "a".repeat(16 * 1024) + "\"}"
            val r = post(BusinessBodies.CREATE, big)
            r.response.status shouldBe 413
            r.body() shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }

        Then("path id format violations are 400 REQUEST_MALFORMED (no value echoed); an unknown well-formed id is 404 BUSINESS_NOT_FOUND") {
            listOf(BusinessBodies.detail("mer_01JC0000000000000000000001"), BusinessBodies.detail("biz_short"), BusinessBodies.history("biz_lower0000000000000000000000"))
                .forEach { p -> withClue(p) { get(p).body() shouldBe """{"error":"REQUEST_MALFORMED"}""" } }
            val badSeq = "/internal/v1/admin/businesses/biz_01JC0000000000000000000001/history/after/x1"
            get(badSeq).response.status shouldBe 400
            val nf = get(BusinessBodies.detail("biz_01JC0000000000000000000000"))
            nf.response.status shouldBe 404
            nf.body() shouldBe """{"error":"BUSINESS_NOT_FOUND"}"""
            get(BusinessBodies.history("biz_01JC0000000000000000000000")).body() shouldBe """{"error":"BUSINESS_NOT_FOUND"}"""
        }

        Then("read permits exhausted → 503 ADMIN_READ_BUSY at once; write permits exhausted → 503 ADMIN_WRITE_BUSY with no row while a read still runs (independent bulkheads)") {
            fun holdAll(n: Int, acquire: (() -> Unit) -> Unit, block: () -> Unit) {
                val held = CountDownLatch(n)
                val release = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(n)
                try {
                    repeat(n) { pool.submit { acquire { held.countDown(); release.await(10, TimeUnit.SECONDS) } } }
                    held.await(5, TimeUnit.SECONDS) shouldBe true
                    block()
                } finally {
                    release.countDown()
                    pool.shutdown()
                    pool.awaitTermination(10, TimeUnit.SECONDS)
                }
            }
            holdAll(MasterAdminChannelFixture.springSettings().readPermits, { b -> permits.read("hold", { error("permit expected") }) { b() } }) {
                post(BusinessBodies.SEARCHES, "{}").body() shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                get(BusinessBodies.detail("biz_01JC0000000000000000000000")).body() shouldBe """{"error":"ADMIN_READ_BUSY"}"""
            }
            val before = count("SELECT COUNT(*) FROM admin_request")
            holdAll(MasterAdminChannelFixture.springSettings().writePermits, { b -> permits.write("hold", { error("permit expected") }) { b() } }) {
                val busy = post(BusinessBodies.CREATE, BusinessBodies.create(number = number()))
                busy.response.status shouldBe 503
                busy.body() shouldBe """{"error":"ADMIN_WRITE_BUSY"}"""
                post(BusinessBodies.SEARCHES, """{"limit":1}""").response.status shouldBe 200
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }
    }

    Given("BIZ-2-2 create through the signed channel") {
        Then("TC-04 DRAFT minimum, TC-01 individual REGISTERED and TC-02 corporation REGISTERED answer 200 SUCCEEDED with exactly outcome/reference/replayed") {
            listOf(
                Triple("DRAFT", BusinessBodies.draftMinimal(), "DRAFT"),
                Triple("REGISTERED", BusinessBodies.individual(), "REGISTERED"),
                Triple("REGISTERED", BusinessBodies.corporation(), "REGISTERED"),
            ).forEach { (status, content, expected) ->
                val r = post(BusinessBodies.CREATE, BusinessBodies.create(status = status, number = number(), content = content))
                r.response.status shouldBe 200
                r.response.getHeader("Cache-Control")!! shouldContain "no-store"
                r.tree().keys() shouldBe listOf("outcome", "reference", "replayed")
                val id = r.tree().get("reference").asString()
                id shouldMatch Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
                r.tree().get("replayed").asBoolean() shouldBe false
                val d = get(BusinessBodies.detail(id)).tree()
                d.get("business").get("registrationStatus").asString() shouldBe expected
                d.get("business").get("verificationStatus").asString() shouldBe "NOT_CHECKED"
            }
        }

        Then("TC-06 a duplicate number is 200 REJECTED with existing {businessId, businessName, registrationNumber, registrationStatus}; no second business") {
            val n = number()
            val first = created(status = "DRAFT", content = BusinessBodies.draftMinimal(name = "먼저 등록한 사업자"), n = n)
            val r = post(BusinessBodies.CREATE, BusinessBodies.create(number = n, content = BusinessBodies.individual(name = "나중 사업자")))
            r.response.status shouldBe 200
            val t = r.tree()
            t.keys() shouldBe listOf("outcome", "rejection", "replayed", "existing")
            t.get("rejection").asString() shouldBe "BUSINESS_REGISTRATION_NUMBER_DUPLICATE"
            t.get("existing").keys() shouldBe listOf("businessId", "businessName", "registrationNumber", "registrationStatus")
            t.get("existing").get("businessId").asString() shouldBe first
            t.get("existing").get("businessName").asString() shouldBe "먼저 등록한 사업자"
            // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): 조회 응답 번호는 저장 원문 10자리(응답 필드 `registrationNumber` — v2 계약, M-1). 중복 안내도 범위(D-PII-001 ①).
            t.get("existing").get("registrationNumber").asString() shouldBe n
            t.get("existing").get("registrationStatus").asString() shouldBe "DRAFT"
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", n) shouldBe 1L
        }

        Then("C-12 control: other rejections carry neither reference nor existing (the stored reference stays in admin_request only)") {
            val id = created()
            val s = Submission()
            val r = post(BusinessBodies.COMPLETE, BusinessBodies.complete(s, id, versionOf(id)))
            r.tree().keys() shouldBe listOf("outcome", "rejection", "replayed")
            r.tree().get("rejection").asString() shouldBe "BUSINESS_NOT_DRAFT"
            val stale = post(BusinessBodies.UPDATE, BusinessBodies.update(businessId = id, version = versionOf(id) + 5, content = BusinessBodies.individual(name = "다른 상호"), reason = "상호 변경"))
            stale.tree().keys() shouldBe listOf("outcome", "rejection", "replayed")
            stale.tree().get("rejection").asString() shouldBe "BUSINESS_VERSION_CONFLICT"
        }
    }

    Given("BIZ-2-3 request parsing through HTTP (nothing is stored)") {
        Then("structural violations are 400 REQUEST_MALFORMED; value violations are 400 BUSINESS_VALIDATION_FAILED with exact path·code fieldErrors (TC-03·09·11, create and complete)") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val base = BusinessBodies.createMap(number = number())
            listOf(
                BusinessBodies.with(base, "unknown", 1), BusinessBodies.with(base, "contacts", BusinessBodies.REMOVE), BusinessBodies.with(base, "businessType", null),
                BusinessBodies.with(base, "representatives", List(6) { BusinessBodies.representative("대표$it", "JOINT") }),
            ).forEach { m -> post(BusinessBodies.CREATE, BusinessBodies.json(m)).body() shouldBe """{"error":"REQUEST_MALFORMED"}""" }
            val id = created()
            post(BusinessBodies.UPDATE, BusinessBodies.json(BusinessBodies.with(BusinessBodies.updateMap(businessId = id, version = 1), "registrationNumber", number())))
                .body() shouldBe """{"error":"REQUEST_MALFORMED"}"""

            val noCorporate = BusinessBodies.with(
                BusinessBodies.corporation(), "registration", BusinessBodies.with(BusinessBodies.registration(englishName = "Demo Corp"), "corporateRegistrationNumber", null),
            )
            val cases = listOf(
                noCorporate to """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"registration.corporateRegistrationNumber","code":"REQUIRED"}]}""",
                BusinessBodies.with(BusinessBodies.individual(), "representatives", emptyList<Any>()) to
                    """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"representatives","code":"REQUIRED"}]}""",
                BusinessBodies.with(BusinessBodies.individual(), "contacts", emptyList<Any>()) to
                    """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"contacts","code":"REQUIRED"}]}""",
            )
            val draft = created(status = "DRAFT", content = BusinessBodies.draftMinimal())
            cases.forEach { (content, expected) ->
                post(BusinessBodies.CREATE, BusinessBodies.create(number = number(), content = content)).also { it.response.status shouldBe 400 }.body() shouldBe expected
                post(BusinessBodies.COMPLETE, BusinessBodies.complete(businessId = draft, version = versionOf(draft), content = content)).body() shouldBe expected
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before + 2 // 위에서 만든 두 사업자(created)의 성공 기록뿐
        }

        Then("MINOR-2 (E-10 ③ pinned): 11 business types + 9 items (20 rows — within the structural cap) is 400 BUSINESS_VALIDATION_FAILED [categories TOO_MANY], not stored") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val categories = List(11) { BusinessBodies.category("BUSINESS_TYPE", "업태$it") } + List(9) { BusinessBodies.category("BUSINESS_ITEM", "종목$it") }
            val r = post(BusinessBodies.CREATE, BusinessBodies.create(number = number(), content = BusinessBodies.content(categories = categories)))
            r.response.status shouldBe 400
            r.body() shouldBe """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"categories","code":"TOO_MANY"}]}"""
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }

        Then("the largest valid body (every list at its cap, 4-byte characters at maximum length) fits in 16 KiB and is stored (200 SUCCEEDED)") {
            val wide = "😀"
            fun text(n: Int, tag: Int = 0) = wide.repeat(n - 1) + ('가' + tag)
            val email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
            val body = BusinessBodies.create(
                number = number(),
                content = BusinessBodies.content(
                    type = "CORPORATION", name = text(100),
                    registration = BusinessBodies.registration(corporate = "1101110000001", englishName = "E".repeat(100), phone = "012345678901", email = email),
                    representatives = List(5) { BusinessBodies.representative(text(50, it), "JOINT") },
                    categories = List(20) { BusinessBodies.category(if (it < 10) "BUSINESS_TYPE" else "BUSINESS_ITEM", text(50, it)) },
                    addresses = listOf(
                        BusinessBodies.address("REGISTERED", "12345", text(200, 1), text(200, 2)), BusinessBodies.address("ACTUAL", "54321", text(200, 3), text(200, 4)),
                    ),
                    contacts = List(5) { BusinessBodies.contact(text(50, it), email, "012345678901", primary = it == 0) },
                ),
            )
            body.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual 16 * 1024
            val r = post(BusinessBodies.CREATE, body)
            withClue(r.body()) { r.response.status shouldBe 200 }
            val d = get(BusinessBodies.detail(r.tree().get("reference").asString())).tree()
            d.get("business").get("businessName").asString() shouldBe text(100)
            d.get("categories").size() shouldBe 20
        }
    }

    Given("BIZ-2-4 HTTP idempotency and the duplicate-summary failure (m-1)") {
        Then("same key + same body replays the first response with replayed=true; same key + different body is 409 IDEMPOTENCY_KEY_REUSED") {
            val s = Submission()
            val n = number()
            val body = BusinessBodies.create(s, number = n)
            val first = post(BusinessBodies.CREATE, body)
            val id = first.tree().get("reference").asString()
            post(BusinessBodies.CREATE, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":true}"""
            val reused = post(BusinessBodies.CREATE, BusinessBodies.create(s, number = n, content = BusinessBodies.individual(name = "다른 내용")))
            reused.response.status shouldBe 409
            reused.body() shouldBe """{"error":"IDEMPOTENCY_KEY_REUSED"}"""
            adminRows(s.idempotencyKey) shouldBe 1L
        }

        Then("m-1: the summary read fails after the duplicate rejection is stored → 503 TEMPORARILY_UNAVAILABLE without outcome/existing; resending the same key replays with existing") {
            val n = number()
            val existing = created(status = "DRAFT", content = BusinessBodies.draftMinimal(name = "기존 사업자"), n = n)
            val s = Submission()
            val body = BusinessBodies.create(s, number = n, content = BusinessBodies.individual(name = "중복 시도"))
            faults.after(MasterFaultPoint.BUSINESS_EXISTING_SUMMARY) { throw IllegalStateException("summary read failed (test)") }
            val failed = post(BusinessBodies.CREATE, body)
            failed.response.status shouldBe 503
            failed.body() shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
            OnboardingFixture.runtime(container).use { it.scalar("SELECT result_kind FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) } shouldBe "REJECTED"
            faults.reset()
            val businessesBefore = count("SELECT COUNT(*) FROM business")
            val retried = post(BusinessBodies.CREATE, body)
            retried.response.status shouldBe 200
            retried.tree().get("outcome").asString() shouldBe "REJECTED"
            retried.tree().get("replayed").asBoolean() shouldBe true
            retried.tree().get("existing").get("businessId").asString() shouldBe existing
            adminRows(s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM business") shouldBe businessesBefore
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", n) shouldBe 1L
        }
    }

    Given("MINOR-5 the duplicate summary is missing (stored reference without a business — a defect)") {
        Then("summary == null → 503 TEMPORARILY_UNAVAILABLE with neither outcome nor existing in the body; logged without values") {
            val reference = "biz_01JC0000000000000000000009"
            val duplicate = AdminCommandResult.Rejected(com.paycore.master.merchant.domain.MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, reference, false)
            var asked: String? = null
            val controller = BusinessCommandController(
                object : BusinessProfileCommandUseCase {
                    override fun create(command: CreateBusinessCommand): AdminCommandResult = duplicate
                    override fun update(command: UpdateBusinessCommand): AdminCommandResult = error("not used")
                    override fun completeRegistration(command: CompleteBusinessRegistrationCommand): AdminCommandResult = error("not used")
                },
                object : BusinessProfileQueryUseCase by unusedQuery() {
                    override fun existingSummary(businessId: com.paycore.master.merchant.domain.BusinessId) = null.also { asked = businessId.value }
                },
                AdminChannelPermits(MasterAdminChannelFixture.springSettings(), SimpleMeterRegistry()),
                SimpleMeterRegistry(),
                // BIZ-CRIT-APPROVAL-001 S2: 제안 명령 의존성(이 시험은 부르지 않는다 — 생성자 인자만 보강).
                unused(com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase::class.java),
                unused(com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery::class.java),
            )
            val lines = CapturedLogs().use { logs ->
                val r = controller.create(MockHttpServletRequest("POST", BusinessBodies.CREATE).apply { setContent(BusinessBodies.create(number = number()).toByteArray(Charsets.UTF_8)) })
                r.statusCode.value() shouldBe 503
                String(r.body!!, Charsets.UTF_8) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                logs.lines()
            }
            asked shouldBe reference
            lines.any { it.contains("stage=EXISTING_SUMMARY failure=MISSING") } shouldBe true
        }
    }

    Given("BIZ-2-6 complete-registration over HTTP (M-1 — the transition itself is the change)") {
        Then("DRAFT with the stored content + complete → 200 SUCCEEDED, detail REGISTERED with version + 1; another key again → REJECTED BUSINESS_NOT_DRAFT (no reference)") {
            val id = created(status = "DRAFT", content = BusinessBodies.individual(name = "완료 대상"))
            val before = get(BusinessBodies.detail(id)).tree().get("business")
            before.get("registrationStatus").asString() shouldBe "DRAFT"
            val v = before.get("version").asLong()
            val ok = post(BusinessBodies.COMPLETE, BusinessBodies.complete(businessId = id, version = v, content = BusinessBodies.individual(name = "완료 대상")))
            ok.body() shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}"""
            val after = get(BusinessBodies.detail(id)).tree().get("business")
            after.get("registrationStatus").asString() shouldBe "REGISTERED"
            after.get("version").asLong() shouldBe v + 1
            val again = post(BusinessBodies.COMPLETE, BusinessBodies.complete(businessId = id, version = v + 1, content = BusinessBodies.individual(name = "완료 대상")))
            again.body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_NOT_DRAFT","replayed":false}"""
            versionOf(id) shouldBe v + 1
        }
    }

    // BIZ-NORM-002 M-3(BR-BIZ-311, GOV-BIZ-001 G-03 — 전: 503 TEMPORARILY_UNAVAILABLE·admin_request 0행): 사용중지 행은 앱 경로가 없어 저장 행을 직접 바꾼다.
    Given("BIZ-NORM-002 M-3 an INACTIVE business over HTTP") {
        Then("update → 200 REJECTED BUSINESS_INACTIVE (no reference), same key replays (replayed=true), admin_request REJECTED 1 row, no history/Outbox, version unchanged") {
            val id = created(content = BusinessBodies.individual(name = "사용중지 대상"))
            OnboardingFixture.runtime(container).use { c ->
                c.prepareStatement("UPDATE business SET registration_status = 'INACTIVE' WHERE business_id = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() shouldBe 1 }
            }
            val v = versionOf(id)
            val history = count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id)
            val outbox = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", id)
            val s = Submission()
            val body = BusinessBodies.update(s, businessId = id, version = v, content = BusinessBodies.individual(name = "바꾼 상호"), reason = "상호 변경")
            val first = post(BusinessBodies.UPDATE, body)
            withClue(first.body()) { first.response.status shouldBe 200 }
            first.body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_INACTIVE","replayed":false}"""
            post(BusinessBodies.UPDATE, body).body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_INACTIVE","replayed":true}"""
            adminRows(s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'REJECTED' AND result_code = 'BUSINESS_INACTIVE'", s.idempotencyKey) shouldBe 1L
            listOf(
                count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id),
                count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", id),
                versionOf(id),
            ) shouldBe listOf(history, outbox, v)
            get(BusinessBodies.detail(id)).tree().get("business").get("registrationStatus").asString() shouldBe "INACTIVE"
        }

        Then("complete-registration → 200 REJECTED BUSINESS_NOT_DRAFT (unchanged — expected value fixed) and replayed with the same key") {
            val id = created(content = BusinessBodies.individual(name = "사용중지 완료 대상"))
            OnboardingFixture.runtime(container).use { c ->
                c.prepareStatement("UPDATE business SET registration_status = 'INACTIVE' WHERE business_id = ?").use { ps -> ps.setString(1, id); ps.executeUpdate() shouldBe 1 }
            }
            val s = Submission()
            val body = BusinessBodies.complete(s, businessId = id, version = versionOf(id), content = BusinessBodies.individual(name = "사용중지 완료 대상"))
            post(BusinessBodies.COMPLETE, body).body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_NOT_DRAFT","replayed":false}"""
            post(BusinessBodies.COMPLETE, body).body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_NOT_DRAFT","replayed":true}"""
        }
    }

    Given("MERCHANT-FLOW-001 FLOW-2d [rev 0.4f 후속]: the registration-number lookup carries the latest business contract status (display only — the registration gate stays final)") {
        fun status(n: String) = post(BusinessBodies.LOOKUPS, """{"registrationNumber":"$n"}""").tree().get("items").get(0).get("businessContractStatus")
        val reason = BusinessChangeReason("조회 시험")

        /** 신규 접수 → 계약서 접수 → 계약 완료(시작일·종료일 지정 — 효력 전·만료 상태를 만든다). */
        fun contracted(id: BusinessId, start: java.time.LocalDate, end: java.time.LocalDate? = null) {
            val items = ContractIntakeItems(clock.now().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(), ContractIntakeChannel.PHONE, ActorRef("admin:adm_01JCFL0000000000000000OP01"), setOf(DesiredService.CARD_AUTH))
            (intakes.open(OpenContractIntakeCommand(OnboardingFixture.request(), id, ContractIntakeKind.NEW, items, null)) is AdminCommandResult.Succeeded) shouldBe true
            (intakes.move(MoveContractIntakeCommand(OnboardingFixture.request(), id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)) is AdminCommandResult.Succeeded) shouldBe true
            (
                intakes.complete(
                    CompleteContractIntakeCommand(OnboardingFixture.request(), id, 1, 1, ContractDocumentRef("DOC-1"), BusinessContractTerms(start, end, setOf(ServiceKind.CARD_PG_PAYMENT)), null),
                ) is AdminCommandResult.Succeeded
            ) shouldBe true
        }

        Then("no contract → null; completed → ACTIVE; suspended → SUSPENDED; terminated → TERMINATED (status at the MASTER clock — same calculation as the contract-progress section)") {
            val n = number()
            val id = BusinessId(created(n = n))
            status(n).isNull shouldBe true
            OnboardingFixture.contracted(id)
            status(n).asString() shouldBe "ACTIVE"
            contracts.suspend(BusinessContractEventCommand(OnboardingFixture.request(), id, 1, 0, reason, null)).shouldBeInstanceOfSucceeded()
            status(n).asString() shouldBe "SUSPENDED"
            contracts.terminate(BusinessContractEventCommand(OnboardingFixture.request(), id, 1, 1, reason, null)).shouldBeInstanceOfSucceeded()
            status(n).asString() shouldBe "TERMINATED"
        }

        Then("review NIT-3: a contract starting later → NOT_YET_EFFECTIVE; after its end date (clock moved past it) → EXPIRED") {
            clock.set(java.time.Instant.parse("2026-11-26T01:00:00Z")) // 2026-11-26 10:00 KST
            try {
                val later = number()
                contracted(BusinessId(created(n = later)), java.time.LocalDate.parse("2026-11-29"))
                status(later).asString() shouldBe "NOT_YET_EFFECTIVE"
                val ending = number()
                contracted(BusinessId(created(n = ending)), java.time.LocalDate.parse("2026-11-26"), java.time.LocalDate.parse("2026-11-26"))
                status(ending).asString() shouldBe "ACTIVE"
                clock.set(java.time.Instant.parse("2026-11-26T15:00:00Z")) // 2026-11-27 00:00 KST — 종료일 다음 날
                status(ending).asString() shouldBe "EXPIRED"
            } finally {
                clock.reset()
            }
        }

        Then("review MINOR-3: stored contract rows breaking a domain invariant → 503 TEMPORARILY_UNAVAILABLE (not hidden as null) with a value-free ERROR alert") {
            val n = number()
            val id = BusinessId(created(n = n))
            OnboardingFixture.contracted(id)
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
                c.prepareStatement(
                    "INSERT INTO business_contract_event (business_id, contract_seq, event_seq, kind, reason, evidence_ref, actor_ref, occurred_at) " +
                        "VALUES (?, 1, 1, 'RESUME', 'CANARY-RESUME-REASON', NULL, 'admin:x', '2026-11-25 00:00:00.000')",
                ).use { it.setString(1, id.value); it.executeUpdate() }
            }
            val (res, lines) = CapturedLogs().use { logs -> post(BusinessBodies.LOOKUPS, """{"registrationNumber":"$n"}""") to logs.lines() }
            listOf(res.response.status, res.body()) shouldBe listOf(503, """{"error":"TEMPORARILY_UNAVAILABLE"}""")
            lines.filter { it.contains("alert=BUSINESS_CONTRACT_LOAD_INVARIANT") }.map { it.substringAfter("alert=") } shouldBe
                listOf("BUSINESS_CONTRACT_LOAD_INVARIANT businessId=${id.value} aggregate=contract")
            lines.forEach { it shouldNotContain "CANARY-RESUME-REASON"; it shouldNotContain n }
        }
    }

    Given("BIZ-2-7 query endpoints over HTTP (shapes — Reader behaviour is BusinessReaderSpec)") {
        Then("list, lookup (found / not found same shape with registrationStatus), detail (raw personal data, raw number — D-BIZ-007) and history keys") {
            val n = number()
            val id = created(content = BusinessBodies.corporation(name = "조회모양 법인"), n = n)
            val list = post(BusinessBodies.SEARCHES, """{"displayNamePrefix":"조회모양 법인","limit":1}""").tree()
            list.keys() shouldBe listOf("items", "next")
            list.get("items").get(0).keys() shouldBe listOf(
                "businessId", "businessName", "registrationNumber", "businessType", "registrationStatus", "verificationStatus", "representativeNameMasked",
                "representativeCount", "merchantCount", "updatedAt",
            )
            val found = post(BusinessBodies.LOOKUPS, """{"registrationNumber":"$n"}""")
            // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): 조회 응답 번호는 저장 원문 10자리(응답 필드 `registrationNumber` — v2 계약, M-1).
            // 기대값 변경(MERCHANT-FLOW-001 FLOW-2d — Coordinator 결정(가역) (a): 가맹점 등록 ① 사전 안내용 응답 키 추가, 완화 아님): `businessContractStatus`(계약 없음 = null).
            found.body() shouldBe """{"items":[{"businessId":"$id","businessName":"조회모양 법인","registrationNumber":"$n","registrationStatus":"REGISTERED","businessContractStatus":null}]}"""
            val missing = post(BusinessBodies.LOOKUPS, """{"registrationNumber":"${number()}"}""").tree()
            missing.keys() shouldBe listOf("items")
            missing.get("items").size() shouldBe 0
            post(BusinessBodies.LOOKUPS, """{"registrationNumber":"123-45-67890"}""").body() shouldBe """{"error":"REQUEST_MALFORMED"}"""

            val d = get(BusinessBodies.detail(id)).tree()
            d.keys() shouldBe listOf("business", "registration", "representatives", "categories", "addresses", "contacts")
            d.get("business").keys() shouldBe listOf(
                "businessId", "businessName", "registrationNumber", "businessType", "registrationStatus", "verificationStatus", "lifecycle", "version", "createdAt",
                "updatedAt", "merchantCount",
            )
            d.get("registration").keys() shouldBe listOf("corporateRegistrationNumber", "openingDate", "countryCode", "englishName", "phoneNumber", "email")
            d.get("registration").get("corporateRegistrationNumber").asString() shouldBe "1101110000001"
            d.get("registration").get("openingDate").asString() shouldBe "2026-01-01"
            d.get("representatives").let { reps -> (0 until reps.size()).map { reps.get(it).get("name").asString() } } shouldBe listOf("이공동", "박공동")
            d.get("contacts").get(0).keys() shouldBe listOf("name", "email", "phoneNumber", "primary")
            d.get("contacts").get(0).get("phoneNumber").asString() shouldBe "01012345678"
            // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): 조회 응답 번호는 저장 원문 10자리(응답 필드 `registrationNumber` — v2 계약, M-1).
            d.get("business").get("registrationNumber").asString() shouldBe n

            val h = get(BusinessBodies.history(id)).tree()
            h.keys() shouldBe listOf("items", "nextAfter")
            h.get("items").get(0).keys() shouldBe listOf("seq", "changeType", "actorRef", "occurredAt", "reason", "changes")
            h.get("items").get(0).get("changeType").asString() shouldBe "BusinessCreated"
        }
    }

    Given("BIZ-2-8 no exposure on the MASTER side (canary values of §9)") {
        Then("the canary never appears in logs, the 400 body, admin_request, master_change_history, outbox_event; personal data only in the detail, the number in list/lookup/detail/duplicate (D-BIZ-007)") {
            val canaries = listOf(
                BusinessFixtures.CANARY_NUMBER, BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE,
                BusinessFixtures.CANARY_DETAIL_ADDRESS,
            )
            val bodies = mutableListOf<String>()
            // BRN-UNMASK-001(D-BIZ-007·D-PII-001 ①): 번호를 원문으로 주는 조회 응답(목록·번호 조회·상세·중복 요약)은 따로 모은다 — 번호 캔어리만 대조군, 개인정보 캔어리는 여전히 0건.
            val numberBodies = mutableListOf<String>()
            var detail = ""
            recorder.statements.clear()
            recorder.recording = true
            val lines = CapturedLogs().use { logs ->
                // 공유 DB: BIZ-1 시험이 같은 캔어리 번호를 먼저 썼을 수 있다 — 그러면 중복 거절의 existing으로 같은 사업자를 얻는다(둘 다 캔어리 내용).
                val r = post(BusinessBodies.CREATE, BusinessBodies.create(number = BusinessFixtures.CANARY_NUMBER, content = BusinessBodies.canary()))
                val t = r.tree()
                // 중복 거절이면 existing 요약에 원문 번호가 있다(D-BIZ-007) — 번호 응답 쪽에 모은다.
                if (t.get("outcome").asString() == "SUCCEEDED") bodies += r.body() else numberBodies += r.body()
                val id = if (t.get("outcome").asString() == "SUCCEEDED") t.get("reference").asString() else t.get("existing").get("businessId").asString()
                val invalid = post(
                    BusinessBodies.CREATE,
                    BusinessBodies.create(number = BusinessFixtures.CANARY_NUMBER, content = BusinessBodies.with(BusinessBodies.canary(), "displayName", " 앞공백")),
                )
                invalid.response.status shouldBe 400
                bodies += invalid.body()
                val badEmail = BusinessBodies.with(BusinessBodies.canary(), "contacts", listOf(BusinessBodies.contact(BusinessFixtures.CANARY_REPRESENTATIVE, "x@" + BusinessFixtures.CANARY_PHONE)))
                bodies += post(BusinessBodies.CREATE, BusinessBodies.create(number = BusinessFixtures.CANARY_NUMBER, content = badEmail)).body()
                numberBodies += post(BusinessBodies.SEARCHES, """{"displayNamePrefix":"캔어리","limit":50}""").body()
                numberBodies += post(BusinessBodies.LOOKUPS, """{"registrationNumber":"${BusinessFixtures.CANARY_NUMBER}"}""").body()
                bodies += get(BusinessBodies.history(id)).body()
                detail = get(BusinessBodies.detail(id)).body()
                // 중복 거절 요약(existingSummary) SELECT도 포획 대상에 넣는다.
                numberBodies += post(BusinessBodies.CREATE, BusinessBodies.create(number = BusinessFixtures.CANARY_NUMBER, content = BusinessBodies.canary())).body()
                logs.lines()
            }
            recorder.recording = false
            // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007): 목록·번호 조회·상세·중복 요약 SELECT(jOOQ 포획)는 이제 원문 번호 열을 그대로 읽는다(대조 — 빈 포획 통과 금지).
            // 옛 단언(마스킹 밖 SELECT 없음)은 D-BIZ-007이 바꾼 화면·응답 규칙이라 뒤집는다. 로그·이력·Outbox·admin_request 0건 단언은 아래에서 그대로(약화 없음).
            val captured = recorder.statements.toList()
            captured.count { it.contains("`registration_number`") } shouldBeGreaterThanOrEqual 3
            captured.filterNot(::numberOnlyMaskedOrBound).isNotEmpty() shouldBe true
            numberBodies.size shouldBeGreaterThanOrEqual 3
            numberBodies.forEach { it shouldContain BusinessFixtures.CANARY_NUMBER }
            canaries.forEach { c ->
                withClue(c) {
                    bodies.forEach { it shouldNotContain c }
                    if (c != BusinessFixtures.CANARY_NUMBER) numberBodies.forEach { it shouldNotContain c }
                    lines.forEach { it shouldNotContain c }
                    count(
                        "SELECT COUNT(*) FROM admin_request WHERE CONCAT_WS('|', operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_code, " +
                            "result_reference) LIKE ?",
                        "%$c%",
                    ) shouldBe 0L
                    count("SELECT COUNT(*) FROM master_change_history WHERE CAST(detail AS CHAR) LIKE ?", "%$c%") shouldBe 0L
                    count("SELECT COUNT(*) FROM outbox_event WHERE CAST(payload AS CHAR) LIKE ?", "%$c%") shouldBe 0L
                }
            }
            // 대조군: 상세에는 개인정보 원문이 있다(D-BIZ-006 ②). 기대값 변경(BRN-UNMASK-001 — D-BIZ-007): 사업자등록번호도 상세에서 원문.
            detail shouldContain BusinessFixtures.CANARY_REPRESENTATIVE
            detail shouldContain BusinessFixtures.CANARY_EMAIL
            detail shouldContain BusinessFixtures.CANARY_PHONE
            detail shouldContain BusinessFixtures.CANARY_DETAIL_ADDRESS
            detail shouldContain BusinessFixtures.CANARY_NUMBER
            detail shouldNotContain "731*******"
            // 이력 저장값은 마스킹 그대로(D-BIZ-007 유지 — 위 0건 단언의 대조: 마스킹 값은 실제로 있다).
            (count("SELECT COUNT(*) FROM master_change_history WHERE CAST(detail AS CHAR) LIKE ?", "%731*******%") >= 1L) shouldBe true
        }
    }

    Given("SEC-BIZ-001 M-1 a change reason with a card-number-like digit run (BR-BIZ-305, D-FLOW-007 ① — new saves only)") {
        Then("update and complete are 400 BUSINESS_VALIDATION_FAILED [reason CARD_NUMBER_PATTERN] with no value echoed; admin_request·history 0 rows, version unchanged") {
            val id = created(status = "DRAFT")
            val v = versionOf(id)
            val historyBefore = count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id)
            listOf(BusinessBodies.UPDATE, BusinessBodies.COMPLETE).forEach { path ->
                val s = Submission()
                val r = post(path, BusinessBodies.update(s, id, v, reason = "상호 정정 카드 4111-1111-1111-1111"))
                r.response.status shouldBe 400
                r.body() shouldBe """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"reason","code":"CARD_NUMBER_PATTERN"}]}"""
                adminRows(s.idempotencyKey) shouldBe 0L
            }
            versionOf(id) shouldBe v
            count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id) shouldBe historyBefore
            count("SELECT COUNT(*) FROM master_change_history WHERE CAST(detail AS CHAR) LIKE ?", "%4111-1111%") shouldBe 0L
        }
        Then("control: a digit run that is not card-like (date + serial with separators) is stored as the reason") {
            val id = created(status = "DRAFT")
            val r = post(BusinessBodies.UPDATE, BusinessBodies.update(businessId = id, version = versionOf(id), reason = "요청 문서 RECON-2026-0001", content = BusinessBodies.individual(name = "새 상호")))
            withClue(r.body()) { r.response.status shouldBe 200 }
            r.tree().get("outcome").asString() shouldBe "SUCCEEDED"
        }
    }

    Given("BIZ-2-11 rule violations found on the locked state (rev 0.2.3 m-1 — C-16)") {
        Then("REGISTERED update removing the primary contact → 400 BUSINESS_VALIDATION_FAILED [contacts PRIMARY_REQUIRED], nothing stored; the same body again → the same 400") {
            val id = created()
            val v = versionOf(id)
            val s = Submission()
            val noPrimary = BusinessBodies.with(BusinessBodies.individual(), "contacts", listOf(BusinessBodies.contact(primary = false)))
            val body = BusinessBodies.update(s, id, v, content = noPrimary)
            val historyBefore = count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id)
            repeat(2) {
                val r = post(BusinessBodies.UPDATE, body)
                r.response.status shouldBe 400
                r.body() shouldBe """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"contacts","code":"PRIMARY_REQUIRED"}]}"""
            }
            adminRows(s.idempotencyKey) shouldBe 0L
            versionOf(id) shouldBe v
            count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id) shouldBe historyBefore
            // BIZ-CRIT-APPROVAL-001 §7.1(이동): 사유 있음 + REGISTERED 대표자 0명의 규칙 위반 400은 제안 endpoint 시험(BusinessCriticalChangeApiSpec)으로 옮겼다.
            // 1인 경로에서는 대표자 변경(중요)이 규칙 재검사보다 먼저 2인 필요 저장 거절이다(§3.3 판정 순서).
            val withReason = post(BusinessBodies.UPDATE, BusinessBodies.update(businessId = id, version = v, reason = "대표자 정리",
                content = BusinessBodies.with(BusinessBodies.individual(), "representatives", emptyList<Any>())))
            withReason.body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED","replayed":false}"""
        }

        // BIZ-CRIT-APPROVAL-001 §7.1(D-GOV-016 ③): 기대값 `BUSINESS_CHANGE_REASON_REQUIRED` → `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`(admin_request 1행 불변).
        Then("order: an important change without a reason plus a required-value removal is the stored BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED (200 REJECTED, admin_request 1 row)") {
            val id = created()
            val s = Submission()
            val r = post(BusinessBodies.UPDATE, BusinessBodies.update(s, id, versionOf(id), content = BusinessBodies.with(BusinessBodies.individual(), "representatives", emptyList<Any>())))
            r.body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED","replayed":false}"""
            adminRows(s.idempotencyKey) shouldBe 1L
        }

        Then("control: only BusinessProfileRuleViolation becomes 400 — another IllegalArgumentException from the use case stays 503 TEMPORARILY_UNAVAILABLE (message never logged)") {
            val canary = "9988776655"
            fun controller(e: RuntimeException) = BusinessCommandController(
                object : BusinessProfileCommandUseCase {
                    override fun create(command: CreateBusinessCommand): AdminCommandResult = throw e
                    override fun update(command: UpdateBusinessCommand): AdminCommandResult = throw e
                    override fun completeRegistration(command: CompleteBusinessRegistrationCommand): AdminCommandResult = throw e
                },
                unusedQuery(),
                AdminChannelPermits(MasterAdminChannelFixture.springSettings(), SimpleMeterRegistry()),
                SimpleMeterRegistry(),
                // BIZ-CRIT-APPROVAL-001 S2: 제안 명령 의존성(이 시험은 부르지 않는다 — 생성자 인자만 보강).
                unused(com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase::class.java),
                unused(com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery::class.java),
            )
            fun request() = MockHttpServletRequest("POST", BusinessBodies.UPDATE).apply {
                setContent(BusinessBodies.update(businessId = "biz_01JC0000000000000000000001", version = 1).toByteArray(Charsets.UTF_8))
            }
            val lines = CapturedLogs().use { logs ->
                val plain = controller(IllegalArgumentException("defect with $canary")).update(request())
                plain.statusCode.value() shouldBe 503
                String(plain.body!!, Charsets.UTF_8) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                val rule = controller(BusinessProfileRuleViolation(listOf(BusinessFieldError("contacts", BusinessFieldErrorCode.PRIMARY_REQUIRED)))).update(request())
                rule.statusCode.value() shouldBe 400
                String(rule.body!!, Charsets.UTF_8) shouldBe """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"contacts","code":"PRIMARY_REQUIRED"}]}"""
                logs.lines()
            }
            lines.forEach { it shouldNotContain canary }
            lines.any { it.contains("failure=IllegalArgumentException") } shouldBe true
        }
    }
}) {
    private companion object {
        fun <T> unused(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> error("not used") })

        fun unusedQuery(): BusinessProfileQueryUseCase = java.lang.reflect.Proxy.newProxyInstance(
            BusinessProfileQueryUseCase::class.java.classLoader, arrayOf(BusinessProfileQueryUseCase::class.java),
        ) { _, _, _ -> error("query not used") } as BusinessProfileQueryUseCase
    }
}

private fun AdminCommandResult.shouldBeInstanceOfSucceeded() {
    (this is AdminCommandResult.Succeeded) shouldBe true
}
