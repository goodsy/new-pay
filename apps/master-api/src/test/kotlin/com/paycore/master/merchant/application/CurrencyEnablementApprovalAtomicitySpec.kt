package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.currencyEnablement
import com.paycore.master.merchant.application.ApprovedCommandFixture.evidenceRow
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuedWithActivation
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterFixture
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * MERCHANT-OPS-002 §8.4 B3-2-3(§8.3 B3-2-2·4, D-MOPS-009 ② — §9.26, 실제 MySQL rollback): 근거 있는 통화 활성화(`EnableCurrency`)의 한 로컬 TX
 * (통화 기록·권리 revision·Snapshot·이력·Outbox·`admin_request`·근거 행). `AT_EXECUTION`이면 저장된 `effective_from` = 그 TX의 `lockedNow()`(잠금 **뒤** 시각 — 이력 `occurred_at`·
 * Snapshot 통화 구간 시작과 같은 값), 요청 지문 입력은 **문자열 `AT_EXECUTION`**(확정 시각 아님 — 시각이 달라진 재전송도 재생). DATE 모드는 기존 Domain `period.from < at` 판정.
 * 근거 행렬(같은 행위자·만료·잠금 전 거절 순서·기한 확인·결정 조회)은 §8.3 B3-1-2와 같고, 근거 없는 `enableCurrency`는 seed 관문(C-40).
 */
@MasterDbTest
class CurrencyEnablementApprovalAtomicitySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val decisions: ApprovalDecisionUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val mode: SwitchableDeploymentMode,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
        mode.reset()
    }

    class Injected : RuntimeException("injected")

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun evidence(approvalId: String) = runtime(container).use { evidenceRow(it, approvalId) }

    /** DATETIME(3)의 `CAST(… AS CHAR)` 표기(UTC 세션). */
    val dbTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    fun db(at: Instant): String = LocalDateTime.ofInstant(at, ZoneOffset.UTC).format(dbTime)

    data class Rows(
        val enablements: Long, val open: Long, val entitlement: Long, val snapshots: Long, val history: Long, val adminRequests: Long, val evidence: Long, val outbox: Long,
    )

    fun rows(merchantId: String, mid: MerchantAccountId, approvalId: String) = Rows(
        enablements = count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", mid.value),
        open = count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ? AND effective_to IS NULL", mid.value),
        entitlement = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId),
        snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", merchantId),
        history = count(
            "SELECT COUNT(*) FROM master_change_history WHERE change_type = 'CurrencyEnabled' AND aggregate_id IN " +
                "(SELECT enablement_id FROM currency_enablement WHERE merchant_account_id = ?)",
            mid.value,
        ),
        adminRequests = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.$approvalId"),
        evidence = count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", approvalId),
        // 이 MID 통화 기록의 통합 이벤트 + 그 가맹점 Snapshot Outbox.
        outbox = count(
            "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (SELECT enablement_id FROM currency_enablement WHERE merchant_account_id = ?) " +
                "OR event_id IN (SELECT outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ?)",
            mid.value, merchantId,
        ),
    )

    /** MID까지 발급된 가맹점(통화 기록 없음 — CARD 개통 등록만, KRW는 이 시험이 활성화한다). */
    fun issued(): Pair<String, MerchantAccountId> {
        val (ready, ids) = issuedWithActivation(onboarding)
        return ready.merchantId.value to ids.first
    }

    fun fromOf(mid: MerchantAccountId): String? = text("SELECT CAST(effective_from AS CHAR) FROM currency_enablement WHERE merchant_account_id = ?", mid.value)

    Given("B3-2-3: AT_EXECUTION resolves the start to the MASTER execution time after the merchant → merchant_account locks") {
        Then("success: one cur_ record from lockedNow, history at that time (actor = approver), Snapshot currency interval from that time with an Outbox HIGH row, EXECUTED evidence row") {
            val (merchantId, mid) = issued()
            val executedAt = OnboardingFixture.t0.plusSeconds(7)
            clock.set(executedAt)
            val command = currencyEnablement(mid)
            val before = rows(merchantId, mid, command.evidence.approvalId)
            val reference = entitlement.enableCurrencyWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().also { it.replayed shouldBe false }.reference
            reference shouldStartWith "cur_"
            rows(merchantId, mid, command.evidence.approvalId) shouldBe before.copy(
                enablements = 1, open = 1, entitlement = before.entitlement + 1, snapshots = before.snapshots + 1, history = 1, adminRequests = 1, evidence = 1,
                // Outbox = 통화 활성화 통합 이벤트 1 + Snapshot 1.
                outbox = before.outbox + 2,
            )
            // 확정 시각 하나가 통화 기록·이력·Snapshot에 같은 값으로(Financial Consistency §9.28).
            fromOf(mid) shouldBe db(executedAt)
            text("SELECT CAST(created_at AS CHAR) FROM currency_enablement WHERE enablement_id = ?", reference) shouldBe db(executedAt)
            text("SELECT CONCAT(currency_code, '/', evidence_ref, '/', created_by) FROM currency_enablement WHERE enablement_id = ?", reference) shouldBe
                "KRW/${ApprovedCommandFixture.INSTITUTION_REPLY}/${ApprovalEvidenceFixtures.APPROVER.value}"
            text("SELECT CONCAT(actor_ref, '|', CAST(occurred_at AS CHAR)) FROM master_change_history WHERE change_type = 'CurrencyEnabled' AND aggregate_id = ?", reference) shouldBe
                "${ApprovalEvidenceFixtures.APPROVER.value}|${db(executedAt)}"
            latestSnapshot(container, com.paycore.master.merchant.domain.MerchantId(merchantId)).get("accounts").single().get("currencies").single().let {
                listOf(it.get("currency").stringValue(), it.get("from").stringValue(), it.get("to")?.isNull ?: true) shouldBe listOf("KRW", "2026-10-09T02:00:07.000Z", true)
            }
            text(
                "SELECT priority FROM entitlement_snapshot_issue WHERE merchant_id = ? ORDER BY revision DESC, lease_seq DESC LIMIT 1", merchantId,
            ) shouldBe "HIGH"
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultReference, it.decidedVia, it.evidenceRef) shouldBe
                    listOf("EnableCurrency", "EXECUTED", reference, "EXECUTE", ApprovedCommandFixture.INSTITUTION_REPLY)
            }
        }

        Then("the request fingerprint input is the literal AT_EXECUTION (not the resolved time): a resend at a later MASTER time replays and the start does not move") {
            val (merchantId, mid) = issued()
            val command = currencyEnablement(mid)
            val first = entitlement.enableCurrencyWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            // 지문 = 명령 필드(mid, KRW, "AT_EXECUTION", "") + 근거 필드 — 확정 시각이 들어가지 않는다.
            text("SELECT request_fingerprint FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value) shouldBe
                AdminCommandExecutor.fingerprintOf(AdminOperation.ENABLE_CURRENCY, ApprovedCommands.fingerprintFields(command))
            ApprovedCommands.fingerprintFields(command).take(4) shouldBe listOf(mid.value, "KRW", "AT_EXECUTION", "")
            clock.set(OnboardingFixture.t0.plusSeconds(30))
            entitlement.enableCurrencyWithEvidence(command) shouldBe AdminCommandResult.Succeeded(first.reference, replayed = true)
            rows(merchantId, mid, command.evidence.approvalId).let { listOf(it.enablements, it.adminRequests, it.evidence) shouldBe listOf(1L, 1L, 1L) }
            fromOf(mid) shouldBe db(OnboardingFixture.t0)
        }

        Then("time passes while waiting for the merchant lock: reserved at t1, decided after the lock at t2 → effective_from = t2 (the post-lock time)") {
            val (_, mid) = issued()
            val t1 = OnboardingFixture.t0.plusSeconds(1)
            val t2 = OnboardingFixture.t0.plusSeconds(9)
            clock.set(t1)
            // 대역 시계: merchant 행 잠금을 잡은 직후(같은 TX) 시각이 t2로 흐른다 — 확정 시각은 잠금 뒤 값이어야 한다.
            faults.after(MasterFaultPoint.MERCHANT_LOCK) { clock.set(t2) }
            val command = currencyEnablement(mid)
            succeeded(entitlement.enableCurrencyWithEvidence(command))
            fromOf(mid) shouldBe db(t2)
            text("SELECT CAST(created_at AS CHAR) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value) shouldBe db(t1)
        }

        Then("AT_EXECUTION with an effectiveTo at or before the resolved time is a stored EFFECTIVE_PERIOD_EMPTY (REJECTED evidence row), nothing else changes") {
            listOf(
                // 오늘 00:00 KST(< t0)와, 시각을 정확히 내일 00:00 KST로 맞춘 경우(= 확정 시각).
                Triple(OnboardingFixture.t0, "2026-10-08T15:00:00.000Z", ApprovalEvidenceFixtures.distinctActors(evidenceRef = ApprovedCommandFixture.INSTITUTION_REPLY)),
                Triple(
                    Instant.parse("2026-10-09T15:00:00.000Z"), "2026-10-09T15:00:00.000Z",
                    ApprovalEvidenceFixtures.distinctActors(
                        evidenceRef = ApprovedCommandFixture.INSTITUTION_REPLY,
                        approvedAt = Instant.parse("2026-10-09T14:30:00.000Z"), executeBefore = Instant.parse("2026-10-09T15:30:00.000Z"),
                    ),
                ),
            ).forEach { (at, to, ev) ->
                withClue("at=$at to=$to") {
                    val (merchantId, mid) = issued()
                    clock.set(at)
                    val command = currencyEnablement(mid, effectiveToText = to, evidence = ev)
                    val before = rows(merchantId, mid, command.evidence.approvalId)
                    entitlement.enableCurrencyWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.EFFECTIVE_PERIOD_EMPTY, null, replayed = false)
                    rows(merchantId, mid, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
                    evidence(command.evidence.approvalId).shouldNotBeNull().let {
                        listOf(it.outcome, it.resultCode, it.decidedVia) shouldBe listOf("REJECTED", "EFFECTIVE_PERIOD_EMPTY", "EXECUTE")
                    }
                }
            }
        }
    }

    Given("B3-2-3: the DATE mode keeps the existing Domain judgement (period.from < at → retroactive)") {
        Then("tomorrow 00:00 KST requested at t0 is recorded with that start; the fingerprint carries the body string") {
            val (merchantId, mid) = issued()
            val command = currencyEnablement(mid, effectiveFromText = "2026-10-09T15:00:00.000Z", effectiveToText = "2026-10-10T15:00:00.000Z")
            succeeded(entitlement.enableCurrencyWithEvidence(command))
            text("SELECT CONCAT(CAST(effective_from AS CHAR), '|', CAST(effective_to AS CHAR)) FROM currency_enablement WHERE merchant_account_id = ?", mid.value) shouldBe
                "2026-10-09 15:00:00.000|2026-10-10 15:00:00.000"
            ApprovedCommands.fingerprintFields(command).take(4) shouldBe listOf(mid.value, "KRW", "2026-10-09T15:00:00.000Z", "2026-10-10T15:00:00.000Z")
            rows(merchantId, mid, command.evidence.approvalId).evidence shouldBe 1
        }

        Then("midnight passed while the approval waited (lockedNow = tomorrow 00:00:00.001 KST) → stored CURRENCY_ENABLEMENT_RETROACTIVE, REJECTED evidence row") {
            val (merchantId, mid) = issued()
            val ev = ApprovalEvidenceFixtures.distinctActors(
                evidenceRef = ApprovedCommandFixture.INSTITUTION_REPLY,
                approvedAt = Instant.parse("2026-10-09T14:30:00.000Z"), executeBefore = Instant.parse("2026-10-09T15:30:00.000Z"),
            )
            clock.set(Instant.parse("2026-10-09T15:00:00.001Z"))
            val command = currencyEnablement(mid, effectiveFromText = "2026-10-09T15:00:00.000Z", evidence = ev)
            val before = rows(merchantId, mid, command.evidence.approvalId)
            entitlement.enableCurrencyWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE, null, replayed = false)
            rows(merchantId, mid, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "CURRENCY_ENABLEMENT_RETROACTIVE"
        }
    }

    Given("B3-2-3 (= §8.3 B3-1-2 matrix): atomicity, same actor, expiry order, overlap, deadline confirmation") {
        Then("a fault after the evidence row write, at the currency insert or at the result write rolls back everything; a retry then executes once") {
            listOf(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD, MasterFaultPoint.CURRENCY_INSERT, MasterFaultPoint.ADMIN_COMPLETE).forEach { point ->
                withClue(point) {
                    val (merchantId, mid) = issued()
                    val command = currencyEnablement(mid)
                    val before = rows(merchantId, mid, command.evidence.approvalId)
                    faults.after(point) { throw Injected() }
                    shouldThrow<Injected> { entitlement.enableCurrencyWithEvidence(command) }
                    rows(merchantId, mid, command.evidence.approvalId) shouldBe before
                    faults.reset()
                    entitlement.enableCurrencyWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
                    rows(merchantId, mid, command.evidence.approvalId).let { listOf(it.enablements, it.evidence, it.history) shouldBe listOf(1L, 1L, 1L) }
                }
            }
        }

        Then("the same actor is a stored APPROVAL_SAME_ACTOR with a REJECTED evidence row; no currency record") {
            val (merchantId, mid) = issued()
            val command = currencyEnablement(mid, evidence = ApprovalEvidenceFixtures.sameActor())
            val before = rows(merchantId, mid, command.evidence.approvalId)
            entitlement.enableCurrencyWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_SAME_ACTOR, null, replayed = false)
            rows(merchantId, mid, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "APPROVAL_SAME_ACTOR"
        }

        Then("order (m-4): expired evidence for an unknown MID is MERCHANT_ACCOUNT_NOT_FOUND (pre-lock lookup); for a known MID it is APPROVAL_EXPIRED") {
            val missing = MerchantAccountId("mid_" + MasterFixture.ulid())
            val unknown = currencyEnablement(missing, evidence = ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.enableCurrencyWithEvidence(unknown) shouldBe AdminCommandResult.Rejected(MasterRejection.MERCHANT_ACCOUNT_NOT_FOUND, null, replayed = false)
            evidence(unknown.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "MERCHANT_ACCOUNT_NOT_FOUND"
            val (_, mid) = issued()
            // 기한 지난 근거 + 비어 버릴 기간: 만료 훅(lockedNow 직후)이 기간 판정보다 먼저.
            val known = currencyEnablement(mid, effectiveToText = "2026-10-08T15:00:00.000Z", evidence = ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.enableCurrencyWithEvidence(known) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", mid.value) shouldBe 0
        }

        Then("an already open KRW record makes the next approval a stored CURRENCY_ENABLEMENT_OVERLAP (REJECTED evidence row); open records stay 1") {
            val (merchantId, mid) = issued()
            succeeded(entitlement.enableCurrencyWithEvidence(currencyEnablement(mid)))
            val second = currencyEnablement(mid, effectiveFromText = "2026-10-09T15:00:00.000Z")
            val before = rows(merchantId, mid, second.evidence.approvalId)
            // Use Case 거절에는 기존 열린 기록이 reference로 붙는다(Domain 기존 동작) — 관리 채널 응답은 그것을 버린다(C-12, CurrencyEnablementApiSpec ①).
            entitlement.enableCurrencyWithEvidence(second).shouldBeInstanceOf<AdminCommandResult.Rejected>().let {
                (it.rejection to it.replayed) shouldBe (MasterRejection.CURRENCY_ENABLEMENT_OVERLAP to false)
                it.reference.shouldNotBeNull() shouldStartWith "cur_"
            }
            rows(merchantId, mid, second.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            before.open shouldBe 1
        }

        Then("EnableCurrency deadline confirmation: NOT_YET_DUE stores nothing; at the deadline APPROVAL_EXPIRED (DEADLINE_CONFIRM) without a record; the execute resend replays it") {
            val (merchantId, mid) = issued()
            val command = currencyEnablement(mid)
            val before = rows(merchantId, mid, command.evidence.approvalId)
            clock.set(command.evidence.executeBefore.minusMillis(1))
            decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.NotYetDue
            rows(merchantId, mid, command.evidence.approvalId) shouldBe before
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false))
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultCode, it.decidedVia) shouldBe listOf("EnableCurrency", "REJECTED", "APPROVAL_EXPIRED", "DEADLINE_CONFIRM")
            }
            rows(merchantId, mid, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            clock.reset()
            entitlement.enableCurrencyWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true)
        }

        Then("the decision query returns the EnableCurrency decision with the cur_ reference") {
            val (_, mid) = issued()
            val command = currencyEnablement(mid)
            val reference = succeeded(entitlement.enableCurrencyWithEvidence(command))
            decisions.decision(command.evidence.approvalId).shouldBeInstanceOf<com.paycore.master.merchant.application.provided.ApprovalDecision.Decided>().let {
                listOf(it.operation, it.reference) shouldBe listOf(AdminOperation.ENABLE_CURRENCY, reference)
            }
        }

        Then("invariant n-a includes EnableCurrency: every stored result has exactly one evidence row") {
            val (_, mid) = issued()
            val ok = currencyEnablement(mid)
            succeeded(entitlement.enableCurrencyWithEvidence(ok))
            val refused = currencyEnablement(mid)
            entitlement.enableCurrencyWithEvidence(refused).shouldBeInstanceOf<AdminCommandResult.Rejected>()
            runtime(container).use { ApprovedCommandFixture.invariantViolations(it) }.shouldBeEmpty()
            // 대조군: 불변식 조회가 EnableCurrency 승인 행을 실제로 본다.
            count(
                "SELECT COUNT(*) FROM admin_request WHERE operation = 'EnableCurrency' AND idempotency_key IN (?, ?)",
                ok.request.idempotencyKey.value, refused.request.idempotencyKey.value,
            ) shouldBe 2
        }
    }

    Given("B3-2-3 / C-40 (D-MOPS-008 ②): the evidence-less enableCurrency runs only on the Demo seed path") {
        Then("other actors (system:other, admin:…, operator-1) and LIVE mode are refused with IllegalStateException before any transaction (no admin_request row); the seed actor succeeds") {
            val (_, mid) = issued()
            fun command(actor: ActorRef?, key: com.paycore.master.merchant.application.provided.AdminIdempotencyKey = OnboardingFixture.key()) = EnableCurrencyCommand(
                if (actor == null) seedRequest(key) else request(key, actor), mid, CurrencyCode("KRW"), EffectivePeriod(OnboardingFixture.t0), EntitlementCommandFixture.evidence,
            )
            listOf(ActorRef("system:other"), ApprovalEvidenceFixtures.APPROVER, OnboardingFixture.operator).forEach { actor ->
                withClue(actor.value) {
                    val key = OnboardingFixture.key()
                    shouldThrow<IllegalStateException> { entitlement.enableCurrency(command(actor, key)) }
                    count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe 0
                }
            }
            mode.demo = false
            val liveKey = OnboardingFixture.key()
            shouldThrow<IllegalStateException> { entitlement.enableCurrency(command(null, liveKey)) }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", liveKey.value) shouldBe 0
            mode.reset()
            count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", mid.value) shouldBe 0
            succeeded(entitlement.enableCurrency(command(null))) shouldNotBe null
        }
    }
})
