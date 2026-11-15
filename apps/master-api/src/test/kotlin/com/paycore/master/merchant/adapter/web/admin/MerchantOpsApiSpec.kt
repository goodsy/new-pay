package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantHistoryPage
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-OPS-001 §3·AC 1·3–6(실제 보안 필터 체인 + MVC + MySQL — 같은 시험 컨텍스트에 MockMvc를 붙인다, 새 컨텍스트 없음):
 * 무서명·틀린 서명 거부 행렬(등록된 `/internal/v1/admin/` handler 전수), 그 밖 경로 기본 거부(health만 허용), 서명 호출 응답 모양, **응답 바이트 비노출**(기관 가맹점 번호·이력
 * detail·등록번호 평문 — 같은 DB에 값이 있음을 대조군으로 확인), 오류 code(입력 값 미반영), 로그에 등록번호 없음.
 * 리뷰 보강(2026-11-04): 두 체인의 권한 규칙 자체(서명 필터를 우회해도 `ADMIN_SERVICE`만 — 변이 M-3), 반복 조회 결과 동일·MASTER 표 행 수·checksum 불변(읽기 경로 부작용 없음),
 * 수락·거절 호출 로그에 서명 키·서명 헤더·v1 값·등록번호 원문 없음(캡처 동작 대조군 포함).
 */
@MasterDbTest
class MerchantOpsApiSpec(
    context: WebApplicationContext,
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val mappings: List<RequestMappingHandlerMapping>,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
    private val chains: List<SecurityFilterChain>,
    private val permits: AdminChannelPermits,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun MockHttpServletRequestBuilder.signed(method: String, path: String, body: ByteArray = ByteArray(0)) =
        header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader(method, path, body))

    fun get(path: String, sign: Boolean = true): MvcResult =
        mvc.perform(MockMvcRequestBuilders.get(path).let { if (sign) it.signed("GET", path) else it }).andReturn()

    fun post(path: String, body: String, sign: Boolean = true): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
        return mvc.perform(if (sign) b.signed("POST", path, bytes) else b).andReturn()
    }

    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)

    fun registrationNumberOf(businessId: String): String = OnboardingFixture.runtime(container).use {
        it.scalar("SELECT registration_number FROM business WHERE business_id = ?", businessId) as String
    }

    val search = "/internal/v1/admin/merchant-searches"

    Given("every admin-channel route and other paths without a signature") {
        Then("all registered /internal/v1/admin/ handlers and planned paths are 401 UNAUTHENTICATED with no-store and no WWW-Authenticate") {
            val mapped = mappings.flatMap { it.handlerMethods.keys }.flatMap { info ->
                val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("GET", "POST") }
                info.patternValues.filter { it.startsWith("/internal/v1/admin/") }.flatMap { p -> methods.map { it to p.replace(Regex("\\{[^}]+}"), "x") } }
            }
            // 기대값 변경(MERCHANT-OPS-002 B1-1 — 새 서버 기능): 조회 3 + 명령 3(businesses·merchants·merchant-sites). 쓰기 경로 전수는 MerchantOperationsApiSpec(m-5).
            // 기대값 변경(MERCHANT-OPS-002 B1-2 — §8.1 B1-1-1 m-5 "B1-2 뒤 B1 5개"): + 명령 2(contracts·service-agreements) = 8.
            // 기대값 변경(B2-1 contract-revisions, 명세 §8·§8.2 B2-1): + 명령 1 = 9. 개수가 아니라 경로 집합 자체를 고정해 새 경로가 아래 401 검사 대상에 들어 있음을 보인다.
            mapped.toSet() shouldBe setOf(
                "POST" to search,
                "GET" to "/internal/v1/admin/merchants/x",
                "GET" to "/internal/v1/admin/merchants/x/history/after/x",
                "POST" to "/internal/v1/admin/merchant-operations/businesses",
                "POST" to "/internal/v1/admin/merchant-operations/merchants",
                "POST" to "/internal/v1/admin/merchant-operations/merchant-sites",
                "POST" to "/internal/v1/admin/merchant-operations/contracts",
                "POST" to "/internal/v1/admin/merchant-operations/service-agreements",
                "POST" to "/internal/v1/admin/merchant-operations/contract-revisions",
                // 기대값 변경(B2-2 승인 근거 endpoint 3개, 명세 §8.2 B2-2-5·§9.4): 실행·결정 조회·기한 확인 = 12. 경로 목록 갱신이지 완화가 아니다(아래 401 검사 대상에 들어간다).
                "POST" to "/internal/v1/admin/merchant-operations/contract-revision-approvals",
                "POST" to "/internal/v1/admin/merchant-operations/approval-decisions",
                "POST" to "/internal/v1/admin/merchant-operations/approval-deadline-confirmations",
                // 기대값 변경(B2-3, 명세 §8.2 B2-3-1): 첫 Default MID 발급 실행 = 13(401 검사 대상).
                "POST" to "/internal/v1/admin/merchant-operations/first-default-merchant-accounts",
                // 기대값 변경(B3-1, 명세 §8.3 B3-1-6): 새 handler 4개 = 17(401 검사 대상).
                "POST" to "/internal/v1/admin/merchant-operations/payment-method-activations",
                "POST" to "/internal/v1/admin/merchant-operations/site-review-rejections",
                "POST" to "/internal/v1/admin/merchant-operations/payment-method-activation-approvals",
                "POST" to "/internal/v1/admin/merchant-operations/site-review-approvals",
                // 기대값 변경(B3-2, 명세 §8.4 B3-2-8): 새 handler 1개(`currency-enablements`) = 18(401 검사 대상).
                "POST" to "/internal/v1/admin/merchant-operations/currency-enablements",
                // 기대값 변경(MERCHANT-CODE-001 MC-1b-10): 새 handler 1개(`merchant-code-changes`) = 19(401 검사 대상).
                "POST" to "/internal/v1/admin/merchant-operations/merchant-code-changes",
            )
            // review n-c: 집합 비교는 같은 (method, path)가 두 handler에 중복 매핑돼도 하나로 합쳐 숨긴다 — 목록 크기를 따로 고정해 중복 매핑(모호한 handler)을 잡는다.
            mapped.size shouldBe 19
            (mapped + listOf("GET" to "/internal/v1/admin/merchants/mer_00000000000000000000000000", "DELETE" to "/internal/v1/admin/anything")).forEach { (method, path) ->
                val r = mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)).andReturn()
                withClue("$method $path") {
                    r.response.status shouldBe 401
                    r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                    r.response.getHeader("Cache-Control")!! shouldContain "no-store"
                    r.response.getHeader("WWW-Authenticate") shouldBe null
                    r.response.getHeader(AdminChannelResponses.REQUEST_ID_HEADER) shouldNotBe null
                }
            }
        }
        Then("paths outside the admin channel are denied (401) except GET /actuator/health; no session cookie is created") {
            listOf("GET" to "/", "GET" to "/actuator/env", "GET" to "/login", "POST" to "/internal/v1/entitlement-snapshots", "GET" to "/internal/v1/adminx").forEach { (method, path) ->
                val r = mvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)).andReturn()
                withClue("$method $path") {
                    r.response.status shouldBe 401
                    r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                    r.response.getHeader("Set-Cookie") shouldBe null
                }
            }
            mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andReturn().response.status shouldNotBe 401
        }
        Then("a stale or foreign-key signature is 401; a query string is 400 before the signature") {
            val path = "/internal/v1/admin/merchants/mer_00000000000000000000000000"
            mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path, t = java.time.Instant.now().epochSecond - 400)))
                .andReturn().response.status shouldBe 401
            mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.header("GET", path, ByteArray(0))))
                .andReturn().response.status shouldBe 401
            val q = mvc.perform(MockMvcRequestBuilders.get("$path?x=1").signed("GET", path)).andReturn()
            q.response.status shouldBe 400
            q.body() shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
        }
        Then("defense in depth (M-3): the admin chain's own rule grants only ADMIN_SERVICE even if the signature filter were bypassed; the default chain grants only health") {
            fun request(method: String, path: String) = MockHttpServletRequest(method, path).apply { servletPath = path }
            fun AuthorizationFilter.granted(auth: Authentication, req: MockHttpServletRequest) = authorizationManager.authorize({ auth }, req)?.isGranted ?: false
            fun SecurityFilterChain.rule() = filters.filterIsInstance<AuthorizationFilter>().single()
            val (admin, other) = chains.partition { chain -> chain.filters.any { it is AdminChannelAuthenticationFilter } }
            admin.size shouldBe 1
            other.size shouldBe 1
            val service = TestingAuthenticationToken("adm", "n/a", AdminChannelAuthenticationFilter.ADMIN_SERVICE)
            val someoneElse = TestingAuthenticationToken("svc", "n/a", "ROLE_USER", "ADMIN_SERVICE_X")
            val anonymous = AnonymousAuthenticationToken("k", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
            val adminPath = request("GET", "/internal/v1/admin/merchants/mer_00000000000000000000000000")
            admin.single().matches(adminPath) shouldBe true
            admin.single().matches(request("GET", "/internal/v1/adminx")) shouldBe false
            admin.single().rule().granted(service, adminPath) shouldBe true
            admin.single().rule().granted(someoneElse, adminPath) shouldBe false
            admin.single().rule().granted(anonymous, adminPath) shouldBe false
            other.single().rule().granted(anonymous, request("GET", "/actuator/health")) shouldBe true
            listOf(request("GET", "/actuator/env"), request("GET", "/"), request("POST", "/internal/v1/entitlement-snapshots")).forEach { req ->
                withClue(req.requestURI) {
                    other.single().rule().granted(anonymous, req) shouldBe false
                    other.single().rule().granted(service, req) shouldBe false
                }
            }
        }
        Then("Concurrency Control: with every read permit held, a signed call is 503 ADMIN_READ_BUSY at once and counted per endpoint") {
            val path = "/internal/v1/admin/merchants/mer_00000000000000000000000000"
            val busyBefore = registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "detail").counter()?.count() ?: 0.0
            val held = CountDownLatch(MasterAdminChannelFixture.springSettings().readPermits)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(MasterAdminChannelFixture.springSettings().readPermits)
            try {
                repeat(MasterAdminChannelFixture.springSettings().readPermits) {
                    pool.submit { permits.read("hold", { error("permit expected") }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
                }
                held.await(5, TimeUnit.SECONDS) shouldBe true
                val started = System.nanoTime()
                val busy = get(path)
                busy.response.status shouldBe 503
                busy.body() shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000) shouldBe true
                registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "detail").counter()!!.count() shouldBe busyBefore + 1.0
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
            get(path).response.status shouldBe 404
        }
        Then("Recoverability: a storage failure is 503 TEMPORARILY_UNAVAILABLE on every endpoint and the exception message (possibly bind values) is never logged") {
            val canary = "9988776655"
            val failing = object : MerchantOpsQuery {
                override fun search(filter: MerchantSearchFilter): MerchantDirectoryPage = throw IllegalStateException("driver text with $canary")
                override fun detail(merchantId: MerchantId): MerchantDetailView? = throw IllegalStateException("driver text with $canary")
                override fun history(merchantId: MerchantId, afterSeq: Long): MerchantHistoryPage? = throw IllegalStateException("driver text with $canary")
            }
            val controller = MerchantOpsController(failing, AdminChannelPermits(MasterAdminChannelFixture.springSettings(), SimpleMeterRegistry()))
            val id = "mer_00000000000000000000000000"
            val lines = CapturedLogs().use { logs ->
                val responses = listOf(
                    controller.search(MockHttpServletRequest("POST", search).apply { setContent("""{"limit":1}""".toByteArray(Charsets.UTF_8)) }),
                    controller.detail(id, MockHttpServletRequest("GET", "/internal/v1/admin/merchants/$id")),
                    controller.history(id, "0", MockHttpServletRequest("GET", "/internal/v1/admin/merchants/$id/history/after/0")),
                )
                responses.forEach {
                    it.statusCode.value() shouldBe 503
                    String(it.body!!, Charsets.UTF_8) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                }
                logs.lines()
            }
            lines.count { "result=TEMPORARILY_UNAVAILABLE" in it && "failure=IllegalStateException" in it } shouldBe 3
            lines.filter { canary in it }.shouldBeEmpty()
        }
    }

    Given("a fully onboarded merchant behind a signed call") {
        val r = AuthorizationTermsCommandFixture.ready(terms, EntitlementCommandFixture.entitled(onboarding, entitlement))
        val e = r.entitled
        val brn = registrationNumberOf(e.ready.businessId.value)
        val institutionRef = AuthorizationTermsCommandFixture.institutionRef.value

        Then("control: the institution merchant reference really is stored (route row and change-history detail)") {
            OnboardingFixture.runtime(container).use { c ->
                c.scalar("SELECT COUNT(*) FROM master_change_history WHERE JSON_SEARCH(detail, 'one', ?) IS NOT NULL", institutionRef).toString().toLong() shouldNotBe 0L
                c.scalar("SELECT institution_merchant_ref FROM card_route_activation WHERE activation_id = ?", r.route.value) shouldBe institutionRef
            }
        }

        Then("detail is 200 with the documented shape and no institution reference, no history detail and no plain registration number") {
            val res = get("/internal/v1/admin/merchants/${e.merchantId.value}")
            res.response.status shouldBe 200
            res.response.getHeader("Cache-Control")!! shouldContain "no-store"
            val text = res.body()
            val node = json.readTree(text)
            // 기대값 변경(MERCHANT-CODE-001 MC-1 §3.8 — 응답 키 증가, 완화 아님): 최상위에 merchantCodeHistory·merchantCodeChangeable(MC-1-6 MerchantCodeApiSpec이 값·모양 확인).
            node.propertyNames().toList() shouldBe listOf("merchant", "business", "sites", "contracts", "accounts", "limits", "propagation", "merchantCodeHistory", "merchantCodeChangeable")
            node.get("business").get("registrationNumberMasked").asString() shouldBe brn.take(3) + "*******"
            node.get("accounts").get(0).get("cardRoutes").get(0).propertyNames().toList() shouldBe
                listOf("activationId", "paymentMethod", "acquirerCode", "routeRef", "status", "registeredAt")
            // MERCHANT-OPS-002 B2-2(D-MOPS-008 ③, §9.5): Revision마다 등록자(정규 actor 문자열) — ADMIN이 pinned registeredBy로 등록자≠승인자를 판정한다.
            val revision = node.get("contracts").get(0).get("revisions").get(0)
            revision.propertyNames().toList() shouldBe listOf("revisionNo", "scopeKind", "effectiveFrom", "effectiveTo", "approvedAt", "scopeSiteIds", "registeredBy")
            revision.get("registeredBy").asString() shouldBe OnboardingFixture.operator.value
            // MERCHANT-OPS-002 B3-1(§8.3 B3-1-7): 등록자·Site 적용 확인 목록 — 근거 참조·사유 열은 SELECT하지 않는다(값이 응답에 없음).
            node.get("sites").get(0).propertyNames().toList() shouldBe listOf("merchantSiteId", "channelType", "channelIdentifier", "lifecycle", "review", "registeredBy")
            node.get("accounts").get(0).get("paymentMethods").get(0).propertyNames().toList() shouldBe
                listOf("activationId", "paymentMethod", "status", "registeredAt", "registeredBy")
            node.get("accounts").get(0).get("siteConfirmations").get(0).propertyNames().toList() shouldBe
                listOf("confirmationId", "merchantSiteId", "paymentMethod", "activationId", "confirmedAt", "revoked")
            text shouldNotContain com.paycore.master.merchant.application.EntitlementCommandFixture.evidence.value
            text shouldNotContain institutionRef
            text shouldNotContain "institutionMerchantRef"
            text shouldNotContain "\"detail\""
            text shouldNotContain brn
        }

        Then("history is 200 without detail or request ids, and cursor paging works via the path") {
            val res = get("/internal/v1/admin/merchants/${e.merchantId.value}/history/after/0")
            res.response.status shouldBe 200
            val text = res.body()
            text shouldNotContain institutionRef
            text shouldNotContain "\"detail\""
            text shouldNotContain "requestRecordId"
            val items = json.readTree(text).get("items")
            items.get(0).propertyNames().toList() shouldBe listOf("seq", "occurredAt", "operation", "aggregateType", "aggregateId", "changeType", "actorRef")
            val first = items.get(0).get("seq").asLong()
            json.readTree(get("/internal/v1/admin/merchants/${e.merchantId.value}/history/after/$first").body()).get("items").get(0).get("seq").asLong() shouldNotBe first
        }

        Then("search by the full registration number returns only masked values and the log never contains the number") {
            CapturedLogs().use { logs ->
                val res = post(search, """{"registrationNumber":"$brn"}""")
                res.response.status shouldBe 200
                val node = json.readTree(res.body())
                node.get("items").get(0).get("registrationNumberMasked").asString() shouldBe brn.take(3) + "*******"
                res.body() shouldNotContain brn
                logs.lines().filter { brn in it }.shouldBeEmpty()
            }
        }

        Then("found and not-found searches have the same shape; malformed bodies are 400 without echoing the input") {
            val notFound = post(search, """{"registrationNumber":"0000000001"}""")
            notFound.response.status shouldBe 200
            notFound.body() shouldBe """{"items":[],"nextCursor":null}"""
            listOf(
                """{"registrationNumber":"123456789"}""", """{"registrationNumber":"12345678901"}""", """{"registrationNumber":1234567890}""",
                """{"registrationNumber":"123-45-6789"}""", """{"unknown":"x"}""", """{"limit":51}""", """{"limit":"5"}""", """{"lifecycle":"active"}""",
                """{"merchantId":"mer_1"}""", """{"after":"x"}""", """[]""", """{"limit":1}{}""", """{"limit":1,"limit":2}""", "",
            ).forEach { body ->
                val res = post(search, body)
                withClue(body) {
                    res.response.status shouldBe 400
                    res.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
        }

        Then("unknown or malformed merchant ids are 404 MERCHANT_NOT_FOUND; a malformed history cursor is 400") {
            listOf("mer_00000000000000000000000000", "mer_x", "biz_00000000000000000000000000").forEach { id ->
                val res = get("/internal/v1/admin/merchants/$id")
                res.response.status shouldBe 404
                res.body() shouldBe """{"error":"MERCHANT_NOT_FOUND"}"""
            }
            get("/internal/v1/admin/merchants/mer_00000000000000000000000000/history/after/0").response.status shouldBe 404
            get("/internal/v1/admin/merchants/${e.merchantId.value}/history/after/-1").response.status shouldBe 400
            get("/internal/v1/admin/merchants/${e.merchantId.value}/history/after/99999999999999999999").response.status shouldBe 400
        }

        Then("accepted calls are counted; the metric labels carry classes only") {
            (registry.find(AdminChannelAuthenticationFilter.AUTH_METRIC).tags("result", "ACCEPTED", "reason", "NONE").counter()?.count() ?: 0.0) shouldNotBe 0.0
        }

        Then("Idempotency: repeating the same search, detail and history returns identical bodies and leaves every MASTER table unchanged (row counts and checksums)") {
            fun snapshot(): Map<String, String> = MasterDbAccounts.root(container).use { c ->
                val tables = c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '${MasterDbAccounts.MASTER_DATABASE}' AND table_type = 'BASE TABLE' ORDER BY table_name",
                )
                tables.size shouldNotBe 0
                tables.associateWith { t ->
                    val count = c.scalar("SELECT COUNT(*) FROM `$t`").toString()
                    val checksum = c.prepareStatement("CHECKSUM TABLE `$t`").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(2) } }
                    "$count/$checksum"
                }
            }
            val calls: List<() -> MvcResult> = listOf(
                { post(search, """{"registrationNumber":"$brn"}""") },
                { post(search, """{"businessId":"${e.ready.businessId.value}","limit":1}""") },
                { post(search, """{"registrationNumber":"0000000001"}""") },
                { get("/internal/v1/admin/merchants/${e.merchantId.value}") },
                { get("/internal/v1/admin/merchants/mer_00000000000000000000000000") },
                { get("/internal/v1/admin/merchants/${e.merchantId.value}/history/after/0") },
            )
            val before = snapshot()
            val first = calls.map { call -> call().let { it.response.status to it.body() } }
            val second = calls.map { call -> call().let { it.response.status to it.body() } }
            second shouldBe first
            first.map { it.first } shouldBe listOf(200, 200, 200, 200, 404, 200)
            snapshot() shouldBe before
        }

        Then("Non-exposure in logs: no signing key, signature header or v1 value and no full registration number on accepted or rejected calls") {
            val detailPath = "/internal/v1/admin/merchants/${e.merchantId.value}"
            val sentHeaders = mutableListOf<String>()
            fun signedGet(path: String, header: String): MvcResult {
                sentHeaders += header
                return mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, header)).andReturn()
            }
            val lines = CapturedLogs().use { logs ->
                signedGet(detailPath, MasterAdminChannelFixture.springHeader("GET", detailPath)).response.status shouldBe 200
                signedGet(detailPath, MasterAdminChannelFixture.header("GET", detailPath, ByteArray(0))).response.status shouldBe 401
                signedGet(detailPath, MasterAdminChannelFixture.springHeader("GET", detailPath, t = java.time.Instant.now().epochSecond - 400)).response.status shouldBe 401
                signedGet(detailPath, MasterAdminChannelFixture.springHeader("GET", "/internal/v1/admin/merchants/other")).response.status shouldBe 401
                val body = """{"registrationNumber":"$brn"}""".toByteArray(Charsets.UTF_8)
                val header = MasterAdminChannelFixture.springHeader("POST", search, body)
                sentHeaders += header
                mvc.perform(MockMvcRequestBuilders.post(search).contentType(MediaType.APPLICATION_JSON).content(body).header(AdminCallSignature.HEADER, header))
                    .andReturn().response.status shouldBe 200
                logs.lines()
            }
            // 대조군: 캡처가 실제로 동작한다(거절 WARN·검색 INFO가 잡힌다) — 빈 캡처로 통과하지 않는다.
            lines.count { "operation=AdminChannelAuthenticate" in it && "result=REJECTED" in it } shouldBe 3
            lines.count { "operation=MerchantSearch" in it } shouldBe 1
            val secrets = listOf(MasterAdminChannelFixture.SPRING_SECRET, MasterAdminChannelFixture.secret(MasterAdminChannelFixture.K1), MasterAdminChannelFixture.secret(MasterAdminChannelFixture.K2))
            val v1s = sentHeaders.map { it.substringAfter("v1=") }
            v1s.forEach { it.length shouldBe 64 }
            (secrets + sentHeaders + v1s + brn).forEach { s -> lines.filter { s in it }.shouldBeEmpty() }
        }
    }
})
