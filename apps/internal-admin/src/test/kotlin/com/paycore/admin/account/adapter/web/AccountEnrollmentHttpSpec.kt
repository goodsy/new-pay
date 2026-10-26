package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.column
import org.testcontainers.mysql.MySQLContainer
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.TestTotp
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.util.UUID

/**
 * 계정 관리·등록 HTTP(S7 — 화면이 쓰는 JSON API, §4.10): `GET/POST /api/accounts`, 1인 명령(정지·`ENROLL` 재발급·역할 회수), 등록 흐름 `POST /api/enroll/{start,password,totp,complete}`.
 * 등록 진행 상태는 서버 세션의 **문자열 속성**(암호문 base64 — D-40·D-74)에만, 시작 때 세션 ID·CSRF 재발급, 완료 때 세션 폐기. 코드 원문은 생성·재발급 응답에만(no-store).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AccountEnrollmentHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun json(vararg pairs: Pair<String, String>) = AdminHttpClient.json(*pairs)
    fun AdminHttpClient.postJson(path: String, vararg pairs: Pair<String, String>): MockHttpServletResponse =
        perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json(*pairs)))
    fun field(body: String, name: String): String = Regex("\"$name\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    fun login() = "web-" + UUID.randomUUID().toString().take(8)

    Given("a SECURITY_ADMIN creating an account and the new owner enrolling with the code") {
        Then("the code is only in the creation response (no-store); the list never shows it; enrollment runs on string session state and the account can then log in") {
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            val name = login()
            val created = sa.postJson("/api/accounts", "loginName" to name, "displayName" to "웹 생성")
            (created.status to created.getHeader("Cache-Control")) shouldBe (201 to "no-store")
            val code = field(created.contentAsString, "enrollmentCode")
            val list = sa.perform(get("/api/accounts"))
            list.status shouldBe 200
            list.contentAsString shouldContain name
            list.contentAsString shouldNotContain code
            list.contentAsString shouldNotContain "password"

            val browser = AdminHttpClient(mockMvc).also { it.csrf() }
            val before = browser.sessionCookie
            browser.postJson("/api/enroll/start", "code" to code).let { it.status to it.contentAsString } shouldBe
                (200 to """{"purpose":"ENROLL","needsPassword":true,"needsTotp":true}""")
            browser.sessionCookie shouldNotBe before
            browser.postJson("/api/enroll/password", "password" to "web-enroll-password-1").status shouldBe 204
            val totp = browser.perform(post("/api/enroll/totp"))
            (totp.status to totp.getHeader("Cache-Control")) shouldBe (200 to "no-store")
            val uri = field(totp.contentAsString, "otpauthUri").replace("\\u0026", "&")
            uri shouldStartWith "otpauth://totp/"
            val secret = TestTotp.secretOf(uri)
            browser.postJson("/api/enroll/complete", "totpCode" to TestTotp.code(secret)).let { it.status to it.contentAsString } shouldBe
                (200 to """{"status":"COMPLETED"}""")
            browser.sessionCookie shouldBe null
            AdminHttpClient(mockMvc).login(name, "web-enroll-password-1", secret, Instant.now().plusSeconds(30)).status shouldBe 200
        }
        Then("a wrong code is 400 ENROLLMENT_CODE_INVALID; completing without the required steps is 400 ENROLLMENT_STEP_INVALID; a short password is 400 PASSWORD_POLICY") {
            val browser = AdminHttpClient(mockMvc).also { it.csrf() }
            browser.postJson("/api/enroll/start", "code" to "A".repeat(32)).let { it.status to it.contentAsString } shouldBe (400 to """{"error":"ENROLLMENT_CODE_INVALID"}""")
            browser.postJson("/api/enroll/complete").let { it.status to it.contentAsString } shouldBe (400 to """{"error":"ENROLLMENT_STEP_INVALID"}""")
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            val code = field(sa.postJson("/api/accounts", "loginName" to login(), "displayName" to "짧은 비번").contentAsString, "enrollmentCode")
            val b2 = AdminHttpClient(mockMvc).also { it.csrf() }
            b2.postJson("/api/enroll/start", "code" to code).status shouldBe 200
            b2.postJson("/api/enroll/password", "password" to "short").let { it.status to it.contentAsString } shouldBe (400 to """{"error":"PASSWORD_POLICY"}""")
            b2.postJson("/api/enroll/complete").let { it.status to it.contentAsString } shouldBe (400 to """{"error":"ENROLLMENT_STEP_INVALID"}""")
        }
    }

    Given("1-person account commands over HTTP") {
        Then("suspend, ENROLL reissue (new code once) and role revoke map to fixed codes; a non-SECURITY_ADMIN is 403; malformed input is 400") {
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            val id = field(sa.postJson("/api/accounts", "loginName" to login(), "displayName" to "대상").contentAsString, "actorId")
            sa.perform(post("/api/accounts/$id/enrollment-code")).let { it.status to Regex("\"enrollmentCode\":\"[A-Z2-7]{32}\"").containsMatchIn(it.contentAsString) } shouldBe
                (200 to true)
            sa.perform(post("/api/accounts/$id/roles/VIEWER/revoke")).let { it.status to it.contentAsString } shouldBe (409 to """{"error":"ROLE_NOT_HELD"}""")
            sa.perform(post("/api/accounts/$id/suspend")).let { it.status to it.contentAsString } shouldBe (200 to """{"status":"SUSPENDED"}""")
            sa.postJson("/api/accounts", "loginName" to "Bad Name", "displayName" to "x").let { it.status to it.contentAsString } shouldBe (400 to """{"error":"INVALID_INPUT"}""")
            loggedIn(ready.bootstrapped(AdminRole.APPROVER)).postJson("/api/accounts", "loginName" to login(), "displayName" to "x").let { it.status to it.contentAsString } shouldBe
                (403 to """{"error":"FORBIDDEN"}""")
            loggedIn(ready.withoutRoles()).perform(get("/api/accounts")).status shouldBe 403
            AdminHttpClient(mockMvc).perform(get("/api/accounts")).status shouldBe 401
        }
    }

    Given("login names in the account list (D-ADMIN1-017 — Q-S7-3)") {
        Then("only a SECURITY_ADMIN sees loginName; VIEWER, OPERATOR and APPROVER get actorId, displayName, status and roles only") {
            val name = login()
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            sa.postJson("/api/accounts", "loginName" to name, "displayName" to "목록 대상").status shouldBe 201
            sa.perform(get("/api/accounts")).contentAsString.let { it shouldContain name; it shouldContain "\"loginName\"" }
            val viewer = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }
            val operator = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR) }
            listOf(viewer, operator, ready.bootstrapped(AdminRole.APPROVER)).forEach { account ->
                val body = loggedIn(account).perform(get("/api/accounts")).also { it.status shouldBe 200 }.contentAsString
                body shouldNotContain name
                body shouldNotContain "\"loginName\""
                body shouldContain "목록 대상"
            }
        }
    }

    Given("ROLE_REACTIVATE cause candidates (S8 — Q-S7-2: the screen picks a cause from the server, never free text)") {
        Then("a SECURITY_ADMIN sees the reset approval that suspended APPROVER; other roles get 403; an unknown target has no cause") {
            val target = path.withRolesViaApproval(AdminRole.APPROVER)
            val (_, _, resetId) = path.credentialReset("PASSWORD_RESET", target.id)
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            sa.perform(get("/api/accounts/${target.id.value}/reactivation-causes")).let { it.status to it.contentAsString } shouldBe
                (200 to "{\"causes\":[{\"cause\":\"$resetId\",\"roles\":[\"APPROVER\"]}]}")
            val viewer = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }
            loggedIn(viewer).perform(get("/api/accounts/${target.id.value}/reactivation-causes")).let { it.status to it.contentAsString } shouldBe
                (403 to "{\"error\":\"FORBIDDEN\"}")
            loggedIn(ready.bootstrapped(AdminRole.APPROVER)).perform(get("/api/accounts/${target.id.value}/reactivation-causes")).status shouldBe 403
            sa.perform(get("/api/accounts/adm_unknown/reactivation-causes")).contentAsString shouldBe "{\"causes\":[]}"
        }
    }

    Given("enrollment request hardening (S7 review m-4·m-5)") {
        Then("(a) a state-changing call without the XSRF header is 403 and changes nothing; (c) the pre-start CSRF token no longer works after start") {
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            val name = login()
            sa.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(json("loginName" to name, "displayName" to "x")), xsrf = false).status shouldBe 403
            sa.perform(get("/api/accounts")).contentAsString shouldNotContain name
            val code = field(sa.postJson("/api/accounts", "loginName" to name, "displayName" to "x").contentAsString, "enrollmentCode")
            val browser = AdminHttpClient(mockMvc).also { it.csrf() }
            val oldToken = requireNotNull(browser.xsrfToken)
            browser.postJson("/api/enroll/start", "code" to code).status shouldBe 200
            browser.perform(
                post("/api/enroll/password").contentType(MediaType.APPLICATION_JSON).content(json("password" to "web-enroll-password-1")).header(AdminHttpClient.XSRF_HEADER, oldToken),
                xsrf = false,
            ).status shouldBe 403
            browser.postJson("/api/enroll/password", "password" to "web-enroll-password-1").status shouldBe 204
        }
        Then("(b) start without a session is refused before the controller (no CSRF token without a session — 403); a cookie of an invalidated session is the same") {
            val noSession = AdminHttpClient(mockMvc)
            noSession.postJson("/api/enroll/start", "code" to "A".repeat(32)).status shouldBe 403
        }
        Then("(d) starting an enrollment on a logged-in session ends that login: the same session can no longer call authenticated APIs") {
            val sa = loggedIn(ready.bootstrapped(AdminRole.SECURITY_ADMIN))
            val code = field(sa.postJson("/api/accounts", "loginName" to login(), "displayName" to "x").contentAsString, "enrollmentCode")
            sa.postJson("/api/enroll/start", "code" to code).status shouldBe 200
            sa.perform(get("/api/accounts")).status shouldBe 401
        }
        Then("(e) an address outside the allowlist is 403 IP_DENIED on the enrollment path and the denial is audited with path=ENROLL (m-5)") {
            val outside = AdminHttpClient(mockMvc, remoteAddr = "192.0.2.77")
            outside.postJson("/api/enroll/start", "code" to "A".repeat(32)).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"IP_DENIED"}""")
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, ':', detail) FROM admin_audit_event WHERE remote_ip = '192.0.2.77'")
            } shouldBe listOf("IP_DENIED:path=ENROLL")
        }
    }
})
