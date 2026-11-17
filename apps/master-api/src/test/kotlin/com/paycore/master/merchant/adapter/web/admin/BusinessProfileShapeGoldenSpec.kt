package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessDirectoryItem
import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessHistoryEntry
import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessFieldErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/**
 * BUSINESS-PROFILE-001 §6.8(BIZ-2 골든 — 교차 배치 계약): 고정 Demo 값 view를 응답 Map 생성기 [BusinessProfileJson]으로 만들고 기본 `JsonMapper`(관리 채널 `AdminChannelResponses`와 같은
 * 빌더 호출)로 직렬화한 **압축 JSON 문자열**이 골든 `business-profile-response-shapes-v1.golden.json`의 항목을 같은 mapper로 다시 직렬화한 문자열과 같다(키 순서·값 비교).
 * 항목: 목록·번호 조회 찾음/못 찾음·상세·이력·명령 성공·재생·중복 거절(existing)·다른 거절·400 fieldErrors(정확히 10개 — 값은 모두 Demo 값, 운영값 아님). 실제 HTTP 응답의 키 구조가 이 골든과 같음은 BusinessProfileApiSpec이 확인한다.
 * **ADMIN 사본은 BIZ-3에서 바이트 동일로 추가한다**(`apps/internal-admin/src/test/resources/contracts/` — ADMIN `MasterResponseShape` 해석, MERCHANT-CODE-001 §1.5 교훈). 이 골든을 바꾸면
 * 그 사본도 함께 바꾼다.
 */
class BusinessProfileShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden: JsonNode = mapper.readTree(
        BusinessProfileShapeGoldenSpec::class.java.getResource("/contracts/business-profile-response-shapes-v1.golden.json")!!.readText(Charsets.UTF_8),
    )
    fun same(produced: Map<String, Any?>, key: String) = mapper.writeValueAsString(produced) shouldBe mapper.writeValueAsString(golden.get(key))
    fun at(text: String) = Instant.parse(text)
    val id = "biz_01JC0000000000000000000001"
    val name = "DEMO 사업자 (운영값 아님)"
    val operator = "admin:adm_01JC00000000000000000000V1"
    val summary = BusinessSummary(id, name, "000*******", "REGISTERED")

    Given("§6.8 fixed Demo views serialized by the MASTER response generator") {
        Then("NIT-1 the golden holds exactly the ten §6.8 shapes (no extra or missing top-level key)") {
            golden.propertyNames().toList() shouldBe listOf(
                "list", "lookupFound", "lookupNotFound", "detail", "history", "commandSucceeded", "commandReplayed", "duplicateRejected", "otherRejected", "validationFailed",
            )
        }

        Then("list (10 item keys, next), lookup found / not found (same shape) and detail equal the golden") {
            same(
                BusinessProfileJson.directory(
                    BusinessDirectoryPage(
                        listOf(
                            BusinessDirectoryItem(id, name, "000*******", "INDIVIDUAL", "REGISTERED", "NOT_CHECKED", "D******", 1, 1, at("2026-11-17T01:00:00Z")),
                            BusinessDirectoryItem("biz_01JC0000000000000000000000", "DEMO 옛 사업자", "999*******", null, "DRAFT", "NOT_CHECKED", null, 0, 0, at("2026-10-09T02:00:00Z")),
                        ),
                        "biz_01JC0000000000000000000000",
                    ),
                ),
                "list",
            )
            same(BusinessProfileJson.lookup(listOf(summary)), "lookupFound")
            same(BusinessProfileJson.lookup(emptyList()), "lookupNotFound")
            same(
                BusinessProfileJson.detail(
                    BusinessDetailView(
                        BusinessDetailView.BusinessBlock(
                            id, name, "000*******", "INDIVIDUAL", "REGISTERED", "NOT_CHECKED", "ACTIVE", 0, at("2026-11-17T01:00:00Z"), at("2026-11-17T01:00:00Z"), 1,
                        ),
                        BusinessDetailView.RegistrationBlock(null, LocalDate.parse("2026-01-01"), "KR", null, "0200000000", null),
                        listOf(BusinessDetailView.Representative("DEMO 대표", "SOLE")),
                        listOf(BusinessDetailView.Category("BUSINESS_TYPE", "DEMO 업태"), BusinessDetailView.Category("BUSINESS_ITEM", "DEMO 종목")),
                        listOf(BusinessDetailView.Address("REGISTERED", "00000", "DEMO 주소 (운영값 아님)", null)),
                        listOf(BusinessDetailView.Contact("DEMO 담당", "demo@paycore.test", "01000000000", true)),
                    ),
                ),
                "detail",
            )
        }

        Then("history (seq, changeType, actorRef, occurredAt, reason, changes — stored masked values, nextAfter) equals the golden") {
            same(
                BusinessProfileJson.history(
                    BusinessHistoryPage(
                        listOf(
                            BusinessHistoryEntry(
                                41, "BusinessCreated", operator, at("2026-11-17T01:00:00Z"), null,
                                listOf(
                                    linkedMapOf("field" to "displayName", "after" to name),
                                    linkedMapOf("field" to "representatives", "added" to listOf(linkedMapOf("name" to "D******", "type" to "SOLE", "order" to 1L))),
                                ),
                            ),
                            BusinessHistoryEntry(
                                42, "BusinessProfileUpdated", operator, at("2026-11-17T02:00:00Z"), "DEMO 상호 정정",
                                listOf(
                                    linkedMapOf("field" to "displayName", "before" to name, "after" to "DEMO 사업자"),
                                    linkedMapOf(
                                        "field" to "contacts",
                                        "ended" to listOf(
                                            linkedMapOf("name" to "D******", "email" to "d***@p***.test", "phoneNumber" to "****0000", "primary" to true, "order" to 1L),
                                        ),
                                        "added" to emptyList<Any>(),
                                    ),
                                ),
                            ),
                        ),
                        null,
                    ),
                ),
                "history",
            )
        }

        Then("commands: success and replay, duplicate rejection with existing (D-BIZ-003), another rejection without reference (C-12), 400 fieldErrors (path·code only)") {
            same(BusinessProfileJson.succeeded(id, false), "commandSucceeded")
            same(BusinessProfileJson.succeeded(id, true), "commandReplayed")
            same(BusinessProfileJson.rejected("BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, summary), "duplicateRejected")
            same(BusinessProfileJson.rejected("BUSINESS_VERSION_CONFLICT", true), "otherRejected")
            same(
                BusinessProfileJson.validationFailed(
                    listOf(
                        BusinessFieldError("contacts", BusinessFieldErrorCode.REQUIRED),
                        BusinessFieldError("registration.corporateRegistrationNumber", BusinessFieldErrorCode.REQUIRED),
                    ),
                ),
                "validationFailed",
            )
            golden.get("duplicateRejected").get("existing").propertyNames().toList() shouldBe
                listOf("businessId", "businessName", "registrationNumberMasked", "registrationStatus")
            golden.get("otherRejected").propertyNames().toList() shouldBe listOf("outcome", "rejection", "replayed")
        }
    }
})
