package com.paycore.admin.security

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.util.Base64

/**
 * 익명 세션 증가(n-1 — `AnonymousSessionGrowthSpec`): 정적 자원·SPA 문서·거부 응답은 세션 행을 만들지 않고, `GET /api/auth/csrf`만 세션을 1개 만든다(있으면 재사용).
 * 만료된 세션은 Spring Session 정리가 지운다. 익명 세션 idle은 Demo 10분.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
class AnonymousSessionGrowthSpec(
    private val mockMvc: MockMvc,
    private val sessions: JdbcIndexedSessionRepository,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun rows(): Long = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_session") as Number).toLong() }

    Given("anonymous traffic") {
        Then("static, SPA document and refused requests create no session row; csrf creates exactly one, reused on repeat; expiry cleanup removes it") {
            val before = rows()
            val c = AdminHttpClient(mockMvc)
            repeat(5) {
                c.perform(get("/"))
                c.perform(get("/login"))
                c.perform(get("/assets/missing.js"))
                c.perform(get("/api/tech-smoke"))
                c.perform(post("/api/tech-smoke"))
            }
            (c.sessionCookie to rows()) shouldBe (null to before)
            c.csrf().status shouldBe 204
            rows() shouldBe before + 1
            c.csrf()
            rows() shouldBe before + 1
            val sessionId = String(Base64.getDecoder().decode(c.sessionCookie!!))
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT MAX_INACTIVE_INTERVAL FROM admin_session WHERE SESSION_ID = ?", sessionId) as Number).toInt() shouldBe 600
                it.update("UPDATE admin_session SET EXPIRY_TIME = 0 WHERE SESSION_ID = ?", sessionId)
            }
            sessions.cleanUpExpiredSessions()
            rows() shouldBe before
        }
    }
})
