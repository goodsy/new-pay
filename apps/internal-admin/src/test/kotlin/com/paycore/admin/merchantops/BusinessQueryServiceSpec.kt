package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.BusinessLookupService
import com.paycore.admin.merchantops.application.BusinessProfileService
import com.paycore.admin.merchantops.application.BusinessQueryService
import com.paycore.admin.merchantops.application.BusinessReadWindow
import com.paycore.admin.merchantops.application.MerchantLookupService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.BusinessQueryResult
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.BusinessReadLimits
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 사업자 조회·번호 조회 Use Case(BUSINESS-PROFILE-001 §7.1·§7.2, §14 BIZ-3-2·BIZ-3-3 — 대역 Port, Spring 없음):
 * 역할 행렬(TC-15 — VIEWER·SECURITY_ADMIN·UNMASK 단독 403, APPROVER·OPERATOR 200 — D-BIZ-006 ①), 상세 `BUSINESS_VIEWED` 반환 전 1행·감사 실패 → 결과 미반환(D-BIZ-006 ②),
 * **사업자 조회 창**(Demo 30 — 목록·상세·이력·번호 조회 공유, 31번째 429·MASTER 미호출, 상세 초과는 `RATE_LIMITED` 1행, 가맹점 조회 창 소모 0 — m-2),
 * 번호 조회(합친 BIZ-LOOKUP — 매 시도 감사, 깨진 본문도 시도, **공유 등록번호 버킷**: 조회 10 → 11번째 429·같은 분 가맹점 BRN 검색·사업자 생성도 소진, 동시 20 → 정확히 10,
 * `businessId`·`registrationStatus` 응답 포함 — D-BIZLOOKUP-001·D-BIZ-006 ③).
 */
class BusinessQueryServiceSpec : BehaviorSpec({

    val actorId = "adm_01JC00000000000000000000V1"
    val biz = "biz_01JC0000000000000000000001"
    val ctx = AdminRequestContext("req-q", "127.0.0.1")
    val found = linkedMapOf<String, Any?>(
        "items" to listOf(linkedMapOf("businessId" to biz, "businessName" to "시험 사업자", "registrationNumberMasked" to "912*******", "registrationStatus" to "DRAFT")),
    )

    class Fixture(roles: Set<String> = setOf("OPERATOR"), status: String = "ACTIVE", businessReads: Int = 30, brnPerMinute: Int = 10, merchantLookups: Int = 30) {
        val audits: MutableList<AuditRecord> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val metrics: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var failAudit = false
        var detailReply: MasterCallResult = MasterCallResult.Found(linkedMapOf("business" to linkedMapOf("businessId" to "biz_01JC0000000000000000000001")))
        var lookupReply: MasterCallResult = MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>()))
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", status, 1, 1, roles)
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val sessions = object : AdminSessionQuery {
            override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
            override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
        }
        val recorder = object : AdminAuditRecorder {
            override fun record(record: AuditRecord) {
                if (failAudit) throw IllegalStateException("injected audit failure")
                audits += record
            }
        }
        val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = merchantLookups
            override val registrationNumberSearchesPerMinute = brnPerMinute
            override val registrationsPerMinute = 100
        }
        val opsMetrics = object : MerchantOpsMetrics {
            override fun rateLimited(kind: String) {
                metrics += "rate:$kind"
            }
            override fun command(operation: String, result: String) = Unit
            override fun commandUnknown(operation: String) = Unit
        }
        val businessMetrics = object : BusinessOpsMetrics {
            override fun lookup(result: String) {
                metrics += "lookup:$result"
            }
            override fun ruleDrift(operation: String) = Unit
        }
        val gateway = object : MasterBusinessGateway {
            override fun search(criteria: BusinessSearchCriteria): MasterCallResult = MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>(), "next" to null)).also { calls += "search" }
            override fun lookup(registrationNumber: String): MasterCallResult = lookupReply.also { calls += "lookup" }
            override fun detail(businessId: String): MasterCallResult = detailReply.also { calls += "detail" }
            override fun history(businessId: String, afterSeq: Long): MasterCallResult =
                MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>(), "nextAfter" to null)).also { calls += "history:$afterSeq" }
            override fun command(command: MasterBusinessCommand): MasterBusinessCommandResult =
                MasterBusinessCommandResult.Decided(false, null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, null).also { calls += "command" }
        }
        val merchantGateway = object : MasterOpsGateway {
            override fun search(criteria: MerchantSearchCriteria): MasterCallResult = MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>(), "nextCursor" to null)).also { calls += "merchant-search" }
            override fun detail(merchantId: String): MasterCallResult = error("not used")
            override fun history(merchantId: String, afterSeq: Long): MasterCallResult = error("not used")
            override fun command(command: MasterCommand): MasterCommandResult = error("not used")
            override fun approval(operation: MerchantApprovalOperation, body: ByteArray, deadline: Boolean): MerchantApprovalResult = error("not used")
            override fun approvalDecision(approvalId: String): MerchantApprovalResult = error("not used")
        }
        val budget = RegistrationNumberBudget(limits) { 0L }
        val window = BusinessReadWindow(object : BusinessReadLimits {
            override val businessReadsPerMinute = businessReads
        }) { 0L }
        val query = BusinessQueryService(tx, sessions, recorder, gateway, window, opsMetrics)
        val lookup = BusinessLookupService(tx, sessions, recorder, gateway, budget, window, opsMetrics, businessMetrics)
        val profiles = BusinessProfileService(tx, sessions, recorder, gateway, limits, opsMetrics, businessMetrics, budget)
        val merchants = MerchantLookupService(tx, sessions, merchantGateway, recorder, limits, opsMetrics, budget)
        fun row(i: Int) = audits[i].let { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}" }
    }

    Given("BIZ-3-2 role matrix (TC-15 — D-BIZ-006 ① 사용자 결정 2026-11-17)") {
        Then("VIEWER, SECURITY_ADMIN or UNMASK alone → 403 for list, detail, history and number lookup; MASTER not called") {
            listOf(setOf("VIEWER"), setOf("SECURITY_ADMIN"), setOf("UNMASK")).forEach { roles ->
                val f = Fixture(roles = roles)
                withClue(roles) {
                    f.query.search(actorId, BusinessSearchCriteria(), ctx) shouldBe BusinessQueryResult.Forbidden
                    f.query.detail(actorId, biz, ctx) shouldBe BusinessQueryResult.Forbidden
                    f.query.history(actorId, biz, null) shouldBe BusinessQueryResult.Forbidden
                    f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.Forbidden
                    f.query.rejectMalformedSearch(actorId) shouldBe BusinessQueryResult.Forbidden
                    f.calls.shouldBeEmpty()
                    // 감사 대상 action(상세·번호 조회)만 FORBIDDEN 1행씩 — 앞단 밖(UNMASK 단독)은 감사 없음.
                    f.audits.map { "${it.action}|${it.resultCode}" } shouldBe
                        if (roles == setOf("UNMASK")) emptyList() else listOf("BUSINESS_VIEWED|FORBIDDEN", "BUSINESS_BRN_LOOKUP|FORBIDDEN")
                }
            }
        }
        Then("APPROVER and OPERATOR → 200 for all four views") {
            listOf(setOf("APPROVER"), setOf("OPERATOR")).forEach { roles ->
                val f = Fixture(roles = roles)
                withClue(roles) {
                    f.query.search(actorId, BusinessSearchCriteria(limit = 20), ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
                    f.query.detail(actorId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
                    f.query.history(actorId, biz, 41).shouldBeInstanceOf<BusinessQueryResult.Found>()
                    f.lookup.lookup(actorId, "9120000001", ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
                    f.calls shouldBe listOf("search", "detail", "history:41", "lookup")
                }
            }
        }
        Then("an inactive account → 403 with no audit") {
            val f = Fixture(status = "LOCKED")
            f.query.detail(actorId, biz, ctx) shouldBe BusinessQueryResult.Forbidden
            f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.Forbidden
            f.audits.shouldBeEmpty()
        }
    }

    Given("BIZ-3-2 detail audit (D-BIZ-006 ② — plain PII to viewers, audited before return)") {
        Then("FOUND → one BUSINESS_VIEWED OK row with targetRef = businessId before the document is returned; NOT_FOUND and UNAVAILABLE are audited too; list and history are not audited") {
            val f = Fixture(roles = setOf("APPROVER"))
            f.query.detail(actorId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.detailReply = MasterCallResult.NotFound
            f.query.detail(actorId, biz, ctx) shouldBe BusinessQueryResult.NotFound
            f.detailReply = MasterCallResult.Unavailable("TIMEOUT")
            f.query.detail(actorId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Unavailable>()
            f.query.search(actorId, BusinessSearchCriteria(), ctx)
            f.query.history(actorId, biz, null)
            f.audits.map { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}|${it.correlationId}" } shouldBe listOf(
                "BUSINESS_VIEWED|OK|null|$biz|req-q", "BUSINESS_VIEWED|NOT_FOUND|null|$biz|req-q", "BUSINESS_VIEWED|UNAVAILABLE|null|$biz|req-q",
            )
        }
        Then("audit failure → AUDIT_UNAVAILABLE and the document is not returned") {
            val f = Fixture()
            f.failAudit = true
            f.query.detail(actorId, biz, ctx) shouldBe BusinessQueryResult.AuditUnavailable
            f.calls shouldBe listOf("detail")
        }
        Then("a malformed businessId → 400 without MASTER call; a malformed cursor or filter → 400 without MASTER call") {
            val f = Fixture()
            f.query.detail(actorId, "biz_x", ctx) shouldBe BusinessQueryResult.InvalidInput
            f.query.history(actorId, biz, -1) shouldBe BusinessQueryResult.InvalidInput
            listOf(
                BusinessSearchCriteria(limit = 51), BusinessSearchCriteria(limit = 0), BusinessSearchCriteria(after = "mer_01JC0000000000000000000001"),
                BusinessSearchCriteria(createdFrom = "2026-11-18", createdTo = "2026-11-17"), BusinessSearchCriteria(createdFrom = "2026-02-30"),
                BusinessSearchCriteria(registrationStatus = "ACTIVE"), BusinessSearchCriteria(businessType = "NONPROFIT"), BusinessSearchCriteria(displayNamePrefix = " 앞"),
            ).forEach { c -> withClue(c) { f.query.search(actorId, c, ctx) shouldBe BusinessQueryResult.InvalidInput } }
            f.query.search(actorId, BusinessSearchCriteria(createdFrom = "2026-11-17", createdTo = "2026-11-17", registrationStatus = "INACTIVE"), ctx)
                .shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.calls shouldBe listOf("search")
        }
    }

    Given("BIZ-3-2 the business read window (m-2 — Demo 30, shared by list·detail·history·lookup, separate from the merchant lookup window)") {
        Then("30 mixed reads pass; the 31st of each kind → 429 without MASTER; detail over the window is audited RATE_LIMITED once; the merchant window is untouched") {
            val f = Fixture(businessReads = 30, brnPerMinute = 100, merchantLookups = 1)
            repeat(8) { f.query.search(actorId, BusinessSearchCriteria(), ctx).shouldBeInstanceOf<BusinessQueryResult.Found>() }
            repeat(8) { f.query.detail(actorId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Found>() }
            repeat(7) { f.query.history(actorId, biz, null).shouldBeInstanceOf<BusinessQueryResult.Found>() }
            repeat(7) { f.lookup.lookup(actorId, "9120000001", ctx).shouldBeInstanceOf<BusinessQueryResult.Found>() }
            val masterCalls = f.calls.size
            masterCalls shouldBe 30
            val audited = f.audits.size
            f.query.search(actorId, BusinessSearchCriteria(), ctx) shouldBe BusinessQueryResult.RateLimited
            f.query.history(actorId, biz, null) shouldBe BusinessQueryResult.RateLimited
            f.query.detail(actorId, biz, ctx) shouldBe BusinessQueryResult.RateLimited
            f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.RateLimited
            f.calls.size shouldBe 30
            f.audits.drop(audited).map { "${it.action}|${it.resultCode}" } shouldBe listOf("BUSINESS_VIEWED|RATE_LIMITED", "BUSINESS_BRN_LOOKUP|RATE_LIMITED")
            f.metrics.count { it == "rate:BUSINESS_READ" } shouldBe 4
            // 별개 대조: 가맹점 조회 창(1/분)은 그대로 — 같은 분 가맹점 검색 1회 통과.
            f.merchants.search(actorId, MerchantSearchCriteria(limit = 5), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
        }
        Then("control: exhausting the merchant lookup window does not consume the business window") {
            val f = Fixture(businessReads = 2, merchantLookups = 2)
            repeat(2) { f.merchants.search(actorId, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>() }
            f.merchants.search(actorId, MerchantSearchCriteria(), ctx) shouldBe MerchantLookupResult.RateLimited
            f.query.search(actorId, BusinessSearchCriteria(), ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.query.history(actorId, biz, null).shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.query.search(actorId, BusinessSearchCriteria(), ctx) shouldBe BusinessQueryResult.RateLimited
        }
    }

    Given("BIZ-3-3 number lookup (merged MERCHANT-BIZ-LOOKUP-001 — every attempt audited, D-MOPS-003)") {
        Then("found → OK `brn=912*******;results=1` with targetRef = businessId; the document keeps businessId and registrationStatus (D-BIZLOOKUP-001·D-BIZ-006 ③); not found → results=0 without target") {
            val f = Fixture(roles = setOf("APPROVER"))
            f.lookupReply = MasterCallResult.Found(found)
            f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.Found(found)
            f.lookupReply = MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>()))
            f.lookup.lookup(actorId, "9120000002", ctx)
            f.row(0) shouldBe "BUSINESS_BRN_LOOKUP|OK|brn=912*******;results=1|$biz"
            f.row(1) shouldBe "BUSINESS_BRN_LOOKUP|OK|brn=912*******;results=0|null"
            f.metrics.count { it == "lookup:OK" } shouldBe 2
        }
        Then("malformed body (null) and wrong formats are attempts: INVALID_INPUT `brn=INVALID`, before any bucket use; MASTER failure → UNAVAILABLE row; audit failure → no result") {
            val f = Fixture(brnPerMinute = 1)
            f.lookup.lookup(actorId, null, ctx) shouldBe BusinessQueryResult.InvalidInput
            f.lookup.lookup(actorId, "912-00-00001", ctx) shouldBe BusinessQueryResult.InvalidInput
            f.lookup.lookup(actorId, "91200000011", ctx) shouldBe BusinessQueryResult.InvalidInput
            f.audits.map { it.detail } shouldBe listOf("brn=INVALID", "brn=INVALID", "brn=INVALID")
            f.calls.shouldBeEmpty()
            f.lookupReply = MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            f.lookup.lookup(actorId, "9120000001", ctx).shouldBeInstanceOf<BusinessQueryResult.Unavailable>()
            f.row(3) shouldBe "BUSINESS_BRN_LOOKUP|UNAVAILABLE|brn=912*******|null"
            val g = Fixture()
            g.failAudit = true
            g.lookupReply = MasterCallResult.Found(found)
            g.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.AuditUnavailable
        }
        Then("shared bucket (C-3): 10 lookups → the 11th is 429 RATE_LIMITED; in the same minute a merchant BRN search and a business create are also refused by the bucket") {
            val f = Fixture(brnPerMinute = 10, businessReads = 100)
            repeat(10) { f.lookup.lookup(actorId, "9120000001", ctx).shouldBeInstanceOf<BusinessQueryResult.Found>() }
            f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.RateLimited
            f.audits.last().let { "${it.resultCode}|${it.detail}" } shouldBe "RATE_LIMITED|brn=912*******"
            f.metrics shouldContain "rate:REGISTRATION_NUMBER"
            f.merchants.search(actorId, MerchantSearchCriteria(registrationNumber = "9120000001"), ctx) shouldBe MerchantLookupResult.RateLimited
            f.profiles.execute(actorId, BusinessInputFixture.create("%032x".format(1)), ctx) shouldBe BusinessProfileResult.RateLimited
            f.calls.count { it == "lookup" } shouldBe 10
            f.calls.none { it == "command" || it == "merchant-search" } shouldBe true
        }
        Then("control: merchant BRN searches and business creates drain the same bucket the lookup uses") {
            val f = Fixture(brnPerMinute = 3, businessReads = 100)
            f.merchants.search(actorId, MerchantSearchCriteria(registrationNumber = "9120000001"), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.profiles.execute(actorId, BusinessInputFixture.create("%032x".format(2)), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>() // 중복 → 확정
            f.lookup.lookup(actorId, "9120000001", ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.lookup.lookup(actorId, "9120000001", ctx) shouldBe BusinessQueryResult.RateLimited
        }
        Then("twenty concurrent lookups against a bucket of 10 → exactly 10 reach MASTER; every attempt has one audit row") {
            val f = Fixture(brnPerMinute = 10, businessReads = 100)
            val pool = Executors.newFixedThreadPool(20)
            val barrier = CyclicBarrier(20)
            val results = (1..20).map { pool.submit<BusinessQueryResult> { barrier.await(10, TimeUnit.SECONDS); f.lookup.lookup(actorId, "9120000001", ctx) } }
                .map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            results.count { it is BusinessQueryResult.Found } shouldBe 10
            results.count { it == BusinessQueryResult.RateLimited } shouldBe 10
            f.calls.count { it == "lookup" } shouldBe 10
            f.audits.size shouldBe 20
        }
    }
})
