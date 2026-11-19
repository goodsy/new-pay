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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-NAME-SEARCH-001 MNS-2 ADMIN 가맹점명 조건(MASTER는 MNS-1에서 지원): 화면 검색 본문 parser의 `displayNamePrefix`(JSON 문자열만 — 값 형식은 Use Case),
 * Use Case 형식 검사(MASTER `DisplayName.errorOf`와 같은 규칙 — 정규화 없음, 위반이면 MASTER 호출 전 400·감사 없음), 코드 검색과 함께면 400(단일 기준),
 * 등록번호와 함께면 기존 등록번호 감사 경로(이름 위반도 `INVALID_INPUT` 감사 — 마스킹 번호만), 목록 검색과 같이 감사 없음(D-MOPS-002)·기존 조회 속도 창,
 * 조건 toString·로그에 이름 원문 없음(개인사업자 상호에 사람 이름이 있을 수 있다 — BUSINESS-PROFILE-001 L-3 취지).
 */
class MerchantNameSearchSpec : BehaviorSpec({

    val ctx = AdminRequestContext("corr-mns2", "127.0.0.1")

    /** 로그 canary — 사람 이름처럼 보이는 상호. */
    val canary = "김캐너리상회"

    class Fixture(roles: Set<String> = setOf("VIEWER"), perMinute: Int = 100) {
        val searched = mutableListOf<MerchantSearchCriteria>()
        val audits = mutableListOf<AuditRecord>()
        val view = AdminSessionView("adm_01JC00000000000000000000N2", "Viewer", "ACTIVE", 1, 1, roles)
        private val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = perMinute
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

    /** 루트 로거 캡처(로거 이름·형식화된 메시지·예외 사슬 메시지 — MasterOpsGatewaySpec과 같은 방식). */
    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e ->
            buildString {
                append(e.loggerName).append(' ').append(e.formattedMessage)
                var t = e.throwableProxy
                while (t != null) {
                    append(" | ").append(t.className).append(": ").append(t.message)
                    t = t.cause
                }
            }
        }
    }

    Given("MNS-2 the screen search body parser") {
        Then("MNS-2 displayNamePrefix is a known text field passed through unchanged (format is the use case's job), alone or with the other fields") {
            (MerchantSearchBodyParser.parse("""{"displayNamePrefix":"$canary","limit":5}""".toByteArray()) as MerchantSearchBodyParser.Parsed.Criteria).criteria shouldBe
                MerchantSearchCriteria(displayNamePrefix = canary, limit = 5)
            listOf("%_!", " Demo ", "demo", "😀").forEach { name ->
                withClue(name) {
                    val body = """{"lifecycle":"ACTIVE","displayNamePrefix":"$name"}"""
                    (MerchantSearchBodyParser.parse(body.toByteArray()) as MerchantSearchBodyParser.Parsed.Criteria).criteria shouldBe
                        MerchantSearchCriteria(lifecycle = "ACTIVE", displayNamePrefix = name)
                }
            }
        }
        Then("MNS-2 a non-string displayNamePrefix (number, null, array, object) is malformed and not a registration-number attempt; the attempt scanner is unchanged") {
            listOf("""{"displayNamePrefix":12345}""", """{"displayNamePrefix":null}""", """{"displayNamePrefix":["Demo"]}""", """{"displayNamePrefix":{"v":"Demo"}}""").forEach { body ->
                withClue(body) { MerchantSearchBodyParser.parse(body.toByteArray()) shouldBe MerchantSearchBodyParser.Parsed.Malformed(false) }
            }
            MerchantSearchBodyParser.parse("""{"registrationNumber":"1234567890","displayNamePrefix":1}""".toByteArray()) shouldBe MerchantSearchBodyParser.Parsed.Malformed(true)
        }
    }

    Given("MNS-2 the name search use case") {
        Then("MNS-2 a well-formed name (alone or ANDed with the other filters) reaches MASTER unchanged and is not audited (D-MOPS-002)") {
            val f = Fixture()
            val names = listOf(canary, "%_!", "Demo", "a".repeat(100), "😀".repeat(100), "가 나")
            names.forEach { name ->
                withClue(name) { f.service.search(f.view.actorId, MerchantSearchCriteria(displayNamePrefix = name), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>() }
            }
            val combined = MerchantSearchCriteria(
                merchantId = "mer_01JC0000000000000000000001", businessId = "biz_01JC0000000000000000000001", lifecycle = "ACTIVE",
                after = "mer_01JC0000000000000000000001", limit = 10, displayNamePrefix = "Demo",
            )
            f.service.search(f.view.actorId, combined, ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.searched.map { it.displayNamePrefix } shouldBe names + "Demo"
            f.searched.last() shouldBe combined
            f.audits.shouldBeEmpty()
        }
        Then("MNS-2 DisplayName format violations are 400 before MASTER, without an audit row (no normalisation)") {
            val f = Fixture()
            listOf("", " Demo", "Demo ", " ", "De\nmo", "De\tmo", "\u0000", "\u007f", "a".repeat(101), "😀".repeat(101), "\uD83D", "a\uDE00", "\uDE00\uD83D").forEach { name ->
                withClue(name.map { it.code }) { f.service.search(f.view.actorId, MerchantSearchCriteria(displayNamePrefix = name), ctx) shouldBe MerchantLookupResult.InvalidInput }
            }
            f.service.search(f.view.actorId, MerchantSearchCriteria(lifecycle = "ACTIVE", displayNamePrefix = "Demo "), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.searched.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }
        Then("MNS-2 with merchantCode it is 400 before MASTER (code search is a single criterion — MERCHANT-CODE-001 §3.8), not audited") {
            val f = Fixture()
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "shopabcd12", displayNamePrefix = "Demo"), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.service.search(f.view.actorId, MerchantSearchCriteria(merchantCode = "shopabcd12", limit = 1, displayNamePrefix = "Demo"), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.searched.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }
        Then("MNS-2 with a registration number it follows the audited number path: a good name is OK (masked number only), a bad name is INVALID_INPUT before MASTER") {
            val f = Fixture(setOf("OPERATOR"))
            f.service.search(f.view.actorId, MerchantSearchCriteria(registrationNumber = "1234567890", displayNamePrefix = canary), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.service.search(f.view.actorId, MerchantSearchCriteria(registrationNumber = "1234567890", displayNamePrefix = " $canary"), ctx) shouldBe MerchantLookupResult.InvalidInput
            f.audits.map { listOf(it.action, it.resultCode, it.detail) } shouldBe listOf(
                listOf("MERCHANT_BRN_SEARCHED", "OK", "brn=123*******;results=0"),
                listOf("MERCHANT_BRN_SEARCHED", "INVALID_INPUT", "brn=123*******"),
            )
            f.searched.map { it.displayNamePrefix } shouldBe listOf(canary)
            f.audits.forEach { a -> listOf(a.detail, a.targetRef, a.targetMerchantId).forEach { (it ?: "") shouldNotContain canary } }
        }
        Then("MNS-2 a name-only search uses the ordinary lookup window (no audit when limited)") {
            val f = Fixture(perMinute = 2)
            repeat(2) { f.service.search(f.view.actorId, MerchantSearchCriteria(displayNamePrefix = "Demo"), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>() }
            f.service.search(f.view.actorId, MerchantSearchCriteria(displayNamePrefix = "Demo"), ctx) shouldBe MerchantLookupResult.RateLimited
            f.searched.size shouldBe 2
            f.audits.shouldBeEmpty()
        }
    }

    Given("MNS-2 non-exposure of the searched name") {
        Then("MNS-2 toString hides the name (*** when present, null when absent)") {
            val text = MerchantSearchCriteria(lifecycle = "ACTIVE", displayNamePrefix = canary).toString()
            text shouldNotContain canary
            text shouldContain "displayNamePrefix=***"
            text shouldContain "lifecycle=ACTIVE"
            MerchantSearchCriteria().toString() shouldContain "displayNamePrefix=null"
        }
        Then("MNS-2 no log line carries the name — accepted, rejected, number path and the real HTTP gateway call (control: operation lines are captured)") {
            StubMasterOpsServer.start()
            StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY))
            val settings = MasterOpsSettings(
                "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(2), 2, 30, 10, 2, 20, 2,
                Duration.ofMillis(100), 60,
            )
            val gateway = HttpMasterOpsGateway(settings, MasterCallSigner(settings.keyId, settings.signingKey())) { Instant.now() }
            val viewer = Fixture()
            val operator = Fixture(setOf("OPERATOR"))
            val lines = captureLogs {
                viewer.service.search(viewer.view.actorId, MerchantSearchCriteria(displayNamePrefix = canary), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
                viewer.service.search(viewer.view.actorId, MerchantSearchCriteria(displayNamePrefix = "$canary "), ctx) shouldBe MerchantLookupResult.InvalidInput
                viewer.service.search(viewer.view.actorId, MerchantSearchCriteria(merchantCode = "shopabcd12", displayNamePrefix = canary), ctx) shouldBe MerchantLookupResult.InvalidInput
                operator.service.search(operator.view.actorId, MerchantSearchCriteria(registrationNumber = "1234567890", displayNamePrefix = canary), ctx)
                    .shouldBeInstanceOf<MerchantLookupResult.Found>()
                gateway.search(MerchantSearchCriteria(displayNamePrefix = canary)).shouldBeInstanceOf<MasterCallResult.Found>()
            }
            lines.count { "operation=merchant-search" in it } shouldBe 1
            lines.count { "operation=merchant-brn-search" in it } shouldBe 1
            lines.count { "operation=master-ops-search" in it } shouldBe 1
            lines.filter { canary in it }.shouldBeEmpty()
        }
    }
})
