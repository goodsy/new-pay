package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.web.MerchantRegistrationBodyParser
import com.paycore.admin.merchantops.adapter.web.MerchantRegistrationBodyParser.Parsed
import com.paycore.admin.merchantops.application.provided.RegistrationKind
import com.paycore.admin.merchantops.application.provided.RegistrationRequest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 화면 등록 본문 엄격 해석 — B1-2 계약 초안·CSA(MERCHANT-OPS-002 §4.3·§4.4 0, 단위 — Spring 없음): `scopeSiteIds`는 문자열 배열(필수, 비면 MERCHANT Scope),
 * `effectiveToDate`는 선택(없음 또는 `null`), 그 밖 자료형 강제 변환·모르는 key·중복 key·뒤따르는 토큰 거부. 값 형식(날짜·ID·개수)은 Use Case가 검사한다(감사 순서 — 역할이 먼저).
 */
class MerchantRegistrationBodyParserSpec : BehaviorSpec({

    val sub = "0123456789abcdef0123456789abcdef"
    val contract = """{"submissionId":"$sub","partyBusinessId":"biz_01JC0000000000000000000001","scopeMerchantId":"mer_01JC0000000000000000000001",""" +
        """"scopeSiteIds":["site_01JC0000000000000000000001"],"effectiveFromDate":"2026-11-09","effectiveToDate":"2027-01-01"}"""
    val agreement = """{"submissionId":"$sub","contractId":"ctr_01JC0000000000000000000001","serviceKind":"CARD_PG_PAYMENT"}"""
    fun parse(kind: RegistrationKind, body: String) = MerchantRegistrationBodyParser.parse(kind, body.toByteArray(Charsets.UTF_8))

    Given("well-formed B1-2 bodies") {
        Then("contract: ids, the site array and both dates come through unchanged (no conversion here — C-7 is the use case's)") {
            val c = parse(RegistrationKind.CONTRACT, contract).shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.Contract>()
            listOf(c.submissionId, c.partyBusinessId, c.scopeMerchantId, c.scopeSiteIds, c.effectiveFromDate, c.effectiveToDate) shouldBe
                listOf(sub, "biz_01JC0000000000000000000001", "mer_01JC0000000000000000000001", listOf("site_01JC0000000000000000000001"), "2026-11-09", "2027-01-01")
            listOf(contract.replace(",\"effectiveToDate\":\"2027-01-01\"", ""), contract.replace("\"2027-01-01\"", "null")).forEach { body ->
                withClue(body) {
                    parse(RegistrationKind.CONTRACT, body).shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.Contract>().effectiveToDate shouldBe null
                }
            }
            parse(RegistrationKind.CONTRACT, contract.replace("[\"site_01JC0000000000000000000001\"]", "[]"))
                .shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.Contract>().scopeSiteIds shouldBe emptyList()
        }
        Then("agreement: contract id and service kind") {
            val a = parse(RegistrationKind.SERVICE_AGREEMENT, agreement).shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.ServiceAgreement>()
            listOf(a.submissionId, a.contractId, a.serviceKind) shouldBe listOf(sub, "ctr_01JC0000000000000000000001", "CARD_PG_PAYMENT")
        }
    }

    Given("malformed B1-2 bodies are Malformed without a registration-number attempt (no audit — §4.4 0)") {
        Then("types, missing or unknown keys, duplicates and trailing tokens") {
            listOf(
                contract.replace("[\"site_01JC0000000000000000000001\"]", "\"site_01JC0000000000000000000001\""),
                contract.replace("[\"site_01JC0000000000000000000001\"]", "[1]"),
                contract.replace("[\"site_01JC0000000000000000000001\"]", "[null]"),
                contract.replace("[\"site_01JC0000000000000000000001\"]", "null"),
                contract.replace(",\"scopeSiteIds\":[\"site_01JC0000000000000000000001\"]", ""),
                contract.replace("\"2026-11-09\"", "20261109"),
                contract.replace("\"2026-11-09\"", "null"),
                contract.dropLast(1) + ",\"extra\":\"x\"}",
                contract.dropLast(1) + ",\"effectiveFromDate\":\"2026-11-10\"}",
                "$contract{}",
                agreement.replace("\"CARD_PG_PAYMENT\"", "1"),
                agreement.replace("\"CARD_PG_PAYMENT\"", "null"),
                agreement.replace(",\"serviceKind\":\"CARD_PG_PAYMENT\"", ""),
            ).forEach { body ->
                val kind = if ("contractId" in body) RegistrationKind.SERVICE_AGREEMENT else RegistrationKind.CONTRACT
                withClue(body) { parse(kind, body) shouldBe Parsed.Malformed(false) }
            }
            parse(RegistrationKind.CONTRACT, agreement) shouldBe Parsed.Malformed(false)
            parse(RegistrationKind.SERVICE_AGREEMENT, contract) shouldBe Parsed.Malformed(false)
        }
    }
})
