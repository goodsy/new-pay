package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.adapter.integration.HttpMasterOpsGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.adapter.web.MerchantSearchBodyParser
import com.paycore.admin.merchantops.application.MerchantLookupService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import com.paycore.admin.support.StubMasterOpsServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-CODE-001 MC-2 ADMIN 코드 검색(§3.8 — MASTER는 MC-1에서 이미 지원): 화면 검색 본문 parser의 `merchantCode`(JSON 문자열만), Use Case 형식 검사(§3.1 — 정규화 없음)와
 * **단일 기준 규칙**(MASTER `MerchantSearchRequestParser`와 같이 `limit`만 함께 허용 — 다른 필터·cursor·등록번호와 함께면 MASTER 호출 전 400), 목록 검색과 같이 감사 없음(D-MOPS-002),
 * gateway는 **보낸 기준**으로 응답 모양 `DIRECTORY_BY_CODE`를 고른다(응답을 보고 고르지 않음 — MASTER golden 바이트로 확인, 목록 모양이 오면 해석 불가).
 */
class MerchantCodeSearchSpec : BehaviorSpec({

    val ctx = AdminRequestContext("corr-1", "127.0.0.1")
    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(MerchantCodeSearchSpec::class.java.getResource("/contracts/merchant-ops-response-shapes-v1.golden.json")!!.readText(Charsets.UTF_8))

    class Fixture(roles: Set<String> = setOf("VIEWER")) {
        val searched = mutableListOf<MerchantSearchCriteria>()
        val audits = mutableListOf<AuditRecord>()
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", "ACTIVE", 1, 1, roles)
        private val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = 100
            override val registrationNumberSearchesPerMinute = 100
            override val registrationsPerMinute = 100
        }
        val service = MerchantLookupService(
            object : AbstractPlatformTransactionManager() {
                override fun doGetTransaction(): Any = Any()
                override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
                override fun doCommit(status: DefaultTransactionStatus) = Unit
                override fun doRollback(status: DefaultTransactionStatus) = Unit
            },
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : MasterOpsGateway {
                override fun search(criteria: MerchantSearchCriteria): MasterCallResult =
                    MasterCallResult.Found(mapOf("items" to emptyList<Any>(), "nextCursor" to null)).also { searched += criteria }
                override fun detail(merchantId: String): MasterCallResult = error("not used")
                override fun history(merchantId: String, afterSeq: Long): MasterCallResult = error("not used")
                override fun approval(operation: MerchantApprovalOperation, body: ByteArray, deadline: Boolean): MerchantApprovalResult = error("not used")
                override fun approvalDecision(approvalId: String): MerchantApprovalResult = error("not used")
                override fun command(command: MasterCommand): MasterCommandResult = error("not used")
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    audits += record
                }
            },
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) = Unit
                override fun command(operation: String, result: String) = Unit
                override fun commandUnknown(operation: String) = Unit
            },
            RegistrationNumberBudget(limits),
        )
    }

    Given("MC-2 the screen search body parser") {
        Then("MC-2 merchantCode is a known text field (passed through unchanged — no lower-casing), a non-string merchantCode is malformed") {
            (MerchantSearchBodyParser.parse("""{"merchantCode":"shopabcd12","limit":1}""".toByteArray()) as MerchantSearchBodyParser.Parsed.Criteria).criteria shouldBe
                MerchantSearchCriteria(merchantCode = "shopabcd12", limit = 1)
            (MerchantSearchBodyParser.parse("""{"merchantCode":"ShopABCD12"}""".toByteArray()) as MerchantSearchBodyParser.Parsed.Criteria).criteria.merchantCode shouldBe "ShopABCD12"
            listOf("""{"merchantCode":12345678}""", """{"merchantCode":null}""", """{"merchantCode":["shopabcd12"]}""").forEach { body ->
                withClue(body) { MerchantSearchBodyParser.parse(body.toByteArray()) shouldBe MerchantSearchBodyParser.Parsed.Malformed(false) }
            }
        }
    }

    Given("MC-2 the code search use case") {
        Then("MC-2 a well-formed code (alone or with limit only) reaches MASTER unchanged, without an audit row (a list search is not audited — D-MOPS-002)") {
            val f = Fixture()
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "shopabcd12"), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "abcdef", limit = 1), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "a" + "b".repeat(13)), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.searched.map { it.merchantCode } shouldBe listOf("shopabcd12", "abcdef", "a" + "b".repeat(13))
            f.audits.shouldBeEmpty()
        }
        Then("MC-2 §3.1 format violations are 400 before MASTER (no normalisation): upper case, 5 or 15 chars, leading digit, hyphen, underscore, space") {
            val f = Fixture()
            listOf("ShopABCD12", "abcde", "a" + "b".repeat(14), "1shopabcd", "shop-abcd", "shop_abcd", "shop abcd", " shopabcd12", "").forEach { code ->
                withClue(code) { f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = code), ctx) shouldBe MerchantLookupResult.InvalidInput }
            }
            f.searched.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }
        Then("MC-2 single criterion (MASTER rule — only limit may accompany merchantCode): with merchantId, businessId, lifecycle or after it is 400 before MASTER") {
            val f = Fixture()
            listOf(
                MerchantSearchCriteria(merchantCode = "shopabcd12", merchantId = "mer_01JC0000000000000000000001"),
                MerchantSearchCriteria(merchantCode = "shopabcd12", businessId = "biz_01JC0000000000000000000001"),
                MerchantSearchCriteria(merchantCode = "shopabcd12", lifecycle = "ACTIVE"),
                MerchantSearchCriteria(merchantCode = "shopabcd12", after = "mer_01JC0000000000000000000001"),
                MerchantSearchCriteria(merchantCode = "shopabcd12", limit = 51),
            ).forEach { c -> withClue(c) { f.service.search(f.view.actorId, c, ctx) shouldBe MerchantLookupResult.InvalidInput } }
            f.searched.shouldBeEmpty()
        }
        Then("MC-2 with a registration number it follows the audited number path and is INVALID_INPUT (masked number only) — MASTER is never called") {
            val f = Fixture(setOf("OPERATOR"))
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "shopabcd12", registrationNumber = "1234567890"), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.audits.map { listOf(it.action, it.resultCode, it.detail) } shouldBe listOf(listOf("MERCHANT_BRN_SEARCHED", "INVALID_INPUT", "brn=123*******"))
            f.searched.shouldBeEmpty()
        }
    }

    Given("MC-2 the gateway (real HTTP stub, MASTER golden bytes)") {
        StubMasterOpsServer.start()
        val settings = MasterOpsSettings(
            "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 30, 10, 2, 20,
        )
        val gateway = HttpMasterOpsGateway(settings, MasterCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
        val path = StubMasterOpsServer.SEARCH

        Then("MC-2 a code search sends {merchantCode, limit} only and reads the reply with DIRECTORY_BY_CODE (matchedRetiredCode kept)") {
            StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, mapper.writeValueAsString(golden.get("codeSearch"))))
            val before = StubMasterOpsServer.requestsForPath(path).size
            val found = gateway.search(MerchantSearchCriteria(merchantCode = "shopxyz789", limit = 1)).shouldBeInstanceOf<MasterCallResult.Found>()
            val item = (found.document["items"] as List<*>).single() as Map<*, *>
            listOf(item["merchantCode"], item["matchedRetiredCode"]) shouldBe listOf("shopxyz789", true)
            val sent = StubMasterOpsServer.requestsForPath(path).drop(before).single()
            String(sent.body, Charsets.UTF_8) shouldBe """{"merchantCode":"shopxyz789","limit":1}"""
            sent.signatureValid shouldBe true
        }
        Then("MC-2 control: the shape follows the criterion sent — a list-shaped reply to a code search, or a code-search reply to a list search, is unreadable (fail-closed)") {
            StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, mapper.writeValueAsString(golden.get("list"))))
            gateway.search(MerchantSearchCriteria(merchantCode = "shopabcd12")) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, mapper.writeValueAsString(golden.get("codeSearch"))))
            gateway.search(MerchantSearchCriteria()) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY))
            gateway.search(MerchantSearchCriteria(merchantCode = "shopabcd12")).shouldBeInstanceOf<MasterCallResult.Found>()
        }
    }
})
