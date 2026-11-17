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
import com.paycore.admin.merchantops.application.MerchantLookupService
import com.paycore.admin.merchantops.application.MerchantTargetLabelService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantLabelClient
import com.paycore.admin.merchantops.application.provided.MerchantLabelFailure
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelResult
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import com.paycore.admin.merchantops.application.required.MerchantTargetLabelLimits
import com.paycore.admin.merchantops.application.required.MerchantTargetLabelMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.time.Duration

/**
 * MERCHANT-CODE-001 MC-3-17·MC-3-19(MC-3b Kotlin — 대역 Port, Spring 없음, 명시 Fixture): 화면 가맹점 라벨 Use Case 판정 순서(역할 4종 → 입력 → 라벨 전용 분당 창 → MASTER),
 * 역할 밖·입력 위반은 MASTER 0회·창 소비 0, **창은 호출 1회당 1 소비(50개 묶음도 1)**, 초과 → RateLimited·MASTER 0회·지표 RATE_LIMITED, **가맹점 검색 창 `lookup-per-minute`
 * 소비 0**(라벨을 창 한도까지 부른 뒤에도 같은 actor의 가맹점 목록 검색이 성공 — 변이 (l) 대조: 라벨 창(3)이 검색 창(2)보다 크게 둔다), MASTER 실패 분류 → Unavailable·지표,
 * 감사 0행, 로그·지표 label에 키·상호·코드 없음.
 */
class MerchantTargetLabelServiceSpec : BehaviorSpec({

    val ctx = AdminRequestContext("corr-1", "127.0.0.1")
    fun mer(n: Int) = "mer_01JC" + n.toString().padStart(22, '0')

    /** [labelReads]·[lookup]: 두 창의 Demo 밖 시험 값(라벨 창 > 검색 창 — 라벨이 검색 창을 쓰면 3번째 라벨 호출이 막힌다). */
    class Fixture(roles: Set<String> = setOf("VIEWER"), status: String = "ACTIVE", labelReads: Int = 3, lookup: Int = 2, actorId: String = "adm_01JC00000000000000000000V1") {
        val view = AdminSessionView(actorId, "Viewer", status, 1, 1, roles)
        val other = AdminSessionView("adm_01JC00000000000000000000V2", "Other", "ACTIVE", 1, 1, setOf("APPROVER"))
        val masterCalls = mutableListOf<List<String>>()
        val searches = mutableListOf<String>()
        val metrics = mutableListOf<String>()
        val rateLimitedKinds = mutableListOf<String>()
        val audits = mutableListOf<AuditRecord>()
        var reply: MerchantLabelResult = MerchantLabelResult.Found(mapOf("mer_01JC0000000000000000000001" to MerchantLabel("데모상점", "demoshop01")))

        /** 한 설정 객체가 두 Port를 모두 구현한다(실제 `MasterOpsSettings`와 같은 구성). */
        val limits = object : MerchantOpsLimits, MerchantTargetLabelLimits {
            override val lookupPerMinute = lookup
            override val registrationNumberSearchesPerMinute = 100
            override val registrationsPerMinute = 100
            override val labelReadsPerMinute = labelReads
        }
        val sessions = object : AdminSessionQuery {
            override fun current(actorId: String) = listOf(view, other).firstOrNull { it.actorId == actorId }
            override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
        }
        val service = MerchantTargetLabelService(
            sessions,
            object : MerchantLabelClient {
                override fun labels(merchantAccountIds: List<String>): MerchantLabelResult = error("the target label API never asks MID labels")
                override fun merchantLabels(merchantIds: List<String>): MerchantLabelResult = reply.also { masterCalls += merchantIds }
            },
            limits,
            object : MerchantTargetLabelMetrics {
                override fun targetLabels(result: String) {
                    metrics += result
                }
            },
        )
        val lookup = MerchantLookupService(
            object : AbstractPlatformTransactionManager() {
                override fun doGetTransaction(): Any = Any()
                override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
                override fun doCommit(status: DefaultTransactionStatus) = Unit
                override fun doRollback(status: DefaultTransactionStatus) = Unit
            },
            sessions,
            object : MasterOpsGateway {
                override fun search(criteria: MerchantSearchCriteria): MasterCallResult =
                    MasterCallResult.Found(mapOf("items" to emptyList<Any>(), "nextCursor" to null)).also { searches += "search" }
                override fun detail(merchantId: String): MasterCallResult = error("not used")
                override fun history(merchantId: String, afterSeq: Long): MasterCallResult = error("not used")
                override fun approval(operation: com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation, body: ByteArray, deadline: Boolean):
                    com.paycore.admin.merchantops.application.provided.MerchantApprovalResult = error("not used")
                override fun approvalDecision(approvalId: String): com.paycore.admin.merchantops.application.provided.MerchantApprovalResult = error("not used")
                override fun command(command: MasterCommand): MasterCommandResult = error("not used")
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    audits += record
                }
            },
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) {
                    rateLimitedKinds += kind
                }
                override fun command(operation: String, result: String) = Unit
                override fun commandUnknown(operation: String) = Unit
            },
            RegistrationNumberBudget(limits),
        )
        val actor get() = view.actorId
    }

    Given("MC-3-17 the view roles (the set that may open the approval inbox and the audit screen)") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN get labels; UNMASK-only, no role and an inactive account are Forbidden without a MASTER call or a window slot") {
            listOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.labels(f.actor, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>().labels shouldBe
                    mapOf(mer(1) to MerchantLabel("데모상점", "demoshop01"))
                f.masterCalls shouldBe listOf(listOf(mer(1)))
            }
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("OPERATOR"), status = "SUSPENDED")).forEach { f ->
                repeat(5) { f.service.labels(f.actor, listOf(mer(1))) shouldBe MerchantTargetLabelResult.Forbidden }
                f.service.labels(f.actor, null) shouldBe MerchantTargetLabelResult.Forbidden
                f.masterCalls.shouldBeEmpty()
                f.metrics.shouldBeEmpty()
            }
            Fixture().service.labels("adm_01JC0000000000000000000UNK", listOf(mer(1))) shouldBe MerchantTargetLabelResult.Forbidden
        }
    }

    Given("MC-3-17 input rules (before MASTER and before the window)") {
        Then("a malformed body, 0 or 51 ids, duplicates, a MID, a business key, a code or lower case are InvalidInput — MASTER 0 calls, no window slot spent") {
            val f = Fixture(labelReads = 1)
            listOf(
                null, emptyList(), (1..51).map(::mer), listOf(mer(1), mer(1)), listOf("mid_01JC0000000000000000000001"), listOf("biz_01JC0000000000000000000001"),
                listOf("demoshop01"), listOf(mer(1).lowercase()), listOf("mer_x"),
            ).forEach { ids -> withClue(ids?.take(2)) { f.service.labels(f.actor, ids) shouldBe MerchantTargetLabelResult.InvalidInput } }
            f.masterCalls.shouldBeEmpty()
            // 창 소비 없음 대조: 창 1회짜리 actor가 그 뒤에도 한 번 부를 수 있다.
            f.service.labels(f.actor, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
        }

        Then("MASTER receives the ids sorted ascending; 50 ids (the upper bound) is accepted") {
            val f = Fixture()
            f.service.labels(f.actor, listOf(mer(3), mer(1), mer(2))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
            f.service.labels(f.actor, (50 downTo 1).map(::mer)).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
            f.masterCalls shouldBe listOf(listOf(mer(1), mer(2), mer(3)), (1..50).map(::mer))
        }
    }

    Given("MC-3-19 the label read window (C-35 — label-reads-per-minute, one slot per HTTP call)") {
        Then("one slot per call whatever the batch size; past the window the call is RateLimited without a MASTER call (metric RATE_LIMITED); another actor is unaffected") {
            val f = Fixture(labelReads = 3, lookup = 2)
            f.service.labels(f.actor, (1..50).map(::mer)).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
            f.service.labels(f.actor, (1..50).map(::mer)).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
            f.service.labels(f.actor, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
            f.service.labels(f.actor, listOf(mer(1))) shouldBe MerchantTargetLabelResult.RateLimited
            f.masterCalls.size shouldBe 3
            f.metrics shouldBe listOf("OK", "OK", "OK", "RATE_LIMITED")
            f.service.labels(f.other.actorId, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
        }

        Then("MC-3-19 the merchant search window (lookup-per-minute) is not spent by labels: after the label window is used up, the same actor's merchant search still succeeds (control: the search window itself still limits)") {
            val f = Fixture(labelReads = 3, lookup = 2)
            repeat(3) { f.service.labels(f.actor, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>() }
            f.service.labels(f.actor, listOf(mer(1))) shouldBe MerchantTargetLabelResult.RateLimited
            f.lookup.search(f.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.lookup.search(f.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>()
            f.lookup.search(f.actor, MerchantSearchCriteria(), ctx) shouldBe MerchantLookupResult.RateLimited
            f.searches.size shouldBe 2
            f.rateLimitedKinds shouldBe listOf("LOOKUP")
            // 역방향: 검색 창을 다 쓴 다른 Fixture에서도 라벨은 답한다.
            val g = Fixture(labelReads = 3, lookup = 2)
            repeat(2) { g.lookup.search(g.actor, MerchantSearchCriteria(), ctx).shouldBeInstanceOf<MerchantLookupResult.Found>() }
            g.lookup.search(g.actor, MerchantSearchCriteria(), ctx) shouldBe MerchantLookupResult.RateLimited
            g.service.labels(g.actor, listOf(mer(1))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
        }
    }

    Given("MC-3-17 MASTER failures are values, not exceptions; no audit; logs carry no key, name or code") {
        Then("BUSY, UNAVAILABLE and UNREADABLE become Unavailable with the same metric class; Found is OK") {
            listOf(MerchantLabelFailure.BUSY, MerchantLabelFailure.UNAVAILABLE, MerchantLabelFailure.UNREADABLE).forEach { failure ->
                val f = Fixture()
                f.reply = MerchantLabelResult.Unavailable(failure, "X")
                f.service.labels(f.actor, listOf(mer(1))) shouldBe MerchantTargetLabelResult.Unavailable(failure)
                f.metrics shouldBe listOf(failure.name)
            }
        }

        Then("no audit row in any branch, and the log lines carry only the classification and the request count") {
            val f = Fixture(labelReads = 1)
            f.reply = MerchantLabelResult.Found(mapOf(mer(7) to MerchantLabel("비밀상점", "secretshop7")))
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                f.service.labels(f.actor, listOf(mer(7))).shouldBeInstanceOf<MerchantTargetLabelResult.Found>()
                f.service.labels(f.actor, listOf(mer(7))) shouldBe MerchantTargetLabelResult.RateLimited
                f.service.labels(f.actor, listOf(mer(7), mer(7))) shouldBe MerchantTargetLabelResult.InvalidInput
            } finally {
                root.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            lines.filter { "operation=merchant-target-labels" in it } shouldBe listOf(
                "operation=merchant-target-labels result=OK requested=1",
                "operation=merchant-target-labels result=RATE_LIMITED requested=1",
                "operation=merchant-target-labels result=INVALID_INPUT requested=2",
            )
            lines.forEach { line -> listOf(mer(7), "비밀상점", "secretshop7", f.actor).forEach { withClue(line) { line.contains(it) shouldBe false } } }
            f.audits.shouldBeEmpty()
        }
    }
})
