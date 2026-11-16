package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantAccountLabel
import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.application.provided.MerchantDirectoryItem
import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * MERCHANT-CODE-001 MC-1-9 MASTER 쪽(C-22 — 교차 배치 golden): 고정 Demo 값의 view를 응답 Map 생성기 [MerchantOpsJson]으로 만들고 기본 설정 `JsonMapper`(관리 채널
 * `AdminChannelResponses`도 `JsonMapper.builder().build()` — 같은 빌더 호출을 이 시험에서 따로 만든 것이지 그 객체를 쓰는 것은 아니다)로 직렬화한 **압축 JSON 문자열**이
 * golden `merchant-ops-response-shapes-v1.golden.json`의 항목(목록·코드 검색·상세, TXN-LIST-ENRICH-001 T-E2-10(N-2): 라벨 `accountLabels`)을 같은 mapper로 다시 직렬화한 문자열과 같다(키 순서·값 비교 — golden 파일의 들여쓰기 바이트 비교가 아님). ADMIN `MerchantCodeShapeGoldenSpec`이 같은 파일 사본을 `MasterResponseShape`로 해석한다.
 * 실제 HTTP 응답의 키 구조가 이 golden과 같음은 `MerchantCodeApiSpec`(MC-1-6)이, 라벨 응답의 키 목록은 `MerchantAccountLabelApiSpec`(T-E2-1)이 확인한다.
 * golden을 바꾸면 ADMIN 사본(`apps/internal-admin/src/test/resources/contracts/`)도 **같은 바이트로** 함께 바꾼다(ADMIN `MerchantCodeShapeGoldenSpec`이 바이트 동일을 단언).
 */
class MerchantOpsShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden: JsonNode = mapper.readTree(MerchantOpsShapeGoldenSpec::class.java.getResource("/contracts/merchant-ops-response-shapes-v1.golden.json")!!.readText(Charsets.UTF_8))
    fun at(text: String) = Instant.parse(text)
    fun item(code: String, retired: Boolean?) = MerchantDirectoryItem(
        "mer_01JC0000000000000000000001", "Demo Merchant", "biz_01JC0000000000000000000001", "Demo Business", "778*******", "ACTIVE", 5, 1,
        at("2026-10-09T02:00:00Z"), code, retired,
    )
    val operator = "admin:adm_01JC00000000000000000000V1"

    Given("MC-1-9 fixed views serialized by the MASTER response generator") {
        Then("MC-1-9 list (no merchantCode criterion): 10 item keys, no matchedRetiredCode — compact JSON equals the golden") {
            val produced = mapper.writeValueAsString(MerchantOpsJson.directory(MerchantDirectoryPage(listOf(item("shopabcd12", null)), null)))
            produced shouldBe mapper.writeValueAsString(golden.get("list"))
            mapper.readTree(produced).get("items").get(0).propertyNames().toList() shouldBe listOf(
                "merchantId", "merchantName", "businessId", "businessName", "registrationNumberMasked", "lifecycle", "entitlementRevision", "midCount", "createdAt", "merchantCode",
            )
        }

        Then("MC-1-9 code search: 11 item keys with matchedRetiredCode — compact JSON equals the golden") {
            val produced = mapper.writeValueAsString(MerchantOpsJson.directory(MerchantDirectoryPage(listOf(item("shopxyz789", true)), null), byCode = true))
            produced shouldBe mapper.writeValueAsString(golden.get("codeSearch"))
        }

        Then("T-E2-10 account labels: items with exactly merchantAccountId, merchantName, merchantCode (no mer_/biz_ key) — compact JSON equals the golden") {
            val produced = mapper.writeValueAsString(
                MerchantOpsJson.accountLabels(
                    listOf(
                        MerchantAccountLabel("mid_01JC0000000000000000000001", "데모상점", "demoshop01"),
                        MerchantAccountLabel("mid_01JC0000000000000000000002", "Demo Merchant", "shopxyz789"),
                    ),
                ),
            )
            produced shouldBe mapper.writeValueAsString(golden.get("accountLabels"))
            mapper.readTree(produced).get("items").get(0).propertyNames().toList() shouldBe listOf("merchantAccountId", "merchantName", "merchantCode")
        }

        Then("MC-1-9 detail: merchant.merchantCode, merchantCodeHistory and merchantCodeChangeable — compact JSON equals the golden; no registeredBy on the merchant") {
            val view = MerchantDetailView(
                merchant = MerchantDetailView.MerchantSummary("mer_01JC0000000000000000000001", "Demo Merchant", "ACTIVE", 5, at("2026-10-09T02:00:00Z"), "shopxyz789"),
                business = MerchantDetailView.BusinessSummary("biz_01JC0000000000000000000001", "Demo Business", "778*******", "ACTIVE"),
                sites = listOf(MerchantDetailView.SiteDetail("site_01JC0000000000000000000001", "WEB", "demo-shop.paycore.test", "ACTIVE", null, operator)),
                contracts = listOf(
                    MerchantDetailView.ContractDetail(
                        "ctr_01JC0000000000000000000001", at("2026-10-09T02:00:00Z"),
                        listOf(MerchantDetailView.RevisionDetail(1, "MERCHANT", at("2026-10-09T15:00:00Z"), null, null, emptyList(), operator)),
                        emptyList(),
                    ),
                ),
                accounts = emptyList(),
                limits = emptyList(),
                propagation = null,
                merchantCodeHistory = listOf(
                    MerchantDetailView.CodeAssignmentDetail("shopabcd12", 1, "REGISTRATION", null, at("2026-10-09T02:00:00Z"), operator, null),
                    MerchantDetailView.CodeAssignmentDetail("shopxyz789", 2, "CHANGE", "shopabcd12", at("2026-10-09T03:00:00Z"), "admin:adm_01JC00000000000000000000V2", "TYPO_CORRECTION"),
                ),
                merchantCodeChangeable = false,
            )
            val produced = mapper.writeValueAsString(MerchantOpsJson.detail(view))
            produced shouldBe mapper.writeValueAsString(golden.get("detail"))
            mapper.readTree(produced).get("merchant").propertyNames().toList().contains("registeredBy") shouldBe false
        }
    }
})
