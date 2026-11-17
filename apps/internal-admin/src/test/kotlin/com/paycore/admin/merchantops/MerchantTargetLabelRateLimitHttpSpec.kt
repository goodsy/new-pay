package com.paycore.admin.merchantops

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.merchantops.adapter.integration.MicrometerMerchantOpsMetrics
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * MERCHANT-CODE-001 MC-3-19 라벨 분당 창(C-35 — MC-3b 리뷰 NIT-1): 실제 Spring 컨텍스트에서 `paycore.admin.master-ops.label-reads-per-minute=2`로 덮어써
 * 같은 계정의 세 번째 `POST /api/merchant-labels`가 429 `RATE_LIMITED`이고 그 호출은 MASTER를 부르지 않음(MASTER 호출 수 불변)을 HTTP 수준에서 확인한다.
 * (`MerchantTargetLabelHttpSpec`은 시험 설정 600이라 창 초과를 Use Case 시험으로만 확인했다 — 이 시험이 설정 키 → 창 → 응답 매핑을 한 경로로 묶는다.)
 */
@SpringBootTest(properties = ["paycore.admin.master-ops.label-reads-per-minute=2"])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantTargetLabelRateLimitHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val merchant = "mer_01JD" + (System.nanoTime() % 1_000_000L).toString().padStart(22, '0')
    fun masterCalls() = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANT_LABELS).size
    fun rateLimited() = registry.find(MicrometerMerchantOpsMetrics.TARGET_LABELS).tags("result", "RATE_LIMITED").counter()?.count() ?: 0.0

    Given("label-reads-per-minute = 2 and one VIEWER session") {
        Then("the first two calls reach MASTER (200 OK); the third is 429 RATE_LIMITED and MASTER is not called again") {
            StubMasterOpsServer.reply(
                StubMasterOpsServer.MERCHANT_LABELS,
                StubMasterOpsServer.Reply(200, StubMasterOpsServer.merchantLabelsJson(Triple(merchant, "창시험 상점", "windowshop1"))),
            )
            val account = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }
            val client = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
            fun call() = client.perform(post("/api/merchant-labels").contentType(MediaType.APPLICATION_JSON).content("""{"merchantIds":["$merchant"]}"""))

            val start = masterCalls()
            repeat(2) { n ->
                call().let {
                    it.status shouldBe 200
                    it.contentAsString shouldBe """{"items":[{"merchantId":"$merchant","name":"창시험 상점","code":"windowshop1"}],"labels":"OK"}"""
                }
                masterCalls() shouldBe start + n + 1
            }
            val limitedBefore = rateLimited()
            call().let {
                it.status shouldBe 429
                it.contentAsString shouldBe """{"error":"RATE_LIMITED"}"""
            }
            masterCalls() shouldBe start + 2
            rateLimited() shouldBe limitedBefore + 1.0
        }
    }
})
