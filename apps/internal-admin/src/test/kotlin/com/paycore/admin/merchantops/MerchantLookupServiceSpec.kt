package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.MerchantLookupService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * 가맹점 조회 Use Case 판정·감사 표(MERCHANT-OPS-001 §5·AC 9·10 — 대역 Port, Spring 없음):
 * 역할 행렬(조회 4역할, 등록번호 검색 OPERATOR·APPROVER), **모든 등록번호 검색 시도 감사**(FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE — 평문 없음, 찾으면 businessId),
 * 상세 감사(OK·NOT_FOUND, MASTER 불가 시 감사 없음), 목록·이력 감사 없음, 감사 실패 → AUDIT_UNAVAILABLE·결과 미반환, 형식 위반은 MASTER 호출 전 거부, 속도 제한(전용·공용),
 * MASTER 호출은 TX 밖·감사는 TX 안.
 */
class MerchantLookupServiceSpec : BehaviorSpec({

    val brn = "1234567890"
    val mer = "mer_01JC0000000000000000000001"
    val biz = "biz_01JC0000000000000000000001"
    val ctx = AdminRequestContext("corr-1", "127.0.0.1")

    class Fixture(roles: Set<String> = setOf("OPERATOR"), status: String = "ACTIVE", lookupPerMinute: Int = 100, brnPerMinute: Int = 100) {
        val audits = mutableListOf<AuditRecord>()
        var auditFails = false
        val calls = mutableListOf<String>()
        val rateLimited = mutableListOf<String>()
        var auditInTx = mutableListOf<Boolean>()
        var callInTx = mutableListOf<Boolean>()
        var searchReply: MasterCallResult = MasterCallResult.Found(mapOf("items" to emptyList<Any>(), "nextCursor" to null))
        var detailReply: MasterCallResult = MasterCallResult.NotFound
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", status, 1, 1, roles)
        val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = lookupPerMinute
            override val registrationNumberSearchesPerMinute = brnPerMinute
            override val registrationsPerMinute = 100
        }
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val service = MerchantLookupService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : MasterOpsGateway {
                override fun search(criteria: MerchantSearchCriteria): MasterCallResult = searchReply.also { calls += "search"; callInTx += TransactionSynchronizationManager.isActualTransactionActive() }
                override fun detail(merchantId: String): MasterCallResult = detailReply.also { calls += "detail"; callInTx += TransactionSynchronizationManager.isActualTransactionActive() }
                override fun history(merchantId: String, afterSeq: Long): MasterCallResult = MasterCallResult.Found(mapOf("items" to emptyList<Any>())).also { calls += "history:$afterSeq" }
                // MERCHANT-OPS-002 B2-2: 승인 명령 채널(이 시험은 쓰지 않는다).
                override fun approval(operation: com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation, body: ByteArray, deadline: Boolean):
                    com.paycore.admin.merchantops.application.provided.MerchantApprovalResult = error("not used")
                override fun approvalDecision(approvalId: String): com.paycore.admin.merchantops.application.provided.MerchantApprovalResult = error("not used")
                override fun command(command: MasterCommand): MasterCommandResult = error("lookup never sends a command")
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    auditInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    if (auditFails) throw IllegalStateException("injected audit failure")
                    audits += record
                }
            },
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) {
                    rateLimited += kind
                }
                override fun command(operation: String, result: String) = Unit
                override fun commandUnknown(operation: String) = Unit
            },
            // MERCHANT-OPS-002 C-13: 등록번호 버킷은 사업자 등록과 공유하는 Bean이다(시험은 같은 limits로 하나 만든다 — 기대값 불변).
            RegistrationNumberBudget(limits),
        )
        val actor get() = view.actorId

        fun auditRows() = audits.map { listOf(it.action, it.resultCode, it.detail, it.targetRef) }
    }

    Given("the view roles") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN may list, view and read history; UNMASK-only, no role and inactive accounts are forbidden without a MASTER call") {
            listOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.search(f.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
                f.service.history(f.actor, mer, null).shouldBeInstanceOf<MerchantLookupResult.Found>()
                f.calls shouldBe listOf("search", "history:0")
                f.audits.shouldBeEmpty()
            }
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("OPERATOR"), status = "SUSPENDED")).forEach { f ->
                f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx) shouldBe MerchantLookupResult.Forbidden
                f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.Forbidden
                f.service.history(f.actor, mer, null) shouldBe MerchantLookupResult.Forbidden
                f.calls.shouldBeEmpty()
                f.audits.shouldBeEmpty()
            }
        }
        Then("malformed filters are refused before MASTER (no audit for a plain list)") {
            val f = Fixture()
            listOf(
                MerchantSearchCriteria(merchantId = "mer_x"), MerchantSearchCriteria(businessId = mer), MerchantSearchCriteria(lifecycle = "active"),
                MerchantSearchCriteria(after = biz), MerchantSearchCriteria(limit = 51), MerchantSearchCriteria(limit = 0),
            ).forEach { f.service.search(f.actor, it, ctx) shouldBe MerchantLookupResult.InvalidInput }
            f.service.detail(f.actor, "mer_x", ctx) shouldBe MerchantLookupResult.InvalidInput
            f.service.history(f.actor, mer, -1) shouldBe MerchantLookupResult.InvalidInput
            f.calls.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }
    }

    Given("a search body that failed strict parsing (Q-MOPS-07)") {
        Then("a registration-number attempt is audited as brn=INVALID: FORBIDDEN without the number role, otherwise INVALID_INPUT; MASTER is never called") {
            listOf("VIEWER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.rejectMalformedSearch(f.actor, registrationNumberAttempt = true, context = ctx) shouldBe MerchantLookupResult.Forbidden
                f.auditRows() shouldBe listOf(listOf("MERCHANT_BRN_SEARCHED", "FORBIDDEN", "brn=INVALID", null))
                f.calls.shouldBeEmpty()
            }
            listOf("OPERATOR", "APPROVER").forEach { role ->
                val f = Fixture(setOf(role), brnPerMinute = 1)
                repeat(2) { f.service.rejectMalformedSearch(f.actor, registrationNumberAttempt = true, context = ctx) shouldBe MerchantLookupResult.InvalidInput }
                // 형식 위반은 속도 제한 앞(§5.2 순서) — 상한 1이어도 두 번 모두 INVALID_INPUT이고 각각 감사된다.
                f.auditRows() shouldBe List(2) { listOf("MERCHANT_BRN_SEARCHED", "INVALID_INPUT", "brn=INVALID", null) }
                f.audits.forEach { listOf(it.actorId, it.correlationId) shouldBe listOf(f.actor, "corr-1") }
                f.calls.shouldBeEmpty()
                f.rateLimited.shouldBeEmpty()
            }
        }
        Then("a malformed body that is not a registration-number attempt is INVALID_INPUT without audit; inactive or role-less accounts are forbidden without audit") {
            val f = Fixture(setOf("OPERATOR"))
            f.service.rejectMalformedSearch(f.actor, registrationNumberAttempt = false, context = ctx) shouldBe MerchantLookupResult.InvalidInput
            f.audits.shouldBeEmpty()
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("OPERATOR"), status = "SUSPENDED")).forEach { g ->
                g.service.rejectMalformedSearch(g.actor, registrationNumberAttempt = true, context = ctx) shouldBe MerchantLookupResult.Forbidden
                g.audits.shouldBeEmpty()
                g.calls.shouldBeEmpty()
            }
        }
        Then("an audit failure for a malformed attempt is AUDIT_UNAVAILABLE; the audit ran inside a transaction") {
            val f = Fixture(setOf("OPERATOR"))
            f.auditFails = true
            f.service.rejectMalformedSearch(f.actor, registrationNumberAttempt = true, context = ctx) shouldBe MerchantLookupResult.AuditUnavailable
            f.auditInTx shouldBe listOf(true)
            f.calls.shouldBeEmpty()
        }
    }

    Given("searches by the full business registration number (D-MOPS-003)") {
        Then("VIEWER and SECURITY_ADMIN are forbidden and each attempt is audited with the masked number only") {
            listOf("VIEWER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx) shouldBe MerchantLookupResult.Forbidden
                f.auditRows() shouldBe listOf(listOf("MERCHANT_BRN_SEARCHED", "FORBIDDEN", "brn=123*******", null))
                f.calls.shouldBeEmpty()
            }
        }
        Then("OPERATOR and APPROVER search; found results are audited with the business as target and the result count") {
            listOf("OPERATOR", "APPROVER").forEach { role ->
                val f = Fixture(setOf(role))
                f.searchReply = MasterCallResult.Found(mapOf("items" to listOf(mapOf("businessId" to biz), mapOf("businessId" to biz)), "nextCursor" to null))
                f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
                f.auditRows() shouldBe listOf(listOf("MERCHANT_BRN_SEARCHED", "OK", "brn=123*******;results=2", biz))
                f.audits.single().let { listOf(it.actorId, it.actorKind, it.correlationId, it.remoteIp) } shouldBe listOf(f.actor, "ADMIN", "corr-1", "127.0.0.1")
            }
        }
        Then("not found is audited too (results=0, no target); malformed numbers are audited as INVALID without any digits and never reach MASTER") {
            val f = Fixture()
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = "0000000001"), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            listOf("123456789", "12345678901", "123-45-6789", "12345678９0").forEach {
                f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = it), ctx) shouldBe MerchantLookupResult.InvalidInput
            }
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn, limit = 99), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.calls shouldBe listOf("search")
            f.auditRows() shouldBe listOf(listOf("MERCHANT_BRN_SEARCHED", "OK", "000*******;results=0".let { "brn=$it" }, null)) +
                List(4) { listOf("MERCHANT_BRN_SEARCHED", "INVALID_INPUT", "brn=INVALID", null) } +
                listOf(listOf("MERCHANT_BRN_SEARCHED", "INVALID_INPUT", "brn=123*******", null))
            f.audits.forEach { it.toString() + it.detail shouldNotContain "4567890" }
        }
        Then("MASTER unavailable is audited as UNAVAILABLE; the dedicated per-minute limit is audited as RATE_LIMITED and counted") {
            val f = Fixture(brnPerMinute = 1)
            f.searchReply = MasterCallResult.Unavailable("TIMEOUT")
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx) shouldBe MerchantLookupResult.Unavailable("TIMEOUT")
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx) shouldBe MerchantLookupResult.RateLimited
            f.auditRows().map { it[1] } shouldBe listOf("UNAVAILABLE", "RATE_LIMITED")
            f.rateLimited shouldBe listOf("REGISTRATION_NUMBER")
            f.calls shouldBe listOf("search")
            // 일반 목록은 전용 한도와 별개다.
            f.service.search(f.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Unavailable>()
        }
        Then("an audit failure returns AUDIT_UNAVAILABLE and no results; MASTER was called outside, the audit inside a transaction") {
            val f = Fixture()
            f.searchReply = MasterCallResult.Found(mapOf("items" to listOf(mapOf("businessId" to biz)), "nextCursor" to null))
            f.auditFails = true
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx) shouldBe MerchantLookupResult.AuditUnavailable
            f.callInTx shouldBe listOf(false)
            f.auditInTx shouldBe listOf(true)
        }
    }

    Given("MERCHANT-CODE-001 MC-3-12 target_ref stored values (§5.6.4 C-32 — MERCHANT_VIEWED, registration-number search)") {
        Then("MERCHANT_VIEWED FOUND = the code in the response + mer_ key; NOT_FOUND = mer_ + mer_ (no code); a registration-number search keeps biz_ with no merchant key") {
            val f = Fixture(setOf("OPERATOR"))
            f.detailReply = MasterCallResult.Found(mapOf("merchant" to mapOf("merchantId" to mer, "merchantCode" to "shopabcd12")))
            f.service.detail(f.actor, mer, ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.detailReply = MasterCallResult.NotFound
            f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.NotFound
            f.searchReply = MasterCallResult.Found(mapOf("items" to listOf(mapOf("businessId" to biz)), "nextCursor" to null))
            f.service.search(f.actor, MerchantSearchCriteria(registrationNumber = brn), ctx)
            f.audits.map { listOf(it.action, it.targetRef, it.targetMerchantId) } shouldBe listOf(
                listOf("MERCHANT_VIEWED", "shopabcd12", mer), listOf("MERCHANT_VIEWED", mer, mer), listOf("MERCHANT_BRN_SEARCHED", biz, null),
            )
        }
    }

    Given("detail views (D-MOPS-002)") {
        Then("FOUND and NOT_FOUND are audited as MERCHANT_VIEWED with the merchant as target; MASTER unavailable is not audited") {
            val f = Fixture(setOf("VIEWER"))
            // MASTER 상세 모양(merchant.merchantCode 필수 — MC-1)과 같은 대역 문서.
            f.detailReply = MasterCallResult.Found(mapOf("merchant" to mapOf("merchantId" to mer, "merchantCode" to "shopabcd12")))
            f.service.detail(f.actor, mer, ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.detailReply = MasterCallResult.NotFound
            f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.NotFound
            f.detailReply = MasterCallResult.Unavailable("HTTP_503")
            f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.Unavailable("HTTP_503")
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 MERCHANT_VIEWED FOUND의 대상 표기 = 응답의 현재 코드(내부 키는 target_merchant_id), NOT_FOUND는 mer_ 그대로.
            f.auditRows() shouldBe listOf(listOf("MERCHANT_VIEWED", "OK", null, "shopabcd12"), listOf("MERCHANT_VIEWED", "NOT_FOUND", null, mer))
        }
        Then("an audit failure withholds the detail") {
            val f = Fixture()
            f.detailReply = MasterCallResult.Found(mapOf("merchant" to mapOf("merchantId" to mer)))
            f.auditFails = true
            f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.AuditUnavailable
        }
        Then("the shared lookup limit applies to list, detail and history and is counted as LOOKUP") {
            val f = Fixture(lookupPerMinute = 2)
            f.service.search(f.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.service.history(f.actor, mer, 5).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.service.detail(f.actor, mer, ctx) shouldBe MerchantLookupResult.RateLimited
            f.rateLimited shouldBe listOf("LOOKUP")
            f.calls shouldBe listOf("search", "history:5")
        }
    }
})
