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

    Given("B2-1 contract revision bodies (§9.2 — contractId instead of partyBusinessId, same site array and dates)") {
        val revision = contract.replace("\"partyBusinessId\":\"biz_01JC0000000000000000000001\"", "\"contractId\":\"ctr_01JC0000000000000000000001\"")
        Then("ids, the site array and both dates come through unchanged; an absent or null end is no end") {
            val r = parse(RegistrationKind.CONTRACT_REVISION, revision).shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.ContractRevision>()
            listOf(r.submissionId, r.contractId, r.scopeMerchantId, r.scopeSiteIds, r.effectiveFromDate, r.effectiveToDate) shouldBe
                listOf(sub, "ctr_01JC0000000000000000000001", "mer_01JC0000000000000000000001", listOf("site_01JC0000000000000000000001"), "2026-11-09", "2027-01-01")
            parse(RegistrationKind.CONTRACT_REVISION, revision.replace("\"2027-01-01\"", "null"))
                .shouldBeInstanceOf<Parsed.Request>().request.shouldBeInstanceOf<RegistrationRequest.ContractRevision>().effectiveToDate shouldBe null
        }
        Then("a contract-draft body, a missing contractId, an extra key, a non-array site list or a number date are Malformed (no registration-number attempt)") {
            listOf(
                contract,
                revision.replace("\"contractId\":\"ctr_01JC0000000000000000000001\",", ""),
                revision.dropLast(1) + ",\"partyBusinessId\":\"biz_01JC0000000000000000000001\"}",
                revision.replace("[\"site_01JC0000000000000000000001\"]", "\"site_01JC0000000000000000000001\""),
                revision.replace("\"2026-11-09\"", "20261109"),
            ).forEach { body -> withClue(body) { parse(RegistrationKind.CONTRACT_REVISION, body) shouldBe Parsed.Malformed(false) } }
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

    Given("B3-1 1-person bodies (§9.14 — exact fields; site review rejection has an optional evidenceRef and no decision field, C-40)") {
        val s = "a".repeat(32)
        val activation = """{"submissionId":"$s","merchantAccountId":"mid_01JC0000000000000000000001","paymentMethod":"CARD"}"""
        val rejection = """{"submissionId":"$s","merchantSiteId":"site_01JC0000000000000000000001","reasonCode":"DOCUMENT_INSUFFICIENT"}"""
        Then("well-formed bodies come through; evidenceRef absent or null is no evidence") {
            parse(RegistrationKind.PAYMENT_METHOD_ACTIVATION, activation).shouldBeInstanceOf<Parsed.Request>().request
                .shouldBeInstanceOf<RegistrationRequest.PaymentMethodActivation>().let { listOf(it.merchantAccountId, it.paymentMethod) } shouldBe
                listOf("mid_01JC0000000000000000000001", "CARD")
            listOf(rejection, rejection.dropLast(1) + ",\"evidenceRef\":null}").forEach {
                parse(RegistrationKind.SITE_REVIEW_REJECTION, it).shouldBeInstanceOf<Parsed.Request>().request
                    .shouldBeInstanceOf<RegistrationRequest.SiteReviewRejection>().evidenceRef shouldBe null
            }
            parse(RegistrationKind.SITE_REVIEW_REJECTION, rejection.dropLast(1) + ",\"evidenceRef\":\"PG-1\"}").shouldBeInstanceOf<Parsed.Request>().request
                .shouldBeInstanceOf<RegistrationRequest.SiteReviewRejection>().evidenceRef shouldBe "PG-1"
        }
        Then("a decision field (APPROVED via the 1-person channel), unknown keys, missing keys and wrong types are Malformed") {
            listOf(
                RegistrationKind.SITE_REVIEW_REJECTION to rejection.dropLast(1) + ",\"decision\":\"APPROVED\"}",
                RegistrationKind.SITE_REVIEW_REJECTION to rejection.replace(",\"reasonCode\":\"DOCUMENT_INSUFFICIENT\"", ""),
                RegistrationKind.SITE_REVIEW_REJECTION to rejection.dropLast(1) + ",\"evidenceRef\":1}",
                RegistrationKind.SITE_REVIEW_REJECTION to activation,
                RegistrationKind.PAYMENT_METHOD_ACTIVATION to activation.dropLast(1) + ",\"extra\":\"x\"}",
                RegistrationKind.PAYMENT_METHOD_ACTIVATION to activation.replace("\"CARD\"", "null"),
                RegistrationKind.PAYMENT_METHOD_ACTIVATION to rejection,
            ).forEach { (kind, body) -> withClue(body) { parse(kind, body) shouldBe Parsed.Malformed(false) } }
        }
    }
})
