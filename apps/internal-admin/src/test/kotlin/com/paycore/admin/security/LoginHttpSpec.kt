package com.paycore.admin.security

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.TestTotp
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 인증 HTTP(§4.3.2·§4.4 — SessionFixation·SessionCookieAttributes·NewLoginWins·SessionTimeout·CSRF·세션 손상). Spring Session JDBC 실제 표(`admin_session`),
 * 실제 2단계 로그인(TOTP는 시험 쪽 독립 계산). 응답은 고정 JSON code만.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class)
class LoginHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun client() = AdminHttpClient(mockMvc)
    fun sessionId(cookieValue: String): String = String(Base64.getDecoder().decode(cookieValue))
    fun sessionRow(sessionId: String): Long = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_session WHERE SESSION_ID = ?", sessionId) as Number).toLong() }

    Given("the CSRF bootstrap and the session cookie") {
        Then("GET /api/auth/csrf creates the session explicitly; the raw token is only in Set-Cookie (204, empty body); cookie attributes are __Host- strict") {
            val c = client()
            val response = c.csrf()
            response.status shouldBe 204
            response.contentAsString shouldBe ""
            val headers = response.getHeaders("Set-Cookie")
            val session = headers.single { it.startsWith("${AdminHttpClient.SESSION_COOKIE}=") }
            val xsrf = headers.single { it.startsWith("${AdminHttpClient.XSRF_COOKIE}=") }
            listOf("Path=/", "Secure", "HttpOnly", "SameSite=Strict").forEach { session shouldContain it }
            session shouldNotContain "Domain="
            listOf("Path=/", "Secure", "SameSite=Strict").forEach { xsrf shouldContain it }
            xsrf shouldNotContain "HttpOnly"
            sessionRow(sessionId(c.sessionCookie!!)) shouldBe 1L
            val token = c.xsrfToken
            c.csrf().getHeaders("Set-Cookie").none { it.startsWith("${AdminHttpClient.SESSION_COOKIE}=") } shouldBe true
            c.xsrfToken shouldBe token
        }
        Then("a password POST without the session token header is 403 and does not authenticate") {
            val account = ready.withoutRoles()
            val c = client()
            c.csrf()
            c.perform(post("/api/auth/password").contentType("application/json").content(AdminHttpClient.json("loginName" to account.loginName, "password" to account.password)), xsrf = false)
                .status shouldBe 403
        }
    }

    Given("the two-step login") {
        Then("password then TOTP authenticates; the session ID and the CSRF token are renewed at each step (SessionFixation); /api/auth/me shows the current roles") {
            val account = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val c = client()
            c.csrf()
            val anonymousSession = c.sessionCookie
            val anonymousToken = c.xsrfToken
            val step1 = c.password(account.loginName, account.password)
            step1.status shouldBe 200
            step1.contentAsString shouldBe """{"next":"TOTP"}"""
            val passwordSession = c.sessionCookie
            (passwordSession != anonymousSession && c.xsrfToken != anonymousToken) shouldBe true
            c.perform(get("/api/auth/me")).status shouldBe 401
            val passwordToken = c.xsrfToken
            c.totp(TestTotp.code(account.secret)).status shouldBe 200
            (c.sessionCookie != passwordSession && c.xsrfToken != passwordToken) shouldBe true
            sessionRow(sessionId(anonymousSession!!)) shouldBe 0L
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT MAX_INACTIVE_INTERVAL FROM admin_session WHERE SESSION_ID = ?", sessionId(c.sessionCookie!!)) as Number).toInt()
            } shouldBe 900
            val me = c.perform(get("/api/auth/me"))
            me.status shouldBe 200
            me.contentAsString shouldBe """{"actorId":"${account.account.id.value}","displayName":"${account.account.displayName.value}","roles":["SECURITY_ADMIN"]}"""
        }
        Then("every failing branch returns the same 401 body (wrong password, unknown login, suspended)") {
            val account = ready.withoutRoles()
            val suspended = ready.withoutRoles()
            val admin = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(admin.id.value, suspended.account.id.value, AdminRequestContext("corr-susp", null))) shouldBe
                AccountCommandResult.Succeeded(suspended.account.id.value)
            listOf(
                client().also { it.csrf() }.password(account.loginName, "not-the-password"),
                client().also { it.csrf() }.password("ghost-" + UUID.randomUUID().toString().take(6), "not-the-password"),
                client().also { it.csrf() }.password(suspended.loginName, suspended.password),
            ).map { it.status to it.contentAsString } shouldBe List(3) { 401 to """{"error":"INVALID_CREDENTIALS"}""" }
        }
        Then("the TOTP step without a password-verified session is 401") {
            val c = client()
            c.csrf()
            c.totp("123456").let { it.status to it.contentAsString } shouldBe (401 to """{"error":"INVALID_CREDENTIALS"}""")
        }
    }

    Given("session validity on every request (D-ADMIN1-001, §4.4.2)") {
        Then("a newer login of the same account replaces the older session: its next request is 401 SESSION_REPLACED") {
            val account = ready.bootstrapped(AdminRole.APPROVER)
            val first = client()
            first.login(account.loginName, account.password, account.secret).status shouldBe 200
            val second = client()
            second.login(account.loginName, account.password, account.secret, Instant.now().plusSeconds(30)).status shouldBe 200
            first.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_REPLACED"}""")
            second.perform(get("/api/auth/me")).status shouldBe 200
        }
        Then("an idle-expired session (cookie sent, session row gone) is 401 SESSION_EXPIRED (S4 review m-8)") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            AdminDbAccounts.root(container).use { it.update("DELETE FROM admin_session WHERE SESSION_ID = ?", sessionId(c.sessionCookie!!)) }
            c.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_EXPIRED"}""")
        }
        Then("a security_version change of the account (no generation change) ends the session too: 401 SESSION_REPLACED (S4 review nit)") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_account SET security_version = security_version + 1, version = version + 1 WHERE actor_id = ?", account.account.id.value)
            }
            c.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_REPLACED"}""")
        }
        Then("logout invalidates the session (row removed) and is audited") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            val id = sessionId(c.sessionCookie!!)
            c.perform(post("/api/auth/logout")).status shouldBe 204
            sessionRow(id) shouldBe 0L
            c.perform(get("/api/auth/me")).status shouldBe 401
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, ':', result_code) FROM admin_audit_event WHERE actor_id = ? AND action = 'LOGOUT'", account.account.id.value)
            } shouldBe listOf("LOGOUT:SUCCESS")
        }
        Then("past the absolute limit (authenticatedAt + absolute) the next request is 401 SESSION_EXPIRED and the session is removed") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            val id = sessionId(c.sessionCookie!!)
            AdminDbAccounts.root(container).use {
                it.update(
                    "UPDATE admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID SET a.ATTRIBUTE_BYTES = ? " +
                        "WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'paycore.admin.authenticatedAt'",
                    Instant.now().minusSeconds(9 * 3600).toString().toByteArray(), id,
                )
            }
            c.perform(get("/api/auth/me")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_EXPIRED"}""")
            sessionRow(id) shouldBe 0L
        }
        Then("a corrupted session attribute (not a valid value) is never deserialized as an object: 401 and the session row is removed (§17.5)") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            val id = sessionId(c.sessionCookie!!)
            AdminDbAccounts.root(container).use {
                it.update(
                    "UPDATE admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID SET a.ATTRIBUTE_BYTES = UNHEX('ACED0005737200') " +
                        "WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'paycore.admin.sessionGeneration'",
                    id,
                )
            }
            val response = c.perform(get("/api/auth/me"))
            response.status shouldBe 401
            response.contentAsString shouldBe """{"error":"SESSION_EXPIRED"}"""
            sessionRow(id) shouldBe 0L
        }
        Then("session attributes are stored as UTF-8 strings only (no Java serialization header)") {
            val account = ready.withoutRoles()
            val c = client()
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            val id = sessionId(c.sessionCookie!!)
            val values = AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT HEX(a.ATTRIBUTE_BYTES) FROM admin_session_attributes a JOIN admin_session s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID WHERE s.SESSION_ID = ?", id,
                )
            }
            (values.isNotEmpty() && values.none { it.startsWith("ACED") }) shouldBe true
            values.map { String(java.util.HexFormat.of().parseHex(it)) }.any { it == account.account.id.value } shouldBe true
        }
    }
})
