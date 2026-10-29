package com.paycore.admin.paymentops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.approval.OwnerApprovalFixture
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsConfiguration
import com.paycore.admin.paymentops.application.required.PaymentOpsGateway
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64

/**
 * 비노출(ADMIN-PAYMENT-OPS-001 §6 — `PaymentOpsNonDisclosureSpec`). canary 값(서명 키·사유·근거·카드형 숫자)이 다음 어디에도 나오지 않는다:
 * - 요청·승인 전 과정의 모든 로그 이벤트(메시지·예외 스택 — root logger에 붙인 ListAppender), 결과 값 toString.
 * - 설정 검사 실패·기동 실패 출력(실제 `SpringApplication` 기동 — 출력에 "APPLICATION FAILED TO START" 또는 "Application run failed"가 **있는지** 먼저 확인해
 *   출력 수집이 비어 공허하게 통과하지 않게 한다).
 * `PaymentOpsSettingsBootSpec`(L-6 설정 순서의 Spring 기동 시험)도 여기서: 순서 위반·context path는 기동 실패, 정상 설정은 기동해 gateway bean이 생긴다.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class PaymentOpsNonDisclosureSpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    recovery: com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase,
    justifications: com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery,
    findingQuery: com.paycore.admin.approval.application.provided.ReconciliationFindingQuery,
    container: org.testcontainers.mysql.MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    val reasonCanary = "CANARYREASON7731 boxed"
    val referenceCanary = "CANARYREF-7731"
    val cardCanary = "4111 2222 3333 4444"
    val keyCanary = Base64.getEncoder().encodeToString("CANARY-SIGNING-KEY-7731-0123456789abcdef".toByteArray())

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e -> e.formattedMessage + (e.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "") }
    }

    fun boot(vararg args: String): Pair<Throwable?, String> {
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val capture = PrintStream(out, true, Charsets.UTF_8)
        System.setOut(capture)
        System.setErr(capture)
        val failure = try {
            SpringApplicationBuilder(PaymentOpsConfiguration::class.java).web(WebApplicationType.NONE).logStartupInfo(false).run(*args).use { ctx ->
                ctx.getBean(PaymentOpsGateway::class.java)
            }
            null
        } catch (e: Throwable) {
            e
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        val text = out.toString(Charsets.UTF_8) + (failure?.let { generateSequence(it) { t -> t.cause }.joinToString("\n") { t -> t.toString() } } ?: "")
        return failure to text
    }

    fun props(window: String = "24h", contextPath: String? = null, key: String = keyCanary) = listOfNotNull(
        "--paycore.admin.payment-ops.base-url=http://127.0.0.1:${StubPaymentOpsServer.PORT}", "--paycore.admin.payment-ops.key-id=adm-canary",
        "--paycore.admin.payment-ops.signing-key=$key", "--paycore.admin.payment-ops.execution-window=$window",
        contextPath?.let { "--server.servlet.context-path=$it" }, "--spring.main.banner-mode=off",
    ).toTypedArray()

    Given("a request and an approval carrying canary reason, reference and an error response") {
        Then("no log event (message or stack) and no outcome text contains the reason, the reference, the card-like number or the signing key") {
            val logs = captureLogs {
                val operator = fixture.operator()
                val paymentId = fixture.newPaymentId()
                StubPaymentOpsServer.targetFound(paymentId)
                fixture.request(operator, paymentId, reason = "card $cardCanary", reference = referenceCanary) shouldBe
                    ApprovalOutcome.Rejected("INVALID_INPUT", "reason", "CARD_NUMBER_PATTERN")
                val created = fixture.request(operator, paymentId, reason = reasonCanary, reference = referenceCanary) as ApprovalOutcome.Created
                StubPaymentOpsServer.execution(created.approvalId, StubPaymentOpsServer.Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}"))
                val outcome = fixture.approve(created.approvalId)
                outcome.toString() shouldNotContain reasonCanary
            }
            (logs.size > 0) shouldBe true
            logs.filter { line -> listOf(reasonCanary, referenceCanary, cardCanary, "CANARYREASON", "4111").any { line.contains(it) } } shouldBe emptyList()
        }
    }

    Given("S9 canary bundle — the S6–S8 paths (recovery job, refused justification lookup, findings overview, audit and finding rows)") {
        Then("no log line, result text, audit row or finding row carries the reason, the reference or the card-like canary") {
            val root = { com.paycore.admin.support.AdminDbAccounts.root(container) }
            lateinit var approvalId: String
            val texts = mutableListOf<String>()
            val logs = captureLogs {
                val operator = fixture.operator()
                val paymentId = fixture.newPaymentId()
                StubPaymentOpsServer.targetFound(paymentId)
                approvalId = (fixture.request(operator, paymentId, reason = reasonCanary, reference = referenceCanary) as ApprovalOutcome.Created).approvalId
                StubPaymentOpsServer.execution(approvalId, StubPaymentOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}"))
                texts += fixture.approve(approvalId).toString()
                root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", approvalId) }
                recovery.recoverDue()
                val stranger = fixture.operator()
                texts += justifications.view(stranger.id.value, approvalId, com.paycore.admin.account.application.provided.AdminRequestContext("corr-canary", null)).toString()
                texts += findingQuery.overview(storage.securityAdmin().id.value).toString()
            }
            (logs.size > 0) shouldBe true
            val rows = root().use { c ->
                c.column("SELECT CONCAT_WS('|', action, result_code, COALESCE(detail, ''), COALESCE(reason, ''), COALESCE(target_ref, '')) FROM admin_audit_event WHERE approval_id = ?", approvalId) +
                    c.column("SELECT CONCAT_WS('|', kind, COALESCE(detail_code, ''), COALESCE(payment_ref, '')) FROM admin_ops_reconciliation_finding")
            }
            (rows.isNotEmpty()) shouldBe true
            (logs + texts + rows).filter { line -> listOf(reasonCanary, referenceCanary, cardCanary, "CANARYREASON", "CANARYREF").any { line.contains(it) } } shouldBe emptyList()
        }
    }

    Given("PaymentOpsSettingsBootSpec: the payment-ops configuration started by a real SpringApplication") {
        Then("a valid configuration starts and creates the gateway") {
            boot(*props(key = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx")).first shouldBe null
        }
        Then("time order violation (execution-window ≤ backoff-max) fails to start; the failure output exists and holds no key") {
            val (failure, text) = boot(*props(window = "30s"))
            (failure != null) shouldBe true
            text shouldContain "APPLICATION FAILED TO START"
            text shouldContain "backoff-max must be below execution-window"
            text shouldNotContain keyCanary
            text shouldNotContain "CANARY-SIGNING-KEY"
        }
        Then("a non-empty context path fails to start (I-31) without printing the key") {
            val (failure, text) = boot(*props(contextPath = "/admin"))
            (failure != null) shouldBe true
            text shouldContain "context-path must be empty"
            (text.contains("APPLICATION FAILED TO START") || text.contains("Application run failed")) shouldBe true
            text shouldNotContain keyCanary
        }
        Then("a too-short signing key fails to start without echoing the key value") {
            val shortKey = Base64.getEncoder().encodeToString("CANARY-SHORT".toByteArray())
            val (failure, text) = boot(*props(key = shortKey))
            (failure != null) shouldBe true
            text shouldContain "APPLICATION FAILED TO START"
            text shouldNotContain shortKey
        }
    }
})
