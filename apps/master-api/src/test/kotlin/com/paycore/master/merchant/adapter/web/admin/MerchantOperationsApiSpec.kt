package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.ApprovedContractRevisionApprovalCommand
import com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-OPS-002 §3·§8·§8.1 B1-1(실제 보안 필터 체인 + MVC + MySQL — 공용 시험 컨텍스트에 MockMvc). 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 사업자 등록 handler
 * (`merchant-operations/businesses`)가 제거돼 쓰기 경로 목록에서 빠지고, 그 handler를 운반체로 쓰던 채널 공통 시험(성공 모양·재생·409·같은 서명 재전송·permit 소진·실패 분류·로그 비노출)은
 * 가맹점 등록 handler로 옮겼다(같은 단언). 준비용 사업자는 새 생성 경로(REGISTERED — D-BIZ-001). 옛 경로 전용 C-12 중복 번호 비노출 시험은 D-BIZ-003으로 대체된 동작이라 제거 —
 * 새 경로 중복 거절(existing)은 BusinessProfileApiSpec BIZ-2-2, 다른 거절의 reference 비노출(C-12)은 아래 채널 중복 시험이 그대로 고정. 새 명령 handler의 서명 강제(무서명·다른 키·오래된 시각 401), 쓰기 경로 전수(m-5),
 * 성공 응답 모양·재생·키 재사용 409, 업무 거절 200 + 재생, **거절 reference 비노출(C-12 — 대조군: 같은 DB `admin_request`에는 기존 ID가 있다)**, 같은 서명 바이트 재전송 무변화(m-3),
 * 쓰기 permit 소진 503(조회 permit과 독립), Use Case 실패 분류·예외 문구 비로그, 로그 비노출(서명 키·서명·MASTER 멱등 키·등록번호·채널 식별값), 지표, Site 등록의 권리 revision(MID 없음 → 발행 0).
 */
@MasterDbTest
class MerchantOperationsApiSpec(
    context: WebApplicationContext,
    private val mappings: List<RequestMappingHandlerMapping>,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
    private val permits: AdminChannelPermits,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    // 리뷰 n-2: KST_TODAY·KST_YESTERDAY는 시험 clock t0(KST 2026-10-09 11:00) 기준이다. 판정 시각은 주입된 MASTER clock(@Primary MutableMasterClock)이라 벽시계·KST 자정과 무관하다 —
    // 같은 컨텍스트를 쓰는 다른 spec이 clock을 옮겨 두었어도 흔들리지 않게 매 시험 전에 t0로 되돌린다.
    beforeEach { clock.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String, header: String? = MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))): MvcResult {
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body.toByteArray(Charsets.UTF_8))
        return mvc.perform(if (header == null) b else b.header(AdminCallSignature.HEADER, header)).andReturn()
    }

    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun MvcResult.field(name: String): String = json.readTree(body()).get(name).asString()
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun adminRequestReference(key: String): String? = OnboardingFixture.runtime(container).use {
        it.scalar("SELECT result_reference FROM admin_request WHERE idempotency_key = ?", key) as String?
    }

    /** MASTER 전 표 행 수·CHECKSUM(MerchantOpsApiSpec과 같은 방법). */
    fun snapshot(): Map<String, String> = MasterDbAccounts.root(container).use { c ->
        c.column(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = '${MasterDbAccounts.MASTER_DATABASE}' AND table_type = 'BASE TABLE' ORDER BY table_name",
        ).associateWith { t ->
            val rows = c.scalar("SELECT COUNT(*) FROM `$t`").toString()
            val checksum = c.prepareStatement("CHECKSUM TABLE `$t`").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(2) } }
            "$rows/$checksum"
        }
    }

    fun commandCount(operation: String, outcome: String) =
        registry.find(MerchantOperationsController.COMMAND_METRIC).tags("operation", operation, "outcome", outcome).counter()?.count() ?: 0.0

    /** 준비용 사업자(새 생성 경로 REGISTERED — D-BIZ-001, BIZ-4 C-27). */
    fun registerBusiness(brn: String = OnboardingFixture.registrationNumber().value): String =
        post(BusinessBodies.CREATE, BusinessBodies.create(number = brn)).also { it.response.status shouldBe 200 }.field("reference")

    fun registerMerchant(businessId: String): String =
        post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(businessId)).also { it.response.status shouldBe 200 }.field("reference")

    Given("the signed admin channel in front of the new command handlers") {
        val paths = listOf(
            MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.SITES,
            MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.CONTRACT_REVISIONS,
        )

        Then("write-path inventory (m-5 — D-MOPS-005 risk scope): the non-GET handlers under /internal/v1/admin/ are exactly the search, the five B1 commands and the B2-1 revision registration") {
            val nonGet = mappings.flatMap { it.handlerMethods.keys }.flatMap { info ->
                val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("ANY") }
                info.patternValues.filter { it.startsWith("/internal/v1/admin/") }.flatMap { p -> methods.filter { it != "GET" }.map { "$it $p" } }
            }.toSet()
            // B1 5개 + B2-1 `contract-revisions`(§8.2 B2-1-3 — D-MOPS-004 "계약 초안" 해석으로 1인, MERCHANT-OPS-002 rev 0.6.2). 승인·개통·한도·정지 경로는 없다(B2-2 이후 그 배치 결정 근거로 갱신).
            // 기대값 변경(B2-2 승인 근거 endpoint 3개, 명세 §8.2 B2-2-5·§9.4): + `contract-revision-approvals`(실행)·`approval-decisions`(결정 조회 — 쓰기 효과 없음)·
            // `approval-deadline-confirmations`(기한 확인 — 실행 경로 없음). 경로 목록 갱신이지 완화가 아니다 — 첫 MID(B2-3)·개통·한도·정지 경로는 여전히 없다.
            val approvals = setOf(
                "POST /internal/v1/admin/merchant-operations/contract-revision-approvals",
                "POST /internal/v1/admin/merchant-operations/approval-decisions",
                "POST /internal/v1/admin/merchant-operations/approval-deadline-confirmations",
                // 기대값 변경(B2-3 첫 Default MID 발급, 명세 §8.2 B2-3-1): + `first-default-merchant-accounts`. 개통·한도·정지(B3~B5) 경로는 여전히 없다.
                "POST /internal/v1/admin/merchant-operations/first-default-merchant-accounts",
                // 기대값 변경(B3-1, 명세 §8.3 B3-1-6): 1인 `payment-method-activations`·`site-review-rejections`, 2인 `payment-method-activation-approvals`·`site-review-approvals`.
                // 경로 목록 갱신이지 완화가 아니다 — 통화·Route·재개·재확인(B3-2~4)·한도·정지 경로는 여전히 없다.
                "POST /internal/v1/admin/merchant-operations/payment-method-activations",
                "POST /internal/v1/admin/merchant-operations/site-review-rejections",
                "POST /internal/v1/admin/merchant-operations/payment-method-activation-approvals",
                "POST /internal/v1/admin/merchant-operations/site-review-approvals",
                // 기대값 변경(B3-2, 명세 §8.4 B3-2-8·§8.3 B3-2-3): 2인 `currency-enablements`. 경로 목록 갱신이지 완화가 아니다 — Route·재개·재확인(B3-3·B3-4)·한도·정지 경로는 여전히 없다.
                "POST /internal/v1/admin/merchant-operations/currency-enablements",
                // 기대값 변경(MERCHANT-CODE-001 MC-1b-10): 2인 `merchant-code-changes`(경로 목록 갱신 — 완화 아님).
                "POST /internal/v1/admin/merchant-operations/merchant-code-changes",
                // 기대값 변경(TXN-LIST-ENRICH-001 명세 §4.2): MID 묶음 라벨 조회 `merchant-account-labels`(읽기 전용 POST — 쓰기 효과·admin_request 없음, merchant-searches와 같은 형태).
                // 경로 목록 갱신이지 완화가 아니다 — 개통·한도·정지 경로는 여전히 없다.
                "POST /internal/v1/admin/merchant-account-labels",
                // 기대값 변경(MERCHANT-CODE-001 명세 §5.6.3·§7 MC-3b): 가맹점 내부 키 묶음 라벨 조회 `merchant-labels`(읽기 전용 POST — 쓰기 효과·admin_request 없음:
                // jOOQ RO·RR 한 문장, 라벨 permit, 감사 없음 — MerchantLabelApiSpec 전 표 checksum 불변·MerchantLabelArchitectureSpec 쓰기 호출 0, merchant-account-labels와 같은 형태).
                // 경로 목록 갱신이지 완화가 아니다 — 개통·한도·정지 경로는 여전히 없다.
                "POST /internal/v1/admin/merchant-labels",
                // 기대값 변경(BUSINESS-PROFILE-001 BIZ-2, 명세 §6.1): 사업자 조회 POST 2개(`searches`·`registration-number-lookups` — 읽기 전용, admin_request 없음, 조회 permit)와
                // 사업자 명령 3개(`commands/create`·`update`·`complete-registration` — 1인 OPERATOR 명령, D-BIZ-006 ①). 경로 목록 갱신이지 완화가 아니다 — 개통·한도·정지 경로는 여전히 없다.
                "POST /internal/v1/admin/businesses/searches",
                "POST /internal/v1/admin/businesses/registration-number-lookups",
                "POST /internal/v1/admin/businesses/commands/create",
                "POST /internal/v1/admin/businesses/commands/update",
                "POST /internal/v1/admin/businesses/commands/complete-registration",
            )
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 `merchant-operations/businesses` 제거 — 쓰기 경로가 하나 줄었다(완화 아님, 사업자 생성은 위 `businesses/commands/create`).
            nonGet shouldBe setOf("POST /internal/v1/admin/merchant-searches") + paths.map { "POST $it" }.toSet() + approvals
            nonGet.filter { it.contains("merchant-operations/businesses") } shouldBe emptyList()
        }

        Then("unsigned, foreign-key and stale calls are 401 UNAUTHENTICATED without reaching the use case (no admin_request row)") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            paths.forEach { path ->
                val body = MerchantOperationsBodies.registerMerchant("biz_01JC0000000000000000000001")
                val bytes = body.toByteArray(Charsets.UTF_8)
                listOf(
                    null,
                    MasterAdminChannelFixture.header("POST", path, bytes),
                    MasterAdminChannelFixture.springHeader("POST", path, bytes, t = java.time.Instant.now().epochSecond - 400),
                    MasterAdminChannelFixture.springHeader("POST", path, "{}".toByteArray()),
                ).forEach { header ->
                    val r = post(path, body, header)
                    withClue("$path $header") {
                        r.response.status shouldBe 401
                        r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                        r.response.getHeader("WWW-Authenticate") shouldBe null
                    }
                }
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }

        Then("malformed bodies are 400 REQUEST_MALFORMED without echoing the input and are not stored") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val r = post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant("biz_1"))
            r.response.status shouldBe 400
            r.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
            post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.withUpperCaseHost("mer_01JC0000000000000000000001")).body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
            post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.withActor("system:demo-seed")).response.status shouldBe 400
            // B1-2: Site 51개(C-2 상한 초과)·형식 아닌 시작 시각·모르는 서비스 종류도 400(저장 없음).
            val biz = "biz_01JC0000000000000000000001"
            val mer = "mer_01JC0000000000000000000001"
            post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.withTooManyScopeSites(biz, mer)).body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
            post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(biz, mer, from = "2026-10-10")).response.status shouldBe 400
            post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement("ctr_01JC0000000000000000000001", kind = "BANK_TRANSFER")).response.status shouldBe 400
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }
    }

    Given("business → merchant → site registration through the signed channel") {
        Then("each step answers 200 SUCCEEDED with the documented shape; replay is replayed=true with the same reference; a reused key is 409") {
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 사업자는 새 생성 경로(REGISTERED), 성공 모양·재생·409 단언은 가맹점 단계로 옮겼다(사업자 명령의 같은 단언은 BusinessProfileApiSpec BIZ-2-4).
            val businessId = registerBusiness()
            val okBefore = commandCount("RegisterMerchant", "SUCCEEDED")
            val s = Submission()
            val code = com.paycore.master.merchant.domain.MerchantCodeFixtures.unique().value
            val body = MerchantOperationsBodies.registerMerchant(businessId, s, code = code)
            val merchant = post(MerchantOperationsBodies.MERCHANTS, body)
            merchant.response.status shouldBe 200
            merchant.response.getHeader("Cache-Control")!! shouldContain "no-store"
            merchant.response.getHeader(AdminChannelResponses.REQUEST_ID_HEADER) shouldNotBe null
            json.readTree(merchant.body()).propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            merchant.field("outcome") shouldBe "SUCCEEDED"
            val merchantId = merchant.field("reference")
            merchantId shouldMatch Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
            json.readTree(merchant.body()).get("replayed").asBoolean() shouldBe false

            val replay = post(MerchantOperationsBodies.MERCHANTS, body)
            replay.body() shouldBe """{"outcome":"SUCCEEDED","reference":"$merchantId","replayed":true}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L

            val reused = post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(businessId, s, name = "다른 이름", code = code))
            reused.response.status shouldBe 409
            reused.body() shouldBe """{"error":"IDEMPOTENCY_KEY_REUSED"}"""
            commandCount("RegisterMerchant", "SUCCEEDED") shouldBe okBefore + 2.0
            commandCount("RegisterMerchant", "KEY_REUSED") shouldNotBe 0.0
            val revisionBefore = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId)

            val site = post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(merchantId))
            site.field("outcome") shouldBe "SUCCEEDED"
            site.field("reference") shouldMatch Regex("^site_[0-9A-HJKMNP-TV-Z]{26}$")
            // Financial Consistency(§7): Site 등록은 권리 입력 변경이라 revision +1, MID가 없으니 Snapshot 발행은 0.
            count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId) shouldBe revisionBefore + 1
            count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", merchantId) shouldBe 0L
            // MASTER 변경 이력의 actor는 ADMIN이 채운 admin:<계정 ID>(기록용 참조).
            OnboardingFixture.runtime(container).use { c ->
                c.scalar("SELECT actor_ref FROM master_change_history WHERE aggregate_id = ?", merchantId)
            } shouldBe "admin:${MerchantOperationsBodies.ACCOUNT}"
        }

        Then("C-12: a duplicate channel (another merchant's site) is 200 REJECTED without the other site id; the stored result has it (control)") {
            val host = OnboardingFixture.host()
            val owner = registerMerchant(registerBusiness())
            val existingSite = post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(owner, identifier = host)).field("reference")
            val other = registerMerchant(registerBusiness())
            val s = Submission()
            val rejected = post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(other, s, identifier = host))
            rejected.body() shouldBe """{"outcome":"REJECTED","rejection":"SITE_CHANNEL_DUPLICATE","replayed":false}"""
            rejected.body() shouldNotContain existingSite
            rejected.body() shouldNotContain host
            adminRequestReference(s.idempotencyKey) shouldBe existingSite
        }

        Then("unknown parents are stored business rejections (BUSINESS_NOT_FOUND, MERCHANT_NOT_FOUND)") {
            post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant("biz_01JC0000000000000000000000")).body() shouldBe
                """{"outcome":"REJECTED","rejection":"BUSINESS_NOT_FOUND","replayed":false}"""
            post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite("mer_01JC0000000000000000000000")).body() shouldBe
                """{"outcome":"REJECTED","rejection":"MERCHANT_NOT_FOUND","replayed":false}"""
        }

        Then("m-3: the same signed header and bytes replayed within the clock window add no rows and change no table (replayed=true)") {
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 운반 명령을 가맹점 등록으로(같은 단언).
            val body = MerchantOperationsBodies.registerMerchant(registerBusiness())
            val header = MasterAdminChannelFixture.springHeader("POST", MerchantOperationsBodies.MERCHANTS, body.toByteArray(Charsets.UTF_8))
            val first = post(MerchantOperationsBodies.MERCHANTS, body, header)
            first.field("outcome") shouldBe "SUCCEEDED"
            val before = snapshot()
            val second = post(MerchantOperationsBodies.MERCHANTS, body, header)
            second.body() shouldBe """{"outcome":"SUCCEEDED","reference":"${first.field("reference")}","replayed":true}"""
            snapshot() shouldBe before
        }
    }

    Given("B1-2: contract draft (Revision 1) → service agreement through the signed channel (§8.1 B1-2 1·2·3·5)") {
        fun registerSite(merchantId: String): String =
            post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(merchantId)).also { it.response.status shouldBe 200 }.field("reference")
        fun entitlement(merchantId: String) = listOf(
            count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId),
            count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", merchantId),
        )

        Then("MERCHANT and SITES scopes answer 200 SUCCEEDED ctr_ (unapproved Revision 1); replay is replayed=true; a reused key is 409; the CSA answers csa_; rights revision and Snapshot do not move") {
            val businessId = registerBusiness()
            val merchantId = registerMerchant(businessId)
            val siteId = registerSite(merchantId)
            val rightsBefore = entitlement(merchantId)

            val s = Submission()
            val body = MerchantOperationsBodies.registerContract(businessId, merchantId, s = s)
            val header = MasterAdminChannelFixture.springHeader("POST", MerchantOperationsBodies.CONTRACTS, body.toByteArray(Charsets.UTF_8))
            val first = post(MerchantOperationsBodies.CONTRACTS, body, header)
            first.response.status shouldBe 200
            first.response.getHeader("Cache-Control")!! shouldContain "no-store"
            json.readTree(first.body()).propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            first.field("outcome") shouldBe "SUCCEEDED"
            val contractId = first.field("reference")
            contractId shouldMatch Regex("^ctr_[0-9A-HJKMNP-TV-Z]{26}$")
            // m-3: 같은 서명 헤더·바이트를 창 안에서 다시 보내도 재생일 뿐 표가 바뀌지 않는다.
            val before = snapshot()
            post(MerchantOperationsBodies.CONTRACTS, body, header).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$contractId","replayed":true}"""
            snapshot() shouldBe before
            post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId, s = s, from = MerchantOperationsBodies.KST_TODAY)).let {
                it.response.status shouldBe 409
                it.body() shouldBe """{"error":"IDEMPOTENCY_KEY_REUSED"}"""
            }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
            OnboardingFixture.runtime(container).use { c ->
                c.scalar(
                    "SELECT CONCAT(scope_kind, '/', scope_merchant_id, '/', CAST(effective_from AS CHAR), '/', IFNULL(effective_to, 'open')) FROM contract_revision WHERE contract_id = ? AND revision_no = 1",
                    contractId,
                )
            } shouldBe "MERCHANT/$merchantId/2026-10-09 15:00:00.000/open"
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 0L

            val sites = post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(siteId), to = "2026-12-31T15:00:00.000Z"))
            sites.field("outcome") shouldBe "SUCCEEDED"
            val sitesContract = sites.field("reference")
            OnboardingFixture.runtime(container).use { c ->
                listOf(
                    c.scalar("SELECT scope_kind FROM contract_revision WHERE contract_id = ?", sitesContract),
                    c.scalar("SELECT merchant_site_id FROM contract_revision_scope_site WHERE contract_id = ?", sitesContract),
                    c.scalar("SELECT CAST(effective_to AS CHAR) FROM contract_revision WHERE contract_id = ?", sitesContract),
                )
            } shouldBe listOf("SITES", siteId, "2026-12-31 15:00:00.000")

            val agreement = post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement(contractId))
            agreement.response.status shouldBe 200
            json.readTree(agreement.body()).propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            val agreementId = agreement.field("reference")
            agreementId shouldMatch Regex("^csa_[0-9A-HJKMNP-TV-Z]{26}$")
            count("SELECT COUNT(*) FROM contract_service_agreement WHERE contract_id = ? AND service_kind = 'CARD_PG_PAYMENT'", contractId) shouldBe 1L

            // §8.1 B1-2-5: 계약 초안·CSA는 미승인이라 권리 입력이 아니다 — revision·Snapshot 불변(MID 없음).
            entitlement(merchantId) shouldBe rightsBefore
            OnboardingFixture.runtime(container).use { c ->
                c.scalar("SELECT actor_ref FROM master_change_history WHERE aggregate_id = ?", agreementId)
            } shouldBe "admin:${MerchantOperationsBodies.ACCOUNT}"

            // 같은 계약에 같은 서비스 종류 두 번 → 저장된 거절(C-12: reference 키·기존 CSA ID 없음 — 대조군: admin_request 결과에는 있다).
            val again = Submission()
            val duplicate = post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement(contractId, again))
            duplicate.body() shouldBe """{"outcome":"REJECTED","rejection":"SERVICE_KIND_DUPLICATE","replayed":false}"""
            duplicate.body() shouldNotContain agreementId
            adminRequestReference(again.idempotencyKey) shouldBe agreementId
            post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement(contractId, again)).body() shouldBe
                """{"outcome":"REJECTED","rejection":"SERVICE_KIND_DUPLICATE","replayed":true}"""
        }

        Then("§8.1 B1-2-3: a SITES scope listing another merchant's site is SCOPE_SITE_OF_OTHER_MERCHANT without a reference; an unknown contract is CONTRACT_NOT_FOUND") {
            val businessId = registerBusiness()
            val merchantId = registerMerchant(businessId)
            val sibling = registerMerchant(businessId)
            val foreignSite = registerSite(sibling)
            val rejected = post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(foreignSite)))
            rejected.body() shouldBe """{"outcome":"REJECTED","rejection":"SCOPE_SITE_OF_OTHER_MERCHANT","replayed":false}"""
            rejected.body() shouldNotContain foreignSite
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchantId) shouldBe 0L
            post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement("ctr_01JC0000000000000000000000")).body() shouldBe
                """{"outcome":"REJECTED","rejection":"CONTRACT_NOT_FOUND","replayed":false}"""
        }

        Then("§3.4 (D-MOPS-006 ⑤): yesterday (KST) is a stored CONTRACT_START_RETROACTIVE replayed for the same key with no contract row; today's KST midnight is accepted") {
            val businessId = registerBusiness()
            val merchantId = registerMerchant(businessId)
            val s = Submission()
            val body = MerchantOperationsBodies.registerContract(businessId, merchantId, s = s, from = MerchantOperationsBodies.KST_YESTERDAY)
            post(MerchantOperationsBodies.CONTRACTS, body).body() shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":false}"""
            post(MerchantOperationsBodies.CONTRACTS, body).body() shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":true}"""
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchantId) shouldBe 0L
            post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId, from = MerchantOperationsBodies.KST_TODAY)).field("outcome") shouldBe "SUCCEEDED"
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchantId) shouldBe 1L
        }

        Then("§8.2 B2-1-3·5 (D-MOPS-007 ②): a revision answers 200 SUCCEEDED ctr_…#2 (unapproved, rights unchanged); replay/409 as other commands; yesterday (KST) is a stored CONTRACT_START_RETROACTIVE without a reference; an unknown contract is CONTRACT_NOT_FOUND") {
            val businessId = registerBusiness()
            val merchantId = registerMerchant(businessId)
            val siteId = registerSite(merchantId)
            val contractId = post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId)).field("reference")
            val rightsBefore = entitlement(merchantId)
            val succeededBefore = commandCount("RegisterContractRevision", "SUCCEEDED")

            val s = Submission()
            val body = MerchantOperationsBodies.registerContractRevision(contractId, merchantId, listOf(siteId), s = s, to = "2026-12-31T15:00:00.000Z")
            val first = post(MerchantOperationsBodies.CONTRACT_REVISIONS, body)
            first.response.status shouldBe 200
            first.response.getHeader("Cache-Control")!! shouldContain "no-store"
            json.readTree(first.body()).propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            first.field("reference") shouldBe "$contractId#2"
            post(MerchantOperationsBodies.CONTRACT_REVISIONS, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$contractId#2","replayed":true}"""
            post(MerchantOperationsBodies.CONTRACT_REVISIONS, MerchantOperationsBodies.registerContractRevision(contractId, merchantId, s = s)).let {
                it.response.status shouldBe 409
                it.body() shouldBe """{"error":"IDEMPOTENCY_KEY_REUSED"}"""
            }
            OnboardingFixture.runtime(container).use { c ->
                listOf(
                    c.scalar("SELECT scope_kind FROM contract_revision WHERE contract_id = ? AND revision_no = 2", contractId),
                    c.scalar("SELECT merchant_site_id FROM contract_revision_scope_site WHERE contract_id = ? AND revision_no = 2", contractId),
                    c.scalar("SELECT registered_by FROM contract_revision WHERE contract_id = ? AND revision_no = 2", contractId),
                )
            } shouldBe listOf("SITES", siteId, "admin:${MerchantOperationsBodies.ACCOUNT}")
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 0L
            // §8.2 B2-1-5: 미승인 Revision은 권리 입력이 아니다.
            entitlement(merchantId) shouldBe rightsBefore

            val retro = Submission()
            val retroBody = MerchantOperationsBodies.registerContractRevision(contractId, merchantId, s = retro, from = MerchantOperationsBodies.KST_YESTERDAY)
            post(MerchantOperationsBodies.CONTRACT_REVISIONS, retroBody).body() shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":false}"""
            post(MerchantOperationsBodies.CONTRACT_REVISIONS, retroBody).body() shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_START_RETROACTIVE","replayed":true}"""
            count("SELECT COUNT(*) FROM contract_revision WHERE contract_id = ?", contractId) shouldBe 2L
            post(MerchantOperationsBodies.CONTRACT_REVISIONS, MerchantOperationsBodies.registerContractRevision(contractId, merchantId, from = MerchantOperationsBodies.KST_TODAY))
                .field("reference") shouldBe "$contractId#3"

            post(MerchantOperationsBodies.CONTRACT_REVISIONS, MerchantOperationsBodies.registerContractRevision("ctr_01JC0000000000000000000000", merchantId)).body() shouldBe
                """{"outcome":"REJECTED","rejection":"CONTRACT_NOT_FOUND","replayed":false}"""
            // 지표는 저장된 판정 응답마다 센다(재생 포함): 첫 성공 + 재생 + #3 = 3.
            commandCount("RegisterContractRevision", "SUCCEEDED") shouldBe succeededBefore + 3.0
        }

        Then("Idempotency (review NIT): the same key with effectiveTo omitted vs null and with scopeSiteIds in another order is a replay (replayed=true, same ctr_), not 409; no new row") {
            val businessId = registerBusiness()
            val merchantId = registerMerchant(businessId)
            val siteA = registerSite(merchantId)
            val siteB = registerSite(merchantId)
            val s = Submission()
            val first = post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(siteA, siteB), s = s))
            first.field("outcome") shouldBe "SUCCEEDED"
            val contractId = first.field("reference")
            val replay = """{"outcome":"SUCCEEDED","reference":"$contractId","replayed":true}"""
            val withNullEnd = MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(siteA, siteB), s = s).dropLast(1) + ""","effectiveTo":null}"""
            val reordered = MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(siteB, siteA), s = s)
            val both = MerchantOperationsBodies.registerContract(businessId, merchantId, listOf(siteB, siteA), s = s).dropLast(1) + ""","effectiveTo":null}"""
            listOf(withNullEnd, reordered, both).forEach { body ->
                withClue(body) {
                    post(MerchantOperationsBodies.CONTRACTS, body).let {
                        it.response.status shouldBe 200
                        it.body() shouldBe replay
                    }
                }
            }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchantId) shouldBe 1L
        }

        Then("C-1: with every write permit held the contract and agreement commands are 503 ADMIN_WRITE_BUSY at once (no row)") {
            val writes = MasterAdminChannelFixture.springSettings().writePermits
            val held = CountDownLatch(writes)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writes)
            val before = count("SELECT COUNT(*) FROM admin_request")
            try {
                repeat(writes) { pool.submit { permits.write("hold", { error("permit expected") }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } } }
                held.await(5, TimeUnit.SECONDS) shouldBe true
                listOf(
                    MerchantOperationsBodies.CONTRACTS to MerchantOperationsBodies.registerContract("biz_01JC0000000000000000000001", "mer_01JC0000000000000000000001"),
                    MerchantOperationsBodies.AGREEMENTS to MerchantOperationsBodies.addServiceAgreement("ctr_01JC0000000000000000000001"),
                ).forEach { (path, body) ->
                    val busy = post(path, body)
                    withClue(path) {
                        busy.response.status shouldBe 503
                        busy.body() shouldBe """{"error":"ADMIN_WRITE_BUSY"}"""
                    }
                }
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
            listOf("RegisterContract", "AddServiceAgreement").forEach { op ->
                (registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", op).counter()?.count() ?: 0.0) shouldNotBe 0.0
            }
        }

        Then("use-case failures of the two new commands map to the same 503 codes; logs carry the mos. correlation id but never the MASTER key; metrics per operation") {
            val canary = "CANARY-B1-2-DRIVER-TEXT"
            fun failing(e: RuntimeException) = object : MerchantOnboardingUseCase {
                override fun registerMerchant(command: RegisterMerchantCommand): AdminCommandResult = error("not used")
                override fun registerMerchantSite(command: RegisterMerchantSiteCommand): AdminCommandResult = error("not used")
                override fun registerContract(command: RegisterContractCommand): AdminCommandResult = throw e
                override fun registerContractRevision(command: RegisterContractRevisionCommand): AdminCommandResult = error("not used")
                override fun approveContractRevision(command: ApproveContractRevisionCommand): AdminCommandResult = error("not used")
                override fun approveContractRevisionWithEvidence(command: ApprovedContractRevisionApprovalCommand): AdminCommandResult = error("not used")
                override fun issueFirstDefaultMerchantAccountWithEvidence(command: com.paycore.master.merchant.application.provided.ApprovedFirstDefaultIssuanceCommand): AdminCommandResult = error("not used")
                override fun addServiceAgreement(command: AddServiceAgreementCommand): AdminCommandResult = throw e
                override fun issueFirstDefaultMerchantAccount(command: IssueFirstDefaultMerchantAccountCommand): AdminCommandResult = error("not used")
                override fun registerPaymentMethodActivation(command: RegisterPaymentMethodActivationCommand): AdminCommandResult = error("not used")
                override fun changeMerchantCodeWithEvidence(command: com.paycore.master.merchant.application.provided.ApprovedMerchantCodeChangeCommand): AdminCommandResult = error("not used")
            }
            val cases = listOf(
                AdminCommandFailure.RequestInProgress(IllegalStateException(canary)) to "REQUEST_IN_PROGRESS",
                AdminCommandFailure.OutcomeUnknown(IllegalStateException(canary)) to "OUTCOME_UNKNOWN",
                AdminCommandFailure.TemporarilyUnavailable(IllegalStateException(canary)) to "TEMPORARILY_UNAVAILABLE",
                AdminCommandFailure.IdentifierCollision("IDENTIFIER") to "TEMPORARILY_UNAVAILABLE",
                IllegalStateException("driver text with $canary") to "TEMPORARILY_UNAVAILABLE",
            )
            val metrics = SimpleMeterRegistry()
            val submissions = mutableListOf<Submission>()
            val lines = CapturedLogs().use { logs ->
                cases.forEach { (e, code) ->
                    val controller = MerchantOperationsController(failing(e), AdminChannelPermits(MasterAdminChannelFixture.springSettings(), metrics), metrics)
                    fun request(path: String, body: String) = MockHttpServletRequest("POST", path).apply { setContent(body.toByteArray(Charsets.UTF_8)) }
                    val contract = controller.registerContract(
                        request(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract("biz_01JC0000000000000000000001", "mer_01JC0000000000000000000001", s = Submission().also { submissions += it })),
                    )
                    val agreement = controller.addServiceAgreement(
                        request(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement("ctr_01JC0000000000000000000001", Submission().also { submissions += it })),
                    )
                    listOf(contract, agreement).forEach { response ->
                        withClue(code) {
                            response.statusCode.value() shouldBe 503
                            String(response.body!!, Charsets.UTF_8) shouldBe """{"error":"$code"}"""
                        }
                    }
                }
                logs.lines()
            }
            listOf("RegisterContract", "AddServiceAgreement").forEach { op ->
                lines.count { "operation=$op" in it && "correlationId=mos." in it } shouldBe cases.size
                metrics.find(MerchantOperationsController.COMMAND_METRIC).tags("operation", op, "outcome", "FAILED").counter()!!.count() shouldBe cases.size.toDouble()
            }
            lines.filter { canary in it }.shouldBeEmpty()
            submissions.forEach { s -> lines.filter { s.idempotencyKey in it }.shouldBeEmpty() }
        }
    }

    Given("bulkhead and failure classification") {
        Then("C-1: with every write permit held a signed command is 503 ADMIN_WRITE_BUSY at once (no row), while a read call still runs") {
            val writes = MasterAdminChannelFixture.springSettings().writePermits
            val busyBusiness = registerBusiness()
            val held = CountDownLatch(writes)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writes)
            val before = count("SELECT COUNT(*) FROM admin_request")
            try {
                repeat(writes) { pool.submit { permits.write("hold", { error("permit expected") }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } } }
                held.await(5, TimeUnit.SECONDS) shouldBe true
                val started = System.nanoTime()
                // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 운반 명령을 가맹점 등록으로(같은 단언).
                val busy = post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(busyBusiness))
                busy.response.status shouldBe 503
                busy.body() shouldBe """{"error":"ADMIN_WRITE_BUSY"}"""
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000) shouldBe true
                val read = "/internal/v1/admin/merchants/mer_00000000000000000000000000"
                mvc.perform(MockMvcRequestBuilders.get(read).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", read))).andReturn().response.status shouldBe 404
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
            (registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "RegisterMerchant").counter()?.count() ?: 0.0) shouldNotBe 0.0
            post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(busyBusiness)).response.status shouldBe 200
        }

        Then("use-case failures map to 503 codes (same key again); an unexpected exception's message (possibly bind values) is never logged") {
            val canary = "9988776655"
            fun failing(e: RuntimeException) = object : MerchantOnboardingUseCase {
                override fun registerMerchant(command: RegisterMerchantCommand): AdminCommandResult = throw e
                override fun registerMerchantSite(command: RegisterMerchantSiteCommand): AdminCommandResult = throw e
                override fun registerContract(command: RegisterContractCommand): AdminCommandResult = error("not used")
                override fun registerContractRevision(command: RegisterContractRevisionCommand): AdminCommandResult = error("not used")
                override fun approveContractRevision(command: ApproveContractRevisionCommand): AdminCommandResult = error("not used")
                override fun approveContractRevisionWithEvidence(command: ApprovedContractRevisionApprovalCommand): AdminCommandResult = error("not used")
                override fun issueFirstDefaultMerchantAccountWithEvidence(command: com.paycore.master.merchant.application.provided.ApprovedFirstDefaultIssuanceCommand): AdminCommandResult = error("not used")
                override fun addServiceAgreement(command: AddServiceAgreementCommand): AdminCommandResult = error("not used")
                override fun issueFirstDefaultMerchantAccount(command: IssueFirstDefaultMerchantAccountCommand): AdminCommandResult = error("not used")
                override fun registerPaymentMethodActivation(command: RegisterPaymentMethodActivationCommand): AdminCommandResult = error("not used")
                override fun changeMerchantCodeWithEvidence(command: com.paycore.master.merchant.application.provided.ApprovedMerchantCodeChangeCommand): AdminCommandResult = error("not used")
            }
            val cases = listOf(
                AdminCommandFailure.RequestInProgress(IllegalStateException(canary)) to "REQUEST_IN_PROGRESS",
                AdminCommandFailure.OutcomeUnknown(IllegalStateException(canary)) to "OUTCOME_UNKNOWN",
                AdminCommandFailure.TemporarilyUnavailable(IllegalStateException(canary)) to "TEMPORARILY_UNAVAILABLE",
                AdminCommandFailure.IdentifierCollision("IDENTIFIER") to "TEMPORARILY_UNAVAILABLE",
                IllegalStateException("driver text with $canary") to "TEMPORARILY_UNAVAILABLE",
            )
            val metrics = SimpleMeterRegistry()
            val lines = CapturedLogs().use { logs ->
                cases.forEach { (e, code) ->
                    val controller = MerchantOperationsController(failing(e), AdminChannelPermits(MasterAdminChannelFixture.springSettings(), metrics), metrics)
                    // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 운반 명령을 가맹점 등록으로(같은 단언 — 예외 문구의 canary는 어디에도 기록되지 않는다).
                    val request = MockHttpServletRequest("POST", MerchantOperationsBodies.MERCHANTS).apply {
                        setContent(MerchantOperationsBodies.registerMerchant("biz_01JC0000000000000000000001", name = "가맹점 $canary").toByteArray(Charsets.UTF_8))
                    }
                    val response = controller.registerMerchant(request)
                    withClue(code) {
                        response.statusCode.value() shouldBe 503
                        String(response.body!!, Charsets.UTF_8) shouldBe """{"error":"$code"}"""
                    }
                }
                logs.lines()
            }
            lines.count { "operation=RegisterMerchant" in it && "correlationId=mos." in it } shouldBe cases.size
            lines.filter { canary in it }.shouldBeEmpty()
            metrics.find(MerchantOperationsController.COMMAND_METRIC).tags("operation", "RegisterMerchant", "outcome", "FAILED").counter()!!.count() shouldBe cases.size.toDouble()
        }
    }

    Given("non-exposure in logs (§7 Observability)") {
        Then("no signing key, signature header or v1 value, MASTER idempotency key, registration number or channel identifier in any log line; the mos. correlation id is there (control)") {
            val brn = OnboardingFixture.registrationNumber().value
            val host = OnboardingFixture.host()
            val submissions = mutableListOf<Submission>()
            val headers = mutableListOf<String>()
            fun signedPost(path: String, body: String): MvcResult {
                val header = MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))
                headers += header
                return post(path, body, header)
            }
            val lines = CapturedLogs().use { logs ->
                // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 사업자는 새 생성 경로(번호가 오가는 명령 — 로그에 번호 없음 단언 유지), 재생·거절 운반은 가맹점·Site 명령으로(같은 5건).
                val businessId = signedPost(BusinessBodies.CREATE, BusinessBodies.create(Submission().also { submissions += it }, number = brn)).field("reference")
                val s1 = Submission().also { submissions += it }
                val merchantBody = MerchantOperationsBodies.registerMerchant(businessId, s1)
                val merchantId = signedPost(MerchantOperationsBodies.MERCHANTS, merchantBody).field("reference")
                signedPost(MerchantOperationsBodies.MERCHANTS, merchantBody).field("outcome") shouldBe "SUCCEEDED"
                val other = signedPost(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(businessId, Submission().also { submissions += it })).field("reference")
                signedPost(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(merchantId, Submission().also { submissions += it }, identifier = host)).field("outcome") shouldBe "SUCCEEDED"
                signedPost(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(other, Submission().also { submissions += it }, identifier = host)).field("outcome") shouldBe "REJECTED"
                post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(businessId), MasterAdminChannelFixture.header("POST", MerchantOperationsBodies.MERCHANTS, ByteArray(0)))
                    .response.status shouldBe 401
                logs.lines()
            }
            lines.count { "correlationId=mos." in it && ("operation=RegisterMerchant" in it || "operation=RegisterMerchantSite" in it) } shouldBe 5
            val secrets = listOf(MasterAdminChannelFixture.SPRING_SECRET, MasterAdminChannelFixture.secret(MasterAdminChannelFixture.K1))
            val v1s = headers.map { it.substringAfter("v1=") }
            (secrets + headers + v1s + submissions.map { it.idempotencyKey } + brn + host).forEach { s -> lines.filter { s in it }.shouldBeEmpty() }
        }
    }
})
