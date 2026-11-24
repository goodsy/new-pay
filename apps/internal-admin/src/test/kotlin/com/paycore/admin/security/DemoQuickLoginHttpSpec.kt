package com.paycore.admin.security

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.Base64

/**
 * 데모 간편 로그인(ADMIN-DEMO-LOGIN-001 — T-1 켜짐·T-4~T-8, D-ADMIN-AUTH-DEMO-001). 시험 설정은 DEMO 모드, 여기서만 enabled=true + 허용 계정 목록.
 * 세션은 일반 로그인과 같은 규칙(세션 ID·CSRF 재발급, 쿠키 속성, idle, 새 로그인 우선, 역할 = 계정 현재 행), 거부는 원인 비구분 401, 감사는 방식(`method=DEMO_QUICK`)을 남긴다.
 * 응답·감사에 비밀값 없음(이 시험도 비밀번호·TOTP를 출력하지 않는다).
 */
@SpringBootTest(properties = ["paycore.admin.demo-quick-login.enabled=true", DemoQuickLoginTestConfig.ACCOUNTS])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, SwitchableAuditStoreConfig::class)
class DemoQuickLoginHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val accounts: AdminAccountRepository,
    private val settings: AdminSecuritySettings,
    private val commands: AdminAccountCommandUseCase,
    private val container: MySQLContainer,
    private val auditStore: SwitchableAuditStore,
) : BehaviorSpec({

    afterEach { auditStore.failOn(null) }

    val approver = ready.bootstrapped(AdminRole.APPROVER, login = "dq-approver")
    val securityAdmin = ready.bootstrapped(AdminRole.SECURITY_ADMIN, login = "dq-sa")
    val locked = ready.withoutRoles(login = "dq-locked")
    val suspended = ready.withoutRoles(login = "dq-suspended")
    val replaced = ready.bootstrapped(AdminRole.APPROVER, login = "dq-replaced")
    val outside = ready.withoutRoles(login = "dq-outside")
    val limit = ready.withoutRoles(login = "dq-limit")
    val relogin = ready.withoutRoles(login = "dq-relogin")
    val pending: AdminAccount = storage.save(AdminAccountFixture.pending(login = "dq-pending"))

    storage.inTx {
        val current = accounts.lock(locked.account.id)!!
        var account = current.value
        repeat(settings.loginAttemptPolicy.maxFailures) { account = account.recordAuthenticationFailure(settings.loginAttemptPolicy, Instant.now()).account }
        accounts.update(current, account)
    }
    commands.suspend(TargetedAccountCommand(storage.securityAdmin().id.value, suspended.account.id.value, AdminRequestContext("corr-dq-susp", null))) shouldBe
        AccountCommandResult.Succeeded(suspended.account.id.value)

    fun client(remoteAddr: String = "127.0.0.1") = AdminHttpClient(mockMvc, remoteAddr)
    fun quick(c: AdminHttpClient, loginName: String, xsrf: Boolean = true): MockHttpServletResponse =
        c.perform(post("/api/auth/demo-quick-login").contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json("loginName" to loginName)), xsrf = xsrf)
    fun sessionId(cookieValue: String): String = String(Base64.getDecoder().decode(cookieValue))
    fun root() = AdminDbAccounts.root(container)
    fun auditOf(actorId: String, action: String): List<String?> =
        root().use { it.column("SELECT CONCAT(result_code, '|', COALESCE(detail, '')) FROM admin_audit_event WHERE actor_id = ? AND action = ? ORDER BY event_id", actorId, action) }
    fun anonymousRejections(): Long = root().use {
        (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_kind = 'ANONYMOUS' AND action = 'LOGIN_DEMO_QUICK' AND result_code = 'REJECTED' AND login_name_hmac IS NOT NULL") as Number).toLong()
    }
    fun generationOf(account: AdminAccount): Long = storage.load(account.id).sessionGeneration

    Given("the options endpoint (T-8)") {
        Then("lists configured accounts that exist, in configuration order, with display name, current roles and availability — no secrets") {
            val response = client().perform(get("/api/auth/demo-quick-login"))
            response.status shouldBe 200
            response.getHeader("Cache-Control").orEmpty() shouldContain "no-store"
            // 설정 순서의 앞쪽 dq- 묶음(뒤에는 같은 컨텍스트의 다른 spec 계정 su-·mo-가 실행 순서에 따라 붙을 수 있다 — DemoQuickLoginTestConfig).
            response.contentAsString.startsWith(
                """{"accounts":[""" +
                    """{"loginName":"dq-approver","displayName":"${approver.account.displayName.value}","roles":["APPROVER"],"available":true},""" +
                    """{"loginName":"dq-sa","displayName":"${securityAdmin.account.displayName.value}","roles":["SECURITY_ADMIN"],"available":true},""" +
                    """{"loginName":"dq-locked","displayName":"${locked.account.displayName.value}","roles":[],"available":false},""" +
                    """{"loginName":"dq-suspended","displayName":"${suspended.account.displayName.value}","roles":[],"available":false},""" +
                    """{"loginName":"dq-pending","displayName":"${pending.displayName.value}","roles":[],"available":false},""" +
                    """{"loginName":"dq-replaced","displayName":"${replaced.account.displayName.value}","roles":["APPROVER"],"available":true},""" +
                    """{"loginName":"dq-limit","displayName":"${limit.account.displayName.value}","roles":[],"available":true},""" +
                    """{"loginName":"dq-relogin","displayName":"${relogin.account.displayName.value}","roles":[],"available":true}""",
            ) shouldBe true
            response.contentAsString.endsWith("]}") shouldBe true
            response.contentAsString shouldNotContain "dq-outside"
            response.contentAsString shouldNotContain "dq-missing"
        }
    }

    Given("a quick login of an allowed ACTIVE account (T-5·T-6)") {
        Then("issues a normal session: session ID and CSRF token renewed, cookie attributes, 15-minute idle, current roles; audited with the method") {
            val c = client()
            c.csrf()
            val anonymousSession = c.sessionCookie
            val anonymousToken = c.xsrfToken
            val before = generationOf(approver.account)
            val response = quick(c, "dq-approver")
            response.status shouldBe 200
            // 결정 ③: 간편 로그인 세션은 `/api/auth/me`와 같은 본문 + "demoQuickLogin":true(일반 세션 본문에는 없음 — 아래 normal 시험).
            response.contentAsString shouldBe
                """{"actorId":"${approver.account.id.value}","displayName":"${approver.account.displayName.value}","roles":["APPROVER"],"demoQuickLogin":true}"""
            (c.sessionCookie != anonymousSession && c.xsrfToken != anonymousToken) shouldBe true
            val headers = response.getHeaders("Set-Cookie")
            val session = headers.single { it.startsWith("${AdminHttpClient.SESSION_COOKIE}=") }
            val xsrf = headers.single { it.startsWith("${AdminHttpClient.XSRF_COOKIE}=") }
            listOf("Path=/", "Secure", "HttpOnly", "SameSite=Strict").forEach { session shouldContain it }
            listOf("Path=/", "Secure", "SameSite=Strict").forEach { xsrf shouldContain it }
            xsrf shouldNotContain "HttpOnly"
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_session WHERE SESSION_ID = ?", sessionId(anonymousSession!!)) as Number).toLong() } shouldBe 0L
            root().use { (it.scalar("SELECT MAX_INACTIVE_INTERVAL FROM admin_session WHERE SESSION_ID = ?", sessionId(c.sessionCookie!!)) as Number).toInt() } shouldBe 900
            generationOf(approver.account) shouldBe before + 1
            c.perform(get("/api/auth/me")).contentAsString shouldBe
                """{"actorId":"${approver.account.id.value}","displayName":"${approver.account.displayName.value}","roles":["APPROVER"],"demoQuickLogin":true}"""
            root().use {
                it.column(
                    "SELECT CONVERT(a.ATTRIBUTE_BYTES USING utf8mb4) FROM admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID " +
                        "WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'paycore.admin.loginMethod'",
                    sessionId(c.sessionCookie!!),
                )
            } shouldBe listOf("DEMO_QUICK")
            auditOf(approver.account.id.value, "LOGIN_SUCCEEDED").last() shouldBe "SUCCESS|method=DEMO_QUICK"
            // 새 CSRF 토큰으로 상태 변경 요청이 된다(로그아웃 204), 옛 익명 토큰으로는 안 된다(403).
            val stale = AdminHttpClient(mockMvc).also { it.cookies.putAll(c.cookies); it.cookies[AdminHttpClient.XSRF_COOKIE] = anonymousToken!! }
            stale.perform(post("/api/auth/logout")).status shouldBe 403
            c.perform(post("/api/auth/logout")).status shouldBe 204
            c.perform(get("/api/auth/me")).status shouldBe 401
        }
        Then("a normal login success keeps its audit detail (no method marker), its /api/auth/me body has no demo flag and its session has no login-method attribute") {
            val normal = ready.withoutRoles()
            val c = client()
            c.login(normal.loginName, normal.password, normal.secret).status shouldBe 200
            auditOf(normal.account.id.value, "LOGIN_SUCCEEDED") shouldBe listOf("SUCCESS|")
            c.perform(get("/api/auth/me")).contentAsString shouldBe
                """{"actorId":"${normal.account.id.value}","displayName":"${normal.account.displayName.value}","roles":[]}"""
            root().use {
                (it.scalar(
                    "SELECT COUNT(*) FROM admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID " +
                        "WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'paycore.admin.loginMethod'",
                    sessionId(c.sessionCookie!!),
                ) as Number).toLong()
            } shouldBe 0L
        }
        Then("review MINOR-1: when the success audit cannot be stored the whole login rolls back — 500, generation unchanged, no authenticated session") {
            val before = generationOf(securityAdmin.account)
            val successes = auditOf(securityAdmin.account.id.value, "LOGIN_SUCCEEDED").size
            val c = client().also { it.csrf() }
            auditStore.failOn { it.action == "LOGIN_SUCCEEDED" && it.detail == "method=DEMO_QUICK" }
            quick(c, "dq-sa").let { it.status to it.contentAsString } shouldBe (500 to """{"error":"INTERNAL_ERROR"}""")
            auditStore.failOn(null)
            generationOf(securityAdmin.account) shouldBe before
            c.perform(get("/api/auth/me")).status shouldBe 401
            auditOf(securityAdmin.account.id.value, "LOGIN_SUCCEEDED").size shouldBe successes
        }
        Then("review NIT-b: a normal password + TOTP login in the same browser after a quick login clears the marker (no demo flag, no attribute)") {
            val c = client().also { it.csrf() }
            quick(c, "dq-relogin").status shouldBe 200
            c.login(relogin.loginName, relogin.password, relogin.secret).status shouldBe 200
            c.perform(get("/api/auth/me")).contentAsString shouldBe
                """{"actorId":"${relogin.account.id.value}","displayName":"${relogin.account.displayName.value}","roles":[]}"""
            root().use {
                (it.scalar(
                    "SELECT COUNT(*) FROM admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID " +
                        "WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'paycore.admin.loginMethod'",
                    sessionId(c.sessionCookie!!),
                ) as Number).toLong()
            } shouldBe 0L
        }
        Then("the newer login wins: a quick login replaces the account's earlier normal session (401 SESSION_REPLACED)") {
            val first = client()
            first.login(replaced.loginName, replaced.password, replaced.secret).status shouldBe 200
            val second = client()
            second.csrf()
            quick(second, "dq-replaced").status shouldBe 200
            first.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_REPLACED"}""")
            second.perform(get("/api/auth/me")).status shouldBe 200
        }
        Then("the session is the account's own: role-dependent APIs follow the current roles (login names only for SECURITY_ADMIN — D-ADMIN1-017)") {
            val sa = client().also { it.csrf() }
            quick(sa, "dq-sa").status shouldBe 200
            sa.perform(get("/api/accounts")).let { it.status to it.contentAsString.contains("\"loginName\"") } shouldBe (200 to true)
            val ap = client().also { it.csrf() }
            quick(ap, "dq-approver").status shouldBe 200
            ap.perform(get("/api/accounts")).let { it.status to it.contentAsString.contains("\"loginName\"") } shouldBe (200 to false)
        }
        Then("without the CSRF header, or without a session (no stored token), the POST is 403 — neither authenticates") {
            val before = generationOf(securityAdmin.account)
            val c = client()
            c.csrf()
            quick(c, "dq-sa", xsrf = false).status shouldBe 403
            c.perform(get("/api/auth/me")).status shouldBe 401
            val noSession = client()
            noSession.cookies[AdminHttpClient.XSRF_COOKIE] = "x"
            quick(noSession, "dq-sa").status shouldBe 403
            generationOf(securityAdmin.account) shouldBe before
        }
    }

    Given("refusals (T-4)") {
        Then("outside the allowlist (a real account), a listed name with no account and a malformed name: same 401, anonymous audit, no account change") {
            val before = anonymousRejections()
            val generation = generationOf(outside.account)
            listOf("dq-outside", "dq-missing", "Not A Name").map { name ->
                val c = client().also { it.csrf() }
                quick(c, name).let { it.status to it.contentAsString }
            } shouldBe List(3) { 401 to """{"error":"INVALID_CREDENTIALS"}""" }
            anonymousRejections() shouldBe before + 3
            generationOf(outside.account) shouldBe generation
            auditOf(outside.account.id.value, "LOGIN_SUCCEEDED") shouldBe emptyList()
        }
        Then("LOCKED, SUSPENDED and PENDING_ENROLLMENT accounts are refused with the same 401 and audited as NOT_ACTIVE; nothing changes") {
            listOf(locked.account, suspended.account, pending).forEach { account ->
                val stored = storage.load(account.id)
                val c = client().also { it.csrf() }
                quick(c, account.loginName.value).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"INVALID_CREDENTIALS"}""")
                c.perform(get("/api/auth/me")).status shouldBe 401
                storage.load(account.id).let { (it.sessionGeneration to it.failedAttempts) } shouldBe (stored.sessionGeneration to stored.failedAttempts)
                auditOf(account.id.value, "LOGIN_DEMO_QUICK").last() shouldBe "NOT_COUNTED|reason=NOT_ACTIVE"
            }
        }
    }

    Given("an ACTIVE account whose failure count already reached the limit (the limit was lowered — §4.3.2(b))") {
        Then("is refused with the same 401, audited NOT_COUNTED reason=LIMIT, and its count is not reset") {
            val max = settings.loginAttemptPolicy.maxFailures
            root().use { it.update("UPDATE admin_account SET failed_attempts = ?, version = version + 1 WHERE actor_id = ?", max, limit.account.id.value) }
            val c = client().also { it.csrf() }
            quick(c, "dq-limit").let { it.status to it.contentAsString } shouldBe (401 to """{"error":"INVALID_CREDENTIALS"}""")
            auditOf(limit.account.id.value, "LOGIN_DEMO_QUICK").last() shouldBe "NOT_COUNTED|reason=LIMIT"
            storage.load(limit.account.id).failedAttempts shouldBe max
            client().perform(get("/api/auth/demo-quick-login")).contentAsString shouldContain
                """{"loginName":"dq-limit","displayName":"${limit.account.displayName.value}","roles":[],"available":false}"""
        }
    }

    Given("the IP allowlist (T-7)") {
        Then("an address outside the list is refused before the endpoint (403 IP_DENIED) for both methods") {
            val c = client(remoteAddr = "10.9.9.9")
            c.perform(get("/api/auth/demo-quick-login")).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"IP_DENIED"}""")
            quick(c, "dq-sa", xsrf = false).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"IP_DENIED"}""")
        }
    }
})
