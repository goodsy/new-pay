package com.paycore.admin.security

import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.mysql.MySQLContainer

/**
 * IP 허용 목록(§4.8.1 — `IpAllowlistSpec`): 모든 경로 앞, `remoteAddr`만(프록시 헤더 불신), IPv4-mapped 정규화, 빈 목록·호스트 이름 거부(기동 실패).
 * 시험 설정의 허용 목록은 `127.0.0.1`·`10.20.0.0/16`. 인증 경로(`/api/auth/`) 거부는 익명 감사 1행.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
class IpAllowlistSpec(
    private val mockMvc: MockMvc,
    private val container: MySQLContainer,
    private val environment: org.springframework.core.env.Environment,
) : BehaviorSpec({

    fun sessionRows(): Long = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_session") as Number).toLong() }

    Given("the servlet filter in front of every path") {
        Then("an address outside the list is refused with 403 IP_DENIED before any session, ignoring X-Forwarded-For") {
            val before = sessionRows()
            val outside = AdminHttpClient(mockMvc, remoteAddr = "10.9.9.9")
            listOf(
                outside.perform(get("/")),
                outside.perform(get("/api/auth/csrf")),
                outside.perform(get("/api/auth/csrf").header("X-Forwarded-For", "127.0.0.1")),
            ).map { it.status to it.contentAsString } shouldBe List(3) { 403 to """{"error":"IP_DENIED"}""" }
            sessionRows() shouldBe before
        }
        Then("listed addresses, CIDR members and IPv4-mapped IPv6 forms of listed addresses pass") {
            listOf("127.0.0.1", "::ffff:127.0.0.1", "10.20.3.4").map { AdminHttpClient(mockMvc, remoteAddr = it).perform(get("/")).status } shouldBe listOf(200, 200, 200)
            AdminHttpClient(mockMvc, remoteAddr = "10.21.0.1").perform(get("/")).status shouldBe 403
        }
        Then("the IP filter runs before authentication: an unauthenticated /api/audit from outside is 403 IP_DENIED, not 401 (S4 review m-8)") {
            AdminHttpClient(mockMvc, remoteAddr = "10.9.9.9").perform(get("/api/audit")).let { it.status to it.contentAsString } shouldBe
                (403 to """{"error":"IP_DENIED"}""")
            AdminHttpClient(mockMvc).perform(get("/api/audit")).status shouldBe 401
        }
        Then("proxy headers are not interpreted: server.forward-headers-strategy is none (S4 review m-8)") {
            environment.getProperty("server.forward-headers-strategy") shouldBe "none"
        }
        Then("a refused authentication-path request is audited anonymously with the remote address (no other detail)") {
            AdminHttpClient(mockMvc, remoteAddr = "10.9.9.10").perform(get("/api/auth/csrf"))
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(actor_kind, ':', action, ':', result_code, ':', detail) FROM admin_audit_event WHERE remote_ip = '10.9.9.10'")
            } shouldBe listOf("ANONYMOUS:IP_DENIED:DENIED:path=AUTH")
        }
    }

    Given("allowlist configuration") {
        Then("an empty list, a host name or a malformed entry is rejected (startup fails)") {
            listOf(emptyList(), listOf("localhost"), listOf("10.0.0.0/33"), listOf("1.2.3"), listOf("999.1.1.1"), listOf("1.2.3.256"), listOf("01.2.3.4x")).forEach { entries ->
                shouldThrow<IllegalArgumentException> { IpAllowlist.parse(entries) }
            }
        }
        Then("only numeric literals reach InetAddress (no host-name lookup): out-of-range IPv4 shapes are refused before resolution (S4 review nit)") {
            listOf("127.0.0.1", "255.255.255.255", "::ffff:10.0.0.1", "999.1.1.1", "1.2.3.256", "localhost", "1.2.3").map(IpAllowlist::isNumericLiteral) shouldBe
                listOf(true, true, true, false, false, false, false)
        }
        Then("a LIVE deployment refuses an allowlist that permits every address (0.0.0.0/0 or ::/0 — S4 review m-5)") {
            listOf(listOf("0.0.0.0/0"), listOf("10.0.0.0/8", "::/0"), listOf("10.0.0.0/8")).map { IpAllowlist.parse(it).liveViolations() } shouldBe listOf(
                listOf("paycore.admin.network.ip-allowlist permits every address"), listOf("paycore.admin.network.ip-allowlist permits every address"), emptyList(),
            )
        }
        Then("IPv6 entries and prefixes match; IPv4-mapped forms match IPv4 entries") {
            val list = IpAllowlist.parse(listOf("fd00::/8", "192.168.1.10"))
            listOf("fd12::1", "fe80::1", "192.168.1.10", "192.168.1.11", "::ffff:192.168.1.10").map(list::allows) shouldBe listOf(true, false, true, false, true)
        }
    }
})
