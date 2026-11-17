package com.paycore.admin.audit

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 감사 조회 HTTP(S3 리뷰 m-3 — D-ADMIN1-008): 범위는 **세션 actor의 계정 현재 행**으로만 계산한다. VIEWER가 다른 actor 필터를 보내도 자기 행만, SECURITY_ADMIN은
 * 전체 필터, 역할 없는 계정은 403. 요청은 범위·역할을 담을 수 없다(쿼리 파라미터는 필터뿐).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AuditQueryHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val approvalPath: ApprovalPath,
    private val storage: AdminAccountStorage,
    private val recorder: AdminAuditRecorder,
    private val commands: AdminAccountCommandUseCase,
) : BehaviorSpec({

    fun actorsIn(body: String): List<String> = Regex("\"actorId\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()

    Given("audit rows by another admin") {
        val other = "adm_" + "Q".repeat(26)
        storage.inTx {
            recorder.record(AuditRecord(Instant.now().truncatedTo(ChronoUnit.MILLIS), "ADMIN", other, "다른 관리자", "ACCOUNT_SUSPENDED", "SUCCESS", "corr-other"))
        }

        Then("a VIEWER filtering by another actor sees only their own rows (SELF scope from the current row)") {
            val viewer = ready.withoutRoles().also { approvalPath.grant(it.account.id, AdminRole.VIEWER) }
            val c = AdminHttpClient(mockMvc)
            c.login(viewer.loginName, viewer.password, viewer.secret).status shouldBe 200
            val response = c.perform(get("/api/audit").param("actorId", other))
            response.status shouldBe 200
            val actors = actorsIn(response.contentAsString)
            (actors.isNotEmpty() && actors.all { it == viewer.account.id.value }) shouldBe true
        }
        Then("a SECURITY_ADMIN may filter by another actor") {
            val admin = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val c = AdminHttpClient(mockMvc)
            c.login(admin.loginName, admin.password, admin.secret).status shouldBe 200
            actorsIn(c.perform(get("/api/audit").param("actorId", other)).contentAsString) shouldBe listOf(other)
        }
        Then("MERCHANT-CODE-001 MC-3-16: targetMerchantId filter — malformed is 400 (no read); a well-formed key returns entries whose keys carry targetMerchantId right after targetRef") {
            val admin = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val merchant = "mer_01JCME0000000000000000QH16"
            storage.inTx {
                recorder.record(
                    AuditRecord(Instant.now().truncatedTo(ChronoUnit.MILLIS), "ADMIN", other, "다른 관리자", "MERCHANT_VIEWED", "OK", "corr-mc3a", targetRef = "shopqh1600", targetMerchantId = merchant),
                )
            }
            val c = AdminHttpClient(mockMvc)
            c.login(admin.loginName, admin.password, admin.secret).status shouldBe 200
            listOf("shopqh1600", "mer_lowercase00000000000000", "biz_01JC0000000000000000000001").map {
                c.perform(get("/api/audit").param("targetMerchantId", it)).let { r -> r.status to r.contentAsString }
            } shouldBe List(3) { 400 to """{"error":"BAD_REQUEST"}""" }
            val body = c.perform(get("/api/audit").param("targetMerchantId", merchant)).contentAsString
            Regex("\"targetRef\":\"([^\"]+)\",\"targetMerchantId\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] + "|" + it.groupValues[2] }.toList() shouldBe
                listOf("shopqh1600|$merchant")
        }
        Then("an account without any viewing role gets 403 and no rows") {
            val roleless = ready.withoutRoles()
            val c = AdminHttpClient(mockMvc)
            c.login(roleless.loginName, roleless.password, roleless.secret).status shouldBe 200
            c.perform(get("/api/audit")).let { it.status to it.contentAsString } shouldBe (403 to """{"error":"FORBIDDEN"}""")
        }
        Then("a role revoked after login takes effect on the next request (the session generation changes — 401 SESSION_REPLACED)") {
            val admin = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val c = AdminHttpClient(mockMvc)
            c.login(approver.loginName, approver.password, approver.secret).status shouldBe 200
            c.perform(get("/api/audit")).status shouldBe 200
            commands.revokeRole(RevokeAdminRole(admin.account.id.value, approver.account.id.value, "APPROVER", AdminRequestContext("corr-rv", null))) shouldBe
                AccountCommandResult.Succeeded(approver.account.id.value)
            c.perform(get("/api/audit")).let { it.status to it.contentAsString } shouldBe (401 to """{"error":"SESSION_REPLACED"}""")
        }
    }
})
