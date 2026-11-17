package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotContain
import java.time.Instant

/**
 * MERCHANT-OPS-002 §3.2·§8(B1-1) 명령 본문 엄격 해석(단위 — Spring 없음): 정상 본문 → Use Case 명령, 모르는 key·빠진 key·중복 key·뒤따르는 토큰·자료형 강제 변환·`null`·`actor` 접두 위반·
 * Domain 값 규칙 위반(대문자 host·IP host·앞뒤 공백 이름·형식 아닌 ID — 사업자등록번호 형식은 새 생성 경로 BusinessProfileRequestParserSpec) → null(400 — 어느 필드인지 말하지 않는다).
 */
class MerchantOperationsRequestParserSpec : BehaviorSpec({

    val biz = "biz_01JC0000000000000000000001"
    val mer = "mer_01JC0000000000000000000001"
    val ctr = "ctr_01JC0000000000000000000001"
    val tomorrow = MerchantOperationsBodies.KST_TOMORROW
    fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    Given("well-formed command bodies (what ADMIN sends — C-4·C-14)") {
        // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 사업자 등록 명령(`business`)이 제거돼 공통 필드·구조 시험의 운반 본문을 가맹점 명령으로 옮겼다(같은 단언).
        // 사업자등록번호 형식(하이픈·자릿수·전각)은 새 생성 경로 BusinessProfileRequestParserSpec(registrationNumber FORMAT)·값 객체 MasterValuesSpec이 고정한다.
        Then("merchant: the key, admin actor, correlation id and Domain values come through unchanged") {
            val s = Submission()
            val c = MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(biz, s, name = "시험 가맹점"))).shouldNotBeNull()
            listOf(c.request.idempotencyKey.value, c.request.actor.value, c.request.correlationId) shouldBe listOf(s.idempotencyKey, s.actor, s.correlationId)
            c.businessId.value shouldBe biz
            c.displayName.value shouldBe "시험 가맹점"
            s.idempotencyKey.length shouldBe 63
            s.correlationId.length shouldBe 36
        }
        Then("C-27: the legacy business command parser no longer exists (no `business` entry point)") {
            MerchantOperationsRequestParser::class.java.declaredMethods.map { it.name } shouldNotContain "business"
        }
        Then("merchant and site: ids, channel type and the lower-case identifier") {
            MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(biz))).shouldNotBeNull().businessId.value shouldBe biz
            val site = MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.registerSite(mer, identifier = "demo-shop2.paycore.test"))).shouldNotBeNull()
            listOf(site.merchantId.value, site.channelType, site.channelIdentifier) shouldBe listOf(mer, ChannelType.WEB, "demo-shop2.paycore.test")
            MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.registerSite(mer, type = "APP", identifier = "test.paycore.app"))).shouldNotBeNull().channelType shouldBe ChannelType.APP
        }
    }

    Given("malformed bodies (400 REQUEST_MALFORMED before the use case — nothing stored)") {
        val good = MerchantOperationsBodies.registerMerchant(biz, code = "shopabcd12")
        Then("structure: unknown, missing or duplicate keys, trailing tokens, non-object roots and empty bodies") {
            listOf(
                good.dropLast(1) + ",\"unknown\":\"x\"}",
                good.replace(Regex(",\"displayName\":\"[^\"]*\""), ""),
                good.dropLast(1) + ",\"displayName\":\"again\"}",
                "$good{}",
                "[$good]",
                "\"text\"",
                "",
                "{}",
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.merchant(bytes(body)).shouldBeNull() } }
            MerchantOperationsRequestParser.merchant(bytes(good)).shouldNotBeNull() // 대조
        }
        Then("types: numbers, booleans, arrays, objects and null are not coerced to strings") {
            listOf(
                good.replace("\"shopabcd12\"", "123456"),
                good.replace("\"shopabcd12\"", "null"),
                good.replace("\"shopabcd12\"", "[\"shopabcd12\"]"),
                good.replace("\"shopabcd12\"", "{\"v\":\"shopabcd12\"}"),
                good.replace("\"shopabcd12\"", "true"),
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.merchant(bytes(body)).shouldBeNull() } }
        }
        Then("actor namespace (§3.1): only admin:<id> — no other namespace, no bare prefix") {
            listOf("system:demo-seed", "operator-1", "admin:", "Admin:adm_x", "admin:has space").forEach { actor ->
                withClue(actor) { MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.withActor(actor))).shouldBeNull() }
            }
            MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.withActor("admin:adm_01JC00000000000000000000V2"))).shouldNotBeNull()
        }
        Then("common fields: idempotency key and correlation id formats") {
            val s = Submission()
            val body = MerchantOperationsBodies.registerMerchant(biz, s)
            listOf(
                body.replace(s.idempotencyKey, "has space"),
                body.replace(s.idempotencyKey, "k".repeat(65)),
                body.replace(s.idempotencyKey, ""),
                body.replace(s.correlationId, "c".repeat(129)),
                body.replace(s.correlationId, "상관"),
                body.replace(s.correlationId, ""),
            ).forEach { b -> withClue(b) { MerchantOperationsRequestParser.merchant(bytes(b)).shouldBeNull() } }
        }
        Then("Domain value rules: padded names, malformed ids, upper-case or IP hosts, unknown channel types") {
            listOf(" 시험", "시험 ", "", "a".repeat(101)).forEach { name ->
                withClue(name) { MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(biz, name = name))).shouldBeNull() }
            }
            listOf("biz_1", mer, "biz_01jc0000000000000000000001").forEach { id ->
                withClue(id) { MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerMerchant(id))).shouldBeNull() }
            }
            MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.withUpperCaseHost(mer))).shouldBeNull()
            listOf("10.0.0.1", "shop.example.test:443", "https://shop.example.test", "shop").forEach { host ->
                withClue(host) { MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.registerSite(mer, identifier = host))).shouldBeNull() }
            }
            MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.registerSite(mer, type = "web"))).shouldBeNull()
            MerchantOperationsRequestParser.site(bytes(MerchantOperationsBodies.registerSite(biz))).shouldBeNull()
        }
        Then("a body for one command is not accepted by another (field sets are exact)") {
            MerchantOperationsRequestParser.site(bytes(good)).shouldBeNull()
            MerchantOperationsRequestParser.merchant(bytes(MerchantOperationsBodies.registerSite(mer))).shouldBeNull()
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.addServiceAgreement(ctr))).shouldBeNull()
            MerchantOperationsRequestParser.serviceAgreement(bytes(MerchantOperationsBodies.registerContract(biz, mer))).shouldBeNull()
        }
    }

    Given("B1-2 contract draft bodies (§3.2 — scopeSiteIds array ≤ 50, effectiveFrom/effectiveTo …Z with milliseconds, effectiveTo optional)") {
        val site1 = "site_01JC0000000000000000000001"
        val site2 = "site_01JC0000000000000000000002"

        Then("MERCHANT scope (empty array, no end) and SITES scope (with an exclusive end) come through as Domain values") {
            val s = Submission()
            val merchantWide = MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer, s = s, from = "2026-11-08T15:00:00.000Z"))).shouldNotBeNull()
            listOf(merchantWide.request.idempotencyKey.value, merchantWide.request.correlationId) shouldBe listOf(s.idempotencyKey, s.correlationId)
            listOf(merchantWide.partyBusinessId.value, merchantWide.scope.merchantId.value, merchantWide.scope.siteIds) shouldBe listOf(biz, mer, emptySet<Any>())
            merchantWide.effectivePeriod shouldBe EffectivePeriod(Instant.parse("2026-11-08T15:00:00Z"), null)

            val sites = MerchantOperationsRequestParser.contract(
                bytes(MerchantOperationsBodies.registerContract(biz, mer, listOf(site2, site1), from = "2026-11-08T15:00:00.000Z", to = "2026-12-31T15:00:00.000Z")),
            ).shouldNotBeNull()
            sites.scope.siteIds.map { it.value }.toSet() shouldBe setOf(site1, site2)
            sites.effectivePeriod shouldBe EffectivePeriod(Instant.parse("2026-11-08T15:00:00Z"), Instant.parse("2026-12-31T15:00:00Z"))
            // 선택 필드 effectiveTo는 null도 "없음"으로 받는다(§3.2 — null 거부는 선택 필드 제외).
            val nullEnd = MerchantOperationsBodies.registerContract(biz, mer).dropLast(1) + ",\"effectiveTo\":null}"
            MerchantOperationsRequestParser.contract(bytes(nullEnd)).shouldNotBeNull().effectivePeriod.to.shouldBeNull()
        }
        Then("C-2: 50 scope sites are accepted, 51 are rejected; duplicate, malformed or non-string site ids are rejected") {
            val fifty = (1..50).map { "site_01JC" + it.toString().padStart(22, '0') }
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer, fifty))).shouldNotBeNull().scope.siteIds.size shouldBe 50
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.withTooManyScopeSites(biz, mer))).shouldBeNull()
            val one = MerchantOperationsBodies.registerContract(biz, mer, listOf(site1))
            listOf(
                MerchantOperationsBodies.registerContract(biz, mer, listOf(site1, site1)),
                MerchantOperationsBodies.registerContract(biz, mer, listOf("site_1")),
                MerchantOperationsBodies.registerContract(biz, mer, listOf(mer)),
                one.replace("[\"$site1\"]", "[1]"),
                one.replace("[\"$site1\"]", "[null]"),
                one.replace("[\"$site1\"]", "\"$site1\""),
                one.replace("[\"$site1\"]", "null"),
                one.replace("[\"$site1\"]", "[[\"$site1\"]]"),
                one.replace(Regex(",\"scopeSiteIds\":\\[[^]]*]"), ""),
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.contract(bytes(body)).shouldBeNull() } }
        }
        Then("timestamps: only the canonical …Z form with milliseconds; real calendar values; the end must be after the start") {
            listOf(
                "2026-11-08T15:00:00Z", "2026-11-08T15:00:00.000+09:00", "2026-11-08T15:00:00.000000Z", "2026-11-08 15:00:00.000Z", "2026-11-08",
                "2026-02-30T15:00:00.000Z", "2026-11-08T24:00:00.000Z", "2026-11-08T15:00:60.000Z", "+2026-11-08T15:00:00.000Z", "",
            ).forEach { from -> withClue(from) { MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer, from = from))).shouldBeNull() } }
            val from = "2026-11-08T15:00:00.000Z"
            listOf(from, "2026-11-07T15:00:00.000Z", "2026-11-08T15:00:00Z").forEach { to ->
                withClue(to) { MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer, from = from, to = to))).shouldBeNull() }
            }
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer).replace("\"effectiveFrom\":\"$tomorrow\"", "\"effectiveFrom\":null"))).shouldBeNull()
            // 소급 시작은 해석 단계가 판정하지 않는다(§3.4 — Domain만): 형식이 맞으면 통과한다.
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, mer, from = "2020-01-01T15:00:00.000Z"))).shouldNotBeNull()
        }
        Then("ids and kinds: party must be a business id, scope merchant a merchant id; the agreement takes a contract id and a known service kind") {
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(mer, mer))).shouldBeNull()
            MerchantOperationsRequestParser.contract(bytes(MerchantOperationsBodies.registerContract(biz, biz))).shouldBeNull()
            val agreement = MerchantOperationsRequestParser.serviceAgreement(bytes(MerchantOperationsBodies.addServiceAgreement(ctr))).shouldNotBeNull()
            listOf(agreement.contractId.value, agreement.serviceKind) shouldBe listOf(ctr, ServiceKind.CARD_PG_PAYMENT)
            listOf(
                MerchantOperationsBodies.addServiceAgreement(ctr, kind = "card_pg_payment"),
                MerchantOperationsBodies.addServiceAgreement(ctr, kind = "BANK_TRANSFER"),
                MerchantOperationsBodies.addServiceAgreement("ctr_1"),
                MerchantOperationsBodies.addServiceAgreement(mer),
                MerchantOperationsBodies.addServiceAgreement(ctr).dropLast(1) + ",\"extra\":\"x\"}",
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.serviceAgreement(bytes(body)).shouldBeNull() } }
        }
    }

    Given("B2-1 contract revision bodies (§9.2 — same scope/period rules as the contract draft, contractId instead of partyBusinessId)") {
        val ctr = "ctr_01JC0000000000000000000001"
        val mer = "mer_01JC0000000000000000000001"
        val site = "site_01JC0000000000000000000001"
        Then("MERCHANT and SITES scopes come through as Domain values; a past start is not judged here (Domain decides — D-MOPS-007 ②)") {
            val s = MerchantOperationsBodies.Submission()
            val merchantWide = MerchantOperationsRequestParser.contractRevision(bytes(MerchantOperationsBodies.registerContractRevision(ctr, mer, s = s))).shouldNotBeNull()
            listOf(merchantWide.request.idempotencyKey.value, merchantWide.request.actor.value, merchantWide.request.correlationId) shouldBe listOf(s.idempotencyKey, s.actor, s.correlationId)
            listOf(merchantWide.contractId.value, merchantWide.scope.merchantId.value, merchantWide.scope.siteIds.size) shouldBe listOf(ctr, mer, 0)
            merchantWide.effectivePeriod.to.shouldBeNull()
            val sites = MerchantOperationsRequestParser.contractRevision(
                bytes(MerchantOperationsBodies.registerContractRevision(ctr, mer, listOf(site), to = "2026-12-31T15:00:00.000Z")),
            ).shouldNotBeNull()
            sites.scope.siteIds.map { it.value } shouldBe listOf(site)
            MerchantOperationsRequestParser.contractRevision(bytes(MerchantOperationsBodies.registerContractRevision(ctr, mer, from = "2020-01-01T15:00:00.000Z"))).shouldNotBeNull()
        }
        Then("malformed: business id as contract, 51 sites, duplicate site, non-canonical time, end ≤ start, admin actor namespace, a contract-draft body") {
            listOf(
                MerchantOperationsBodies.registerContractRevision("biz_01JC0000000000000000000001", mer),
                MerchantOperationsBodies.registerContractRevision(ctr, mer, (1..51).map { "site_01JC00000000000000000000%02d".format(it % 100).take(31) }),
                MerchantOperationsBodies.registerContractRevision(ctr, mer, listOf(site, site)),
                MerchantOperationsBodies.registerContractRevision(ctr, mer, from = "2026-10-10"),
                MerchantOperationsBodies.registerContractRevision(ctr, mer, from = "2026-10-10T15:00:00.000Z", to = "2026-10-10T15:00:00.000Z"),
                MerchantOperationsBodies.registerContractRevision(ctr, mer).replace("\"actor\":\"admin:", "\"actor\":\"system:"),
                MerchantOperationsBodies.registerContract("biz_01JC0000000000000000000001", mer),
                MerchantOperationsBodies.registerContractRevision(ctr, mer).dropLast(1) + ",\"extra\":\"x\"}",
            ).forEach { body -> withClue(body) { MerchantOperationsRequestParser.contractRevision(bytes(body)).shouldBeNull() } }
        }
    }
})
