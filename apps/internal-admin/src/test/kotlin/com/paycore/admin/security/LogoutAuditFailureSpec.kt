package com.paycore.admin.security

import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.util.Base64

/**
 * 로그아웃은 안전 방향 행위(S4 리뷰 m-1 — D-78과 같은 종류): 세션 폐기는 **항상**(finally) 하고, 감사 기록이 실패하면 로그 분류 + 500 응답(세션은 이미 폐기)이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(LoginReadyAccounts::class, SwitchableAuditStoreConfig::class)
class LogoutAuditFailureSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val auditStore: SwitchableAuditStore,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterEach { auditStore.failOn(null) }

    Given("a logged-in session and an audit store that fails on LOGOUT") {
        Then("the session is still removed (row 0) and the response is the fixed JSON 500") {
            val account = ready.withoutRoles()
            val c = AdminHttpClient(mockMvc)
            c.login(account.loginName, account.password, account.secret).status shouldBe 200
            val sessionId = String(Base64.getDecoder().decode(c.sessionCookie!!))
            auditStore.failOn { it.action == "LOGOUT" }
            c.perform(post("/api/auth/logout")).let { it.status to it.contentAsString } shouldBe (500 to """{"error":"INTERNAL_ERROR"}""")
            AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_session WHERE SESSION_ID = ?", sessionId) as Number).toLong() } shouldBe 0L
            c.perform(get("/api/auth/me")).status shouldBe 401
        }
    }
})
