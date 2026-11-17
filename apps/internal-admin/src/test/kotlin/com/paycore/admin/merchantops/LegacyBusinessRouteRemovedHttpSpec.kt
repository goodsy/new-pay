package com.paycore.admin.merchantops

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.mysql.MySQLContainer

/**
 * BUSINESS-PROFILE-001 BIZ-4-1(C-27 전환 — 옛 사업자 등록 경로 제거, ADMIN): `POST /api/merchant-registrations/businesses`가 없다 — OPERATOR 세션 + CSRF 호출도 404,
 * 감사 행(`MERCHANT_BUSINESS_REGISTER`) 추가 0, MASTER 미호출, handler 목록에 없음. 새 사업자 생성은 `POST /api/businesses`(BIZ-3 — BusinessHttpSpec)이고 남은 가맹점·Site 등록 경로는 그대로(대조).
 * 기존 감사 action 이름 `MERCHANT_BUSINESS_REGISTER`의 과거 행·의미는 불변(§7.4) — 새 행만 생기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class LegacyBusinessRouteRemovedHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val mappings: List<RequestMappingHandlerMapping>,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val legacy = "/api/merchant-registrations/businesses"

    Given("C-27: the legacy ADMIN business registration route") {
        Then("an OPERATOR call with CSRF is 404; no audit row, no MASTER call; no handler maps the path while the merchant and site routes remain (control)") {
            val operator = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR) }
            val client = AdminHttpClient(mockMvc).also { it.login(operator.loginName, operator.password, operator.secret).status shouldBe 200 }
            val before = StubMasterOpsServer.received.size
            val body = AdminHttpClient.json("submissionId" to "%032x".format(System.nanoTime()), "registrationNumber" to "7781234560", "displayName" to "옛 경로")
            client.perform(post(legacy).contentType(MediaType.APPLICATION_JSON).content(body), true).status shouldBe 404
            StubMasterOpsServer.received.size shouldBe before
            AdminDbAccounts.root(container).use {
                it.column("SELECT action FROM admin_audit_event WHERE actor_id = ? AND action = 'MERCHANT_BUSINESS_REGISTER'", operator.account.id.value)
            }.shouldBeEmpty()

            val patterns = mappings.flatMap { it.handlerMethods.keys }.flatMap { it.patternValues }
            patterns shouldNotContain legacy
            patterns shouldContain "/api/merchant-registrations/merchants"
            patterns shouldContain "/api/merchant-registrations/merchant-sites"
            patterns shouldContain "/api/businesses"
        }
    }
})
