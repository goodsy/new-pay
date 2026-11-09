package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.MerchantRegistrationService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.provided.MerchantRegistrationResult
import com.paycore.admin.merchantops.application.provided.RegistrationKind
import com.paycore.admin.merchantops.application.provided.RegistrationRequest
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 가맹점 등록 Use Case 판정·감사 표(MERCHANT-OPS-002 §4.4·§6·§8 — 대역 Port, Spring 없음):
 * OPERATOR만(VIEWER·APPROVER·SECURITY_ADMIN 403 + 감사 FORBIDDEN, 사업자 brn 행렬 M-1), 형식 400(MASTER 미호출, 사업자만 감사), 등록 버킷, **등록번호 버킷 예약·확정·환불(C-13)**,
 * 시도 행 → 결과 행 순서·TX 경계, 시도 감사 실패 → MASTER 미호출, 결과 감사 실패 503, 결과 분류(Unknown·RetrySameKey·Busy·KeyReused), correlationId `mos.<submissionId>`(C-14)와
 * 사전 거절의 ADMIN 요청 ID(n-4), MASTER 키 파생(계정 이름공간 C-4)과 감사·로그 비노출.
 */
class MerchantRegistrationServiceSpec : BehaviorSpec({

    val operatorId = "adm_01JC00000000000000000000V1"
    val otherOperatorId = "adm_01JC00000000000000000000V2"
    val brn = "7781234560"
    val biz = "biz_01JC0000000000000000000001"
    val mer = "mer_01JC0000000000000000000001"
    val ctx = AdminRequestContext("req-1", "127.0.0.1")
    var subSeq = 0
    fun sub() = "%032x".format(++subSeq)

    class Fixture(
        roles: Set<String> = setOf("OPERATOR"),
        status: String = "ACTIVE",
        brnPerMinute: Int = 100,
        registrationsPerMinute: Int = 100,
        var now: Long = 0L,
    ) {
        // C-13 동시성 시험이 두 스레드에서 기록하므로 Fixture 목록은 동기화한다(ArrayList 동시 추가 → 간헐 ArrayIndexOutOfBounds).
        val audits: MutableList<AuditRecord> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val commands: MutableList<MasterCommand> = Collections.synchronizedList(mutableListOf())
        val metrics: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val auditInTx: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        val callInTx: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        var failAuditAt: Int? = null
        var reply: (MasterCommand) -> MasterCommandResult = { MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "biz_01JC0000000000000000000009", false) }
        val views = listOf("adm_01JC00000000000000000000V1", "adm_01JC00000000000000000000V2").associateWith { AdminSessionView(it, "Operator", status, 1, 1, roles) }
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = 100
            override val registrationNumberSearchesPerMinute = brnPerMinute
            override val registrationsPerMinute = registrationsPerMinute
        }
        val budget = RegistrationNumberBudget(limits) { now }
        val service = MerchantRegistrationService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = views[actorId]
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : MasterOpsGateway {
                override fun search(criteria: MerchantSearchCriteria): MasterCallResult = error("not used")
                override fun detail(merchantId: String): MasterCallResult = error("not used")
                override fun history(merchantId: String, afterSeq: Long): MasterCallResult = error("not used")
                override fun command(command: MasterCommand): MasterCommandResult {
                    callInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    events += "call"
                    synchronized(commands) { commands += command }
                    return reply(command)
                }
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    auditInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    synchronized(audits) {
                        if (failAuditAt == audits.size + events.count { it == "audit-failed" }) {
                            events += "audit-failed"
                            throw IllegalStateException("injected audit failure")
                        }
                        audits += record
                        events += "audit:${record.resultCode}"
                    }
                }
            },
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) {
                    metrics += "rate:$kind"
                }
                override fun command(operation: String, result: String) {
                    metrics += "command:$operation:$result"
                }
                override fun commandUnknown(operation: String) {
                    metrics += "unknown:$operation"
                }
            },
            budget,
        )

        fun rows() = audits.map { listOf(it.action, it.resultCode, it.detail, it.targetRef, it.correlationId) }
    }

    fun business(s: String = sub(), number: String = brn, name: String = "시험 사업자") = RegistrationRequest.Business(s, number, name)
    fun merchant(s: String = sub(), businessId: String = biz) = RegistrationRequest.Merchant(s, businessId, "시험 가맹점")
    fun site(s: String = sub(), merchantId: String = mer, type: String = "WEB", identifier: String = "demo-shop2.paycore.test") =
        RegistrationRequest.MerchantSite(s, merchantId, type, identifier)

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { it.loggerName + " " + it.formattedMessage }
    }

    Given("roles (D-MOPS-006 ①: OPERATOR only)") {
        Then("VIEWER, APPROVER and SECURITY_ADMIN are 403 for all three kinds, audited FORBIDDEN with the ADMIN request id; MASTER is never called") {
            listOf("VIEWER", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                val s = sub()
                f.service.register(operatorId, business(s), ctx) shouldBe MerchantRegistrationResult.Forbidden
                f.service.register(operatorId, merchant(s), ctx) shouldBe MerchantRegistrationResult.Forbidden
                f.service.register(operatorId, site(s), ctx) shouldBe MerchantRegistrationResult.Forbidden
                withClue(role) {
                    f.rows() shouldBe listOf(
                        listOf("MERCHANT_BUSINESS_REGISTER", "FORBIDDEN", "sub=${s.take(8)};brn=778*******", null, "req-1"),
                        listOf("MERCHANT_REGISTER", "FORBIDDEN", "sub=${s.take(8)}", biz, "req-1"),
                        listOf("MERCHANT_SITE_REGISTER", "FORBIDDEN", "sub=${s.take(8)};channel=WEB", mer, "req-1"),
                    )
                    f.commands.shouldBeEmpty()
                }
            }
        }
        Then("M-1 brn matrix for 403: a well-formed number is masked, a malformed number is INVALID; a strict-parse failure is INVALID too (rejectMalformed)") {
            val f = Fixture(setOf("VIEWER"))
            f.service.register(operatorId, business(number = "778-12-34560"), ctx) shouldBe MerchantRegistrationResult.Forbidden
            f.service.register(operatorId, business(number = brn, name = " 공백"), ctx) shouldBe MerchantRegistrationResult.Forbidden
            f.service.rejectMalformed(operatorId, RegistrationKind.BUSINESS, registrationNumberAttempt = true, context = ctx) shouldBe MerchantRegistrationResult.Forbidden
            f.audits.map { it.detail!!.substringAfterLast(';') } shouldBe listOf("brn=INVALID", "brn=778*******", "brn=INVALID")
        }
        Then("an inactive OPERATOR is 403 and audited; an unknown account is 403 without an audit row") {
            val f = Fixture(setOf("OPERATOR"), status = "SUSPENDED")
            f.service.register(operatorId, merchant(), ctx) shouldBe MerchantRegistrationResult.Forbidden
            f.audits.map { it.resultCode } shouldBe listOf("FORBIDDEN")
            f.service.register("adm_01JC00000000000000000000ZZ", merchant(), ctx) shouldBe MerchantRegistrationResult.Forbidden
            f.audits.size shouldBe 1
        }
    }

    Given("strict-parse failures (§4.4 0)") {
        Then("a business registration-number attempt by an OPERATOR is INVALID_INPUT audited brn=INVALID; non-attempts and other kinds are 400 without audit") {
            val f = Fixture()
            f.service.rejectMalformed(operatorId, RegistrationKind.BUSINESS, true, ctx) shouldBe MerchantRegistrationResult.InvalidInput
            f.service.rejectMalformed(operatorId, RegistrationKind.BUSINESS, false, ctx) shouldBe MerchantRegistrationResult.InvalidInput
            f.service.rejectMalformed(operatorId, RegistrationKind.MERCHANT, true, ctx) shouldBe MerchantRegistrationResult.InvalidInput
            f.service.rejectMalformed(operatorId, RegistrationKind.MERCHANT_SITE, false, ctx) shouldBe MerchantRegistrationResult.InvalidInput
            f.rows() shouldBe listOf(listOf("MERCHANT_BUSINESS_REGISTER", "INVALID_INPUT", "brn=INVALID", null, "req-1"))
            f.commands.shouldBeEmpty()
        }
    }

    Given("value formats (C-10 — same rules as MASTER Domain, MASTER not called)") {
        Then("business: malformed numbers audit brn=INVALID, a bad name with a good number audits the masked number; merchant/site format errors are not audited") {
            val f = Fixture()
            listOf("778-12-34560", "778123456", "77812345601", "778123456O", "７７８1234560").forEach { number ->
                withClue(number) { f.service.register(operatorId, business(number = number), ctx) shouldBe MerchantRegistrationResult.InvalidInput }
            }
            listOf(" 시험", "시험 ", "", "a".repeat(101), "줄\n바꿈").forEach { name ->
                withClue(name) { f.service.register(operatorId, business(name = name), ctx) shouldBe MerchantRegistrationResult.InvalidInput }
            }
            f.service.register(operatorId, business(s = "NOT-A-SUBMISSION-ID"), ctx) shouldBe MerchantRegistrationResult.InvalidInput
            f.audits.map { it.resultCode }.toSet() shouldBe setOf("INVALID_INPUT")
            f.audits.take(5).map { it.detail!!.substringAfterLast(';') } shouldBe List(5) { "brn=INVALID" }
            f.audits.drop(5).take(5).map { it.detail!!.substringAfterLast(';') } shouldBe List(5) { "brn=778*******" }
            f.audits.last().detail shouldBe "brn=778*******"
            val before = f.audits.size
            listOf(merchant(businessId = "biz_1"), merchant(businessId = mer), merchant(s = "ABC")).forEach { f.service.register(operatorId, it, ctx) shouldBe MerchantRegistrationResult.InvalidInput }
            listOf(
                site(identifier = "Shop.Example.test"), site(identifier = "10.0.0.1"), site(identifier = "shop.example.test:443"), site(identifier = "shop"),
                site(type = "web"), site(type = "APP", identifier = "Upper.App"), site(merchantId = biz),
            ).forEach { withClue(it.channelIdentifier) { f.service.register(operatorId, it, ctx) shouldBe MerchantRegistrationResult.InvalidInput } }
            f.audits.size shouldBe before
            f.commands.shouldBeEmpty()
            f.service.register(operatorId, site(type = "APP", identifier = "test.paycore.app"), ctx).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
        }
    }

    Given("a registration that reaches MASTER") {
        Then("attempt row REQUESTED → MASTER call (outside a transaction) → result row OK; both rows carry mos.<submissionId>; the MASTER key is mo1.<account ULID>.<submissionId>") {
            val f = Fixture()
            val s = sub()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "biz_01JC0000000000000000000009", false) }
            f.service.register(operatorId, business(s), ctx) shouldBe MerchantRegistrationResult.Decided("SUCCEEDED", "biz_01JC0000000000000000000009", null, false)
            f.events shouldBe listOf("audit:REQUESTED", "call", "audit:OK")
            f.callInTx shouldBe listOf(false)
            f.auditInTx shouldBe listOf(true, true)
            f.rows() shouldBe listOf(
                listOf("MERCHANT_BUSINESS_REGISTER", "REQUESTED", "sub=${s.take(8)};brn=778*******", null, "mos.$s"),
                listOf("MERCHANT_BUSINESS_REGISTER", "OK", "sub=${s.take(8)};replayed=false;brn=778*******", "biz_01JC0000000000000000000009", "mos.$s"),
            )
            val c = f.commands.single()
            listOf(c.idempotencyKey, c.actor, c.correlationId) shouldBe listOf("mo1.01JC00000000000000000000V1.$s", "admin:$operatorId", "mos.$s")
            c.idempotencyKey.length shouldBe 63
            c.fields shouldBe mapOf("registrationNumber" to brn, "displayName" to "시험 사업자")
            f.audits.forEach { it.remoteIp shouldBe "127.0.0.1" }
            f.metrics shouldBe listOf("command:BUSINESS:OK")
        }
        Then("C-4: two accounts sending the same submissionId get different MASTER keys (no replay of another operator's result); a resend keeps key, correlation id and fields") {
            val f = Fixture()
            val s = sub()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "mer_01JC0000000000000000000009", false) }
            f.service.register(operatorId, merchant(s), ctx)
            f.service.register(otherOperatorId, merchant(s), ctx)
            f.service.register(operatorId, merchant(s), AdminRequestContext("req-2", "127.0.0.1"))
            val (a, b, again) = f.commands
            a.idempotencyKey shouldNotBe b.idempotencyKey
            listOf(again.idempotencyKey, again.correlationId, again.fields) shouldBe listOf(a.idempotencyKey, a.correlationId, a.fields)
            f.audits.filter { it.resultCode == "OK" }.map { it.targetRef } shouldBe List(3) { "mer_01JC0000000000000000000009" }
        }
        Then("result classes: REJECTED (no reference), KEY_REUSED, BUSY and OUTCOME_UNKNOWN (Unknown and RetrySameKey) are audited; unknown outcomes are counted and logged without the MASTER key") {
            val cases = listOf(
                MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "MERCHANT_NOT_FOUND", null, true) to
                    (MerchantRegistrationResult.Decided("REJECTED", null, "MERCHANT_NOT_FOUND", true) to "REJECTED"),
                MasterCommandResult.KeyReused to (MerchantRegistrationResult.KeyReused to "KEY_REUSED"),
                MasterCommandResult.Busy("ADMIN_WRITE_BUSY") to (MerchantRegistrationResult.MasterBusy to "BUSY"),
                MasterCommandResult.RetrySameKey("REQUEST_IN_PROGRESS") to (MerchantRegistrationResult.RetrySameKey to "OUTCOME_UNKNOWN"),
                MasterCommandResult.Unknown("TIMEOUT") to (MerchantRegistrationResult.OutcomeUnknown to "OUTCOME_UNKNOWN"),
            )
            cases.forEach { (reply, expected) ->
                val f = Fixture()
                f.reply = { reply }
                val s = sub()
                lateinit var result: MerchantRegistrationResult
                val logs = captureLogs { result = f.service.register(operatorId, site(s), ctx) }
                withClue(reply) {
                    result shouldBe expected.first
                    f.rows().last() shouldBe listOf(
                        "MERCHANT_SITE_REGISTER", expected.second,
                        listOfNotNull("sub=${s.take(8)}", (reply as? MasterCommandResult.Decided)?.let { "replayed=true;rejection=MERCHANT_NOT_FOUND" }, "channel=WEB").joinToString(";"),
                        mer, "mos.$s",
                    )
                    (f.metrics.contains("unknown:MERCHANT_SITE")) shouldBe (expected.second == "OUTCOME_UNKNOWN")
                    if (expected.second == "OUTCOME_UNKNOWN") logs.count { "result=OUTCOME_UNKNOWN" in it && "correlationId=mos.$s" in it } shouldBe 1
                    logs.filter { f.commands.single().idempotencyKey in it || "demo-shop2.paycore.test" in it }.shouldBeEmpty()
                }
            }
        }
        Then("§4.4 4: an attempt-row audit failure is 503 AUDIT_UNAVAILABLE and MASTER is never called; a result-row failure is AUDIT_UNAVAILABLE after the call") {
            val f = Fixture()
            f.failAuditAt = 0
            f.service.register(operatorId, merchant(), ctx) shouldBe MerchantRegistrationResult.AuditUnavailable
            f.commands.shouldBeEmpty()
            val g = Fixture()
            g.failAuditAt = 1
            g.service.register(operatorId, merchant(), ctx) shouldBe MerchantRegistrationResult.AuditUnavailable
            g.commands.size shouldBe 1
            g.rows().map { it[1] } shouldBe listOf("REQUESTED")
        }
        Then("non-exposure: no audit field carries the MASTER key, the plain number or the display name") {
            val f = Fixture()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", null, false) }
            f.service.register(operatorId, business(), ctx) shouldBe MerchantRegistrationResult.Decided("REJECTED", null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false)
            val key = f.commands.single().idempotencyKey
            f.audits.forEach { a ->
                listOf(a.detail, a.targetRef, a.correlationId, a.toString()).forEach { v ->
                    listOf(key, brn, "4560", "시험 사업자").forEach { secret -> (v?.contains(secret) ?: false) shouldBe false }
                }
            }
            f.audits.last().targetRef shouldBe null
        }
    }

    Given("B1-2 contract draft and service agreement (§4.3·§4.4·§8.1 B1-2-4)") {
        val ctr = "ctr_01JC0000000000000000000001"
        val site1 = "site_01JC0000000000000000000001"
        val site2 = "site_01JC0000000000000000000002"
        fun contract(
            s: String = sub(),
            party: String = biz,
            scope: String = mer,
            sites: List<String> = emptyList(),
            from: String = "2026-11-09",
            to: String? = null,
        ) = RegistrationRequest.Contract(s, party, scope, sites, from, to)
        fun agreement(s: String = sub(), contractId: String = ctr, kind: String = "CARD_PG_PAYMENT") = RegistrationRequest.ServiceAgreement(s, contractId, kind)

        Then("roles: VIEWER, APPROVER and SECURITY_ADMIN are 403 audited FORBIDDEN (targetRef = the entered parent id); MASTER is not called") {
            listOf("VIEWER", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                val s = sub()
                f.service.register(operatorId, contract(s), ctx) shouldBe MerchantRegistrationResult.Forbidden
                f.service.register(operatorId, agreement(s), ctx) shouldBe MerchantRegistrationResult.Forbidden
                withClue(role) {
                    f.rows() shouldBe listOf(
                        listOf("MERCHANT_CONTRACT_REGISTER", "FORBIDDEN", "sub=${s.take(8)}", mer, "req-1"),
                        listOf("MERCHANT_AGREEMENT_ADD", "FORBIDDEN", "sub=${s.take(8)}", ctr, "req-1"),
                    )
                    f.commands.shouldBeEmpty()
                }
            }
        }

        Then("C-7: KST dates become the UTC instant of that date's 00:00 KST (2026-11-09 → 2026-11-08T15:00:00.000Z); the end is the exclusive 00:00 of its date; no end sends no effectiveTo") {
            val f = Fixture()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "ctr_01JC0000000000000000000009", false) }
            val s = sub()
            f.service.register(operatorId, contract(s, sites = listOf(site2, site1), from = "2026-11-09", to = "2027-01-01"), ctx) shouldBe
                MerchantRegistrationResult.Decided("SUCCEEDED", "ctr_01JC0000000000000000000009", null, false)
            val c = f.commands.single()
            c.kind shouldBe MasterCommandKind.REGISTER_CONTRACT
            listOf(c.idempotencyKey, c.actor, c.correlationId) shouldBe listOf("mo1.01JC00000000000000000000V1.$s", "admin:$operatorId", "mos.$s")
            c.fields shouldBe mapOf(
                "partyBusinessId" to biz, "scopeMerchantId" to mer, "scopeSiteIds" to listOf(site2, site1),
                "effectiveFrom" to "2026-11-08T15:00:00.000Z", "effectiveTo" to "2026-12-31T15:00:00.000Z",
            )
            f.service.register(operatorId, contract(from = "2026-11-09"), ctx)
            f.commands.last().fields shouldBe mapOf("partyBusinessId" to biz, "scopeMerchantId" to mer, "scopeSiteIds" to emptyList<String>(), "effectiveFrom" to "2026-11-08T15:00:00.000Z")
            // 감사 2행(시도·결과), targetRef = 시도는 입력한 가맹점, 결과는 만든 계약 ID(§8.1 B1-2-4).
            f.rows().take(2) shouldBe listOf(
                listOf("MERCHANT_CONTRACT_REGISTER", "REQUESTED", "sub=${s.take(8)}", mer, "mos.$s"),
                listOf("MERCHANT_CONTRACT_REGISTER", "OK", "sub=${s.take(8)};replayed=false", "ctr_01JC0000000000000000000009", "mos.$s"),
            )
            f.metrics.take(1) shouldBe listOf("command:CONTRACT:OK")
        }

        Then("the service agreement: fields, attempt and result rows with targetRef = contract then the created csa_ id; a duplicate kind is REJECTED without a reference") {
            val f = Fixture()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "csa_01JC0000000000000000000009", false) }
            val s = sub()
            f.service.register(operatorId, agreement(s), ctx) shouldBe MerchantRegistrationResult.Decided("SUCCEEDED", "csa_01JC0000000000000000000009", null, false)
            f.commands.single().let { listOf(it.kind, it.fields) } shouldBe listOf(MasterCommandKind.ADD_SERVICE_AGREEMENT, mapOf("contractId" to ctr, "serviceKind" to "CARD_PG_PAYMENT"))
            f.rows() shouldBe listOf(
                listOf("MERCHANT_AGREEMENT_ADD", "REQUESTED", "sub=${s.take(8)}", ctr, "mos.$s"),
                listOf("MERCHANT_AGREEMENT_ADD", "OK", "sub=${s.take(8)};replayed=false", "csa_01JC0000000000000000000009", "mos.$s"),
            )
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "SERVICE_KIND_DUPLICATE", null, false) }
            val d = sub()
            f.service.register(operatorId, agreement(d), ctx) shouldBe MerchantRegistrationResult.Decided("REJECTED", null, "SERVICE_KIND_DUPLICATE", false)
            f.rows().last() shouldBe listOf("MERCHANT_AGREEMENT_ADD", "REJECTED", "sub=${d.take(8)};replayed=false;rejection=SERVICE_KIND_DUPLICATE", ctr, "mos.$d")
        }

        Then("§3.4: ADMIN does not judge a past start — yesterday and long ago are sent to MASTER, whose CONTRACT_START_RETROACTIVE is passed through and audited REJECTED") {
            val f = Fixture()
            f.reply = { MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "CONTRACT_START_RETROACTIVE", null, false) }
            val s = sub()
            f.service.register(operatorId, contract(s, from = "2020-01-01"), ctx) shouldBe MerchantRegistrationResult.Decided("REJECTED", null, "CONTRACT_START_RETROACTIVE", false)
            f.commands.single().fields["effectiveFrom"] shouldBe "2019-12-31T15:00:00.000Z"
            f.rows().last() shouldBe listOf("MERCHANT_CONTRACT_REGISTER", "REJECTED", "sub=${s.take(8)};replayed=false;rejection=CONTRACT_START_RETROACTIVE", mer, "mos.$s")
        }

        Then("C-10 formats: bad dates, an end not after the start, 51 or duplicate or malformed site ids, wrong id kinds and unknown service kinds are 400 without audit or MASTER call; 50 sites pass") {
            val f = Fixture()
            listOf(
                contract(from = "2026-11-9"), contract(from = "2026/11/09"), contract(from = "2026-02-30"), contract(from = "2026-11-09T00:00"), contract(from = ""),
                contract(from = "+2026-11-09"), contract(to = "2026-11-09"), contract(to = "2026-11-08"), contract(to = "2026-13-01"),
                contract(sites = (1..51).map { "site_01JC" + it.toString().padStart(22, '0') }), contract(sites = listOf(site1, site1)), contract(sites = listOf("site_1")),
                contract(party = mer), contract(scope = biz), contract(s = "NOT-A-SUBMISSION-ID"),
                agreement(contractId = "ctr_1"), agreement(contractId = mer), agreement(kind = "card_pg_payment"), agreement(kind = "BANK_TRANSFER"),
            ).forEach { request -> withClue(request) { f.service.register(operatorId, request, ctx) shouldBe MerchantRegistrationResult.InvalidInput } }
            f.audits.shouldBeEmpty()
            f.commands.shouldBeEmpty()
            f.metrics.toSet() shouldBe setOf("command:CONTRACT:INVALID_INPUT", "command:SERVICE_AGREEMENT:INVALID_INPUT")
            f.service.register(operatorId, contract(sites = (1..50).map { "site_01JC" + it.toString().padStart(22, '0') }), ctx).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
        }

        Then("C-10 boundary (review m-2): a date whose 00:00 KST falls outside MASTER's canonical 4-digit UTC year (0000-01-01 → -0001-12-31T15:00Z) is 400 before MASTER; 0000-01-02 and 9999-12-31 are sent") {
            val f = Fixture()
            listOf(
                contract(from = "0000-01-01"), contract(from = "0000-01-01", to = "0000-01-03"),
            ).forEach { request -> withClue(request) { f.service.register(operatorId, request, ctx) shouldBe MerchantRegistrationResult.InvalidInput } }
            f.audits.shouldBeEmpty()
            f.commands.shouldBeEmpty()
            f.metrics.toSet() shouldBe setOf("command:CONTRACT:INVALID_INPUT")

            f.service.register(operatorId, contract(from = "0000-01-02", to = "9999-12-31"), ctx).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
            // 0000-01-02는 Asia/Seoul 역사 규칙(LMT +08:27:52)이 적용돼 15:32:08Z가 된다 — 여기서는 MASTER 정규형(4자리 연도 ms 표기)으로 보내지는지만 본다.
            val sent = f.commands.single().fields
            (sent["effectiveFrom"] as String) shouldMatch Regex("^0000-01-01T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
            sent["effectiveTo"] shouldBe "9999-12-30T15:00:00.000Z"
        }

        Then("the registration-number budget is not touched by contracts or agreements (only business registration reserves it — C-13)") {
            val f = Fixture(brnPerMinute = 1)
            f.reply = { MasterCommandResult.Unknown("TIMEOUT") }
            f.service.register(operatorId, contract(), ctx) shouldBe MerchantRegistrationResult.OutcomeUnknown
            f.service.register(operatorId, agreement(), ctx) shouldBe MerchantRegistrationResult.OutcomeUnknown
            f.budget.admit(operatorId) shouldBe true
            f.metrics.filter { it.startsWith("unknown:") } shouldBe listOf("unknown:CONTRACT", "unknown:SERVICE_AGREEMENT")
        }
    }

    Given("rate limits (§4.4 3)") {
        Then("the registration bucket (per actor per minute) answers 429 audited RATE_LIMITED and is counted as REGISTRATION") {
            val f = Fixture(registrationsPerMinute = 1)
            f.service.register(operatorId, merchant(), ctx).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
            f.service.register(operatorId, merchant(), ctx) shouldBe MerchantRegistrationResult.RateLimited
            f.service.register(otherOperatorId, merchant(), ctx).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
            f.rows().map { it[1] } shouldBe listOf("REQUESTED", "OK", "RATE_LIMITED", "REQUESTED", "OK")
            f.metrics.count { it == "rate:REGISTRATION" } shouldBe 1
            f.commands.size shouldBe 2
        }
        Then("C-13: successes, other rejections and 409 refund the number reservation; a duplicate, Unknown, RetrySameKey and Busy confirm it; an exhausted bucket is 429 before MASTER") {
            val f = Fixture(brnPerMinute = 2)
            val refunds = listOf<MasterCommandResult>(
                MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "biz_01JC0000000000000000000009", false),
                MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "BUSINESS_NOT_ACTIVE", null, false),
                MasterCommandResult.KeyReused,
            )
            repeat(3) { round -> refunds.forEach { r -> f.reply = { r }; withClue("$round $r") { (f.service.register(operatorId, business(), ctx) == MerchantRegistrationResult.RateLimited) shouldBe false } } }
            f.budget.admit(operatorId) shouldBe true
            f.budget.admit(operatorId) shouldBe true
            f.budget.admit(operatorId) shouldBe false
            listOf<MasterCommandResult>(
                MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", null, false),
                MasterCommandResult.Unknown("TIMEOUT"),
                MasterCommandResult.RetrySameKey("OUTCOME_UNKNOWN"),
                MasterCommandResult.Busy("ADMIN_WRITE_BUSY"),
            ).forEach { r ->
                val g = Fixture(brnPerMinute = 1)
                g.reply = { r }
                g.service.register(operatorId, business(), ctx)
                withClue(r) {
                    g.service.register(operatorId, business(), ctx) shouldBe MerchantRegistrationResult.RateLimited
                    g.rows().map { it[1] }.last() shouldBe "RATE_LIMITED"
                    g.metrics.count { it == "rate:REGISTRATION_NUMBER" } shouldBe 1
                    g.commands.size shouldBe 1
                    // 같은 버킷을 조회(등록번호 검색)도 쓴다 — 확정된 1회 뒤 검색도 한도에 걸린다.
                    g.budget.admit(operatorId) shouldBe false
                }
            }
        }
        Then("an attempt-audit failure refunds the reservation; a refund never goes below zero and is skipped after the window changed") {
            val f = Fixture(brnPerMinute = 1)
            f.failAuditAt = 0
            f.service.register(operatorId, business(), ctx) shouldBe MerchantRegistrationResult.AuditUnavailable
            f.failAuditAt = null
            f.reply = { MasterCommandResult.Unknown("TIMEOUT") }
            f.service.register(operatorId, business(), ctx) shouldBe MerchantRegistrationResult.OutcomeUnknown
            f.service.register(operatorId, business(), ctx) shouldBe MerchantRegistrationResult.RateLimited

            val g = Fixture(brnPerMinute = 1, now = 59_000L)
            val sync = CountDownLatch(1)
            val release = CountDownLatch(1)
            g.reply = {
                sync.countDown()
                release.await(5, TimeUnit.SECONDS)
                MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "biz_01JC0000000000000000000009", false)
            }
            val pool = Executors.newSingleThreadExecutor()
            val pending = pool.submit<MerchantRegistrationResult> { g.service.register(operatorId, business(), ctx) }
            sync.await(5, TimeUnit.SECONDS) shouldBe true
            g.now = 61_000L // 다음 창 — 환불하지 않는다(0 아래로도 내려가지 않는다).
            g.budget.admit(operatorId) shouldBe true
            release.countDown()
            pending.get(5, TimeUnit.SECONDS).let { it::class shouldBe MerchantRegistrationResult.Decided::class }
            pool.shutdown()
            g.budget.admit(operatorId) shouldBe false
        }
        Then("C-13 concurrency: two simultaneous business registrations by one actor cannot both use the last remaining number reservation") {
            repeat(5) { round ->
                val f = Fixture(brnPerMinute = 1)
                val barrier = CyclicBarrier(2)
                f.reply = { Thread.sleep(50); MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", null, false) }
                val pool = Executors.newFixedThreadPool(2)
                val results = (1..2).map { pool.submit<MerchantRegistrationResult> { barrier.await(5, TimeUnit.SECONDS); f.service.register(operatorId, business(), ctx) } }.map { it.get(10, TimeUnit.SECONDS) }
                pool.shutdown()
                withClue("round $round") {
                    results.map { it::class.simpleName } shouldContainExactlyInAnyOrder listOf("Decided", "RateLimited")
                    f.commands.size shouldBe 1
                }
            }
        }
    }
})
