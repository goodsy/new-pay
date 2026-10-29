package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.ReconcileOperatorDecisionsUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 대사 job 실행(ADMIN-PAYMENT-OPS-001 §4.17·§7.2 — `OperatorDecisionReconciliationSpec`·`ReconciliationFullScanSpec`·`ReconciliationCheckpointRaceSpec`, D-OPS-008).
 * 실제 MySQL 8.4.11, PAYMENT 결정 목록은 실제 HTTP 대역. checkpoint는 단일 행이라 시험마다 고유한 watermark 구간으로 옮겨 시작한다(root — 시각·위치 조정만).
 * 대사는 **승인 상태를 절대 바꾸지 않는다**(모든 시험이 상태 전후를 비교).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OperatorDecisionReconciliationRunSpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val reconciliation: ReconcileOperatorDecisionsUseCase,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    val base = AtomicLong(System.nanoTime() % 1_000_000L * 1_000_000L + 50_000_000_000L)
    fun root() = AdminDbAccounts.root(container)

    class Seeded(val id: String, val paymentId: String, val requester: String, val approver: String?, val fingerprint: String?)

    fun seed(reply: StubPaymentOpsServer.Reply?): Seeded {
        val operator = fixture.operator()
        val (id, paymentId) = fixture.requested(operator)
        if (reply == null) return Seeded(id, paymentId, operator.id.value, null, null)
        StubPaymentOpsServer.execution(id, reply)
        val approver = fixture.approver()
        fixture.approve(id, approver)
        val fp = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
        return Seeded(id, paymentId, operator.id.value, approver.account.id.value, fp)
    }
    val ok = { cid: String -> StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"$cid\",\"status\":\"IN_PROGRESS\",\"existing\":false}") }
    val unavailable = StubPaymentOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")

    /** checkpoint를 고유 구간으로 옮기고 전체 스캔은 방금 한 것으로(증분만 — [fullScanDue]면 오래전으로). 시작 watermark. */
    fun startAt(fullScanDue: Boolean = false): Long {
        val wm = base.addAndGet(1_000)
        root().use {
            it.update(
                "UPDATE admin_ops_reconciliation_checkpoint SET watermark = ?, last_full_scan_at = " +
                    (if (fullScanDue) "UTC_TIMESTAMP(3) - INTERVAL 2 DAY" else "UTC_TIMESTAMP(3)") + " WHERE id = 1",
                wm,
            )
        }
        return wm
    }
    fun checkpoint() = root().use { it.column("SELECT CONCAT(watermark, '|', last_full_scan_at IS NOT NULL) FROM admin_ops_reconciliation_checkpoint").single() }
    fun watermark() = (root().use { it.scalar("SELECT watermark FROM admin_ops_reconciliation_checkpoint") } as Number).toLong()
    fun findings(id: String) = root().use { it.column("SELECT CONCAT(kind, '|', COALESCE(detail_code, '-')) FROM admin_ops_reconciliation_finding WHERE approval_id = ? ORDER BY kind", id) }
    fun statuses(vararg ids: String) = ids.map { id -> root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String } }
    fun counter(name: String, vararg tags: String) = registry.find(name).tags(*tags).counters().sumOf { it.count() }
    val recent = Instant.now().toString().let { it.substring(0, 19) + ".000Z" }
    /** 실행 행의 마지막 응답 시각을 [minutesAgo]분 전으로(root — 시각만). M-1: 전체 스캔 MISSING 후보는 마지막 응답 기준. */
    fun responded(id: String, minutesAgo: Long) =
        root().use { it.update("UPDATE admin_owner_execution SET last_response_at = UTC_TIMESTAMP(3) - INTERVAL ? MINUTE WHERE approval_id = ?", minutesAgo, id) }
    fun lastRunAt() = root().use { it.scalar("SELECT COALESCE(CAST(last_run_at AS CHAR), '-') FROM admin_ops_reconciliation_checkpoint") as String }
    fun lastRunRecent() = root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, last_run_at, UTC_TIMESTAMP(3)) FROM admin_ops_reconciliation_checkpoint") as Number).toLong() } < 60
    fun oldLastRun() = root().use { it.update("UPDATE admin_ops_reconciliation_checkpoint SET last_run_at = '2026-01-01 00:00:00.000' WHERE id = 1") }
    fun lag() = registry.find("paycore.admin.ops.recon.lag_seconds").gauge()?.value() ?: -1.0
    fun orphan(seq: Long, id: String) = StubPaymentOpsServer.listItem(
        seq, id, "pay_01JC0000000000000000000009", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "c".repeat(64), code = "PAYMENT_NOT_FOUND",
    )
    fun page(items: List<String>, next: Long, delayMillis: Long = 0) = StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.listPage(items, next), delayMillis)
    fun listRequests(after: Long) = StubPaymentOpsServer.requestsForPath("/internal/v1/admin/operator-decisions/after/$after").size

    Given("one decision list page with one decision of each kind (incremental run)") {
        val executed = seed(ok("pcn_01JC000000000000000000RX01"))
        val unknown = seed(unavailable)
        val pending = seed(null)
        val orphan = "apr_01JC0000000000000000RPHN01"
        val wm = startAt()
        val items = listOf(
            StubPaymentOpsServer.listItem(wm + 1, orphan, "pay_01JC0000000000000000000009", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "c".repeat(64), code = "PAYMENT_NOT_FOUND"),
            StubPaymentOpsServer.listItem(wm + 2, pending.id, pending.paymentId, pending.requester, "adm_01JC00000000000000000000A1", "oct1:" + "d".repeat(64), cancellationId = "pcn_01JC000000000000000000PD01"),
            StubPaymentOpsServer.listItem(wm + 3, executed.id, executed.paymentId, executed.requester, executed.approver!!, "oct1:" + "e".repeat(64), cancellationId = "pcn_01JC000000000000000000RX01"),
            StubPaymentOpsServer.listItem(wm + 4, unknown.id, unknown.paymentId, unknown.requester, unknown.approver!!, unknown.fingerprint!!, cancellationId = "pcn_01JC000000000000000000VW01", recordedAt = "2026-01-01T00:00:00.000Z"),
        )
        StubPaymentOpsServer.decisionList(wm, StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.listPage(items, wm + 4)))
        val statusesBefore = statuses(executed.id, unknown.id, pending.id)
        val report = reconciliation.reconcile()

        Then("each finding is recorded once with its kind, the watermark advances to nextWatermark, and no approval state changes") {
            listOf(report.result, report.fullScan) shouldBe listOf<Any>("OK", false)
            listOf(findings(orphan), findings(pending.id), findings(executed.id), findings(unknown.id)) shouldBe listOf(
                listOf("ORPHAN_DECISION|-"), listOf("DECISION_FOR_UNAPPROVED|CONTENT_MATCH"), listOf("FINGERPRINT_MISMATCH|-"), listOf("UNRESOLVED_WITH_DECISION|-"),
            )
            watermark() shouldBe wm + 4
            statuses(executed.id, unknown.id, pending.id) shouldBe statusesBefore
        }
        Then("re-reading the same page records nothing new (UNIQUE — duplicate is normal, counted only), still no state change") {
            val duplicates = counter("paycore.admin.ops.recon.finding_duplicate")
            root().use { it.update("UPDATE admin_ops_reconciliation_checkpoint SET watermark = ? WHERE id = 1", wm) }
            reconciliation.reconcile().result shouldBe "OK"
            listOf(findings(orphan).size, findings(pending.id).size, findings(executed.id).size, findings(unknown.id).size) shouldBe listOf(1, 1, 1, 1)
            (counter("paycore.admin.ops.recon.finding_duplicate") - duplicates) shouldBe 4.0
            statuses(executed.id, unknown.id, pending.id) shouldBe statusesBefore
        }
    }

    Given("STATE_DIVERGENCE: ADMIN EXECUTED but PAYMENT recorded REJECTED") {
        Then("it is a finding, ADMIN stays EXECUTED") {
            val executed = seed(ok("pcn_01JC000000000000000000SD01"))
            val wm = startAt()
            StubPaymentOpsServer.decisionList(
                wm,
                StubPaymentOpsServer.Reply(
                    200,
                    StubPaymentOpsServer.listPage(
                        listOf(StubPaymentOpsServer.listItem(wm + 1, executed.id, executed.paymentId, executed.requester, executed.approver!!, executed.fingerprint!!, code = "CANCEL_IN_PROGRESS", recordedAt = recent)),
                        wm + 1,
                    ),
                ),
            )
            reconciliation.reconcile()
            findings(executed.id) shouldBe listOf("STATE_DIVERGENCE|-")
            statuses(executed.id) shouldBe listOf("EXECUTED")
        }
    }

    Given("the stable watermark (PAYMENT holds back rows that may still commit)") {
        Then("the checkpoint advances to nextWatermark, never to the largest seq seen") {
            val wm = startAt()
            val items = (1..3).map { StubPaymentOpsServer.listItem(wm + it, "apr_01JC00000000000000000STB0$it", "pay_01JC0000000000000000000009", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1", "oct1:" + "c".repeat(64), code = "PAYMENT_NOT_FOUND") }
            StubPaymentOpsServer.decisionList(wm, StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.listPage(items, wm + 1)))
            reconciliation.reconcile()
            watermark() shouldBe wm + 1
        }
    }

    Given("PAYMENT unavailable") {
        Then("the watermark does not move, the run is counted as PAYMENT_UNAVAILABLE and the lag gauge keeps growing from the last success") {
            val wm = startAt()
            StubPaymentOpsServer.decisionList(wm, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val failures = counter("paycore.admin.ops.recon.run", "result", "PAYMENT_UNAVAILABLE")
            reconciliation.reconcile().result shouldBe "PAYMENT_UNAVAILABLE"
            watermark() shouldBe wm
            (counter("paycore.admin.ops.recon.run", "result", "PAYMENT_UNAVAILABLE") - failures) shouldBe 1.0
            ((registry.find("paycore.admin.ops.recon.lag_seconds").gauge()?.value() ?: -1.0) >= 0.0) shouldBe true
        }
    }

    Given("ReconciliationFullScanSpec: a decision the incremental run skipped (late commit) and an EXECUTED approval PAYMENT has no decision for") {
        Then("the daily full scan from seq 0 records both, updates last_full_scan_at, and never moves the checkpoint watermark") {
            val late = seed(null)
            val missing = seed(ok("pcn_01JC000000000000000000MS01"))
            responded(missing.id, minutesAgo = 60)
            val wm = startAt(fullScanDue = true)
            StubPaymentOpsServer.decisionList(
                0L,
                StubPaymentOpsServer.Reply(
                    200,
                    StubPaymentOpsServer.listPage(
                        listOf(StubPaymentOpsServer.listItem(wm - 500, late.id, late.paymentId, late.requester, "adm_01JC00000000000000000000A1", "oct1:" + "d".repeat(64), code = "CANCEL_IN_PROGRESS", recordedAt = recent)),
                        wm - 500,
                    ),
                ),
            )
            val report = reconciliation.reconcile()
            report.fullScan shouldBe true
            findings(late.id) shouldBe listOf("DECISION_FOR_UNAPPROVED|CONTENT_MATCH")
            findings(missing.id) shouldBe listOf("MISSING_DECISION|-")
            watermark() shouldBe wm
            (root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, last_full_scan_at, UTC_TIMESTAMP(3)) FROM admin_ops_reconciliation_checkpoint") as Number).toLong() } < 60) shouldBe true
            statuses(late.id, missing.id) shouldBe listOf("PENDING", "EXECUTED")
        }
    }

    Given("S7 review M-1(a)(c): full-scan MISSING_DECISION candidates") {
        Then("only approvals whose last response is before scan start − (stable delay + skew), never PAYMENT 400 endings: old → 1; recent, during the scan, QUERY_NOT_ALLOWED, REQUEST_UNREADABLE → 0") {
            val old = seed(ok("pcn_01JC000000000000000000MD01"))
            val recentEnd = seed(ok("pcn_01JC000000000000000000MD02"))
            val queryRejected = seed(StubPaymentOpsServer.Reply(400, "{\"error\":\"QUERY_NOT_ALLOWED\"}"))
            val unreadable = seed(StubPaymentOpsServer.Reply(400, "{\"error\":\"REQUEST_UNREADABLE\"}"))
            statuses(old.id, recentEnd.id, queryRejected.id, unreadable.id) shouldBe listOf("EXECUTED", "EXECUTED", "OWNER_REJECTED", "OWNER_REJECTED")
            responded(old.id, minutesAgo = 60)
            responded(recentEnd.id, minutesAgo = 3)
            responded(queryRejected.id, minutesAgo = 60)
            responded(unreadable.id, minutesAgo = 60)
            startAt(fullScanDue = true)
            val requests = listRequests(0)
            StubPaymentOpsServer.decisionList(0L, page(emptyList(), 0, delayMillis = 1_500)) // 시험 read-timeout(2초)보다 짧게
            val running = CompletableFuture.supplyAsync { reconciliation.reconcile() }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (listRequests(0) == requests && System.nanoTime() < until) Thread.sleep(20)
            // 전체 스캔이 PAYMENT 목록을 읽는 동안 종료된 승인(마지막 응답이 스캔 시작 뒤).
            val duringScan = seed(ok("pcn_01JC000000000000000000MD03"))
            running.get(60, TimeUnit.SECONDS).fullScan shouldBe true
            // S7 리뷰 L-B(Coordinator 결정): PAYMENT는 본문 읽기 실패를 이제 503으로 답한다 — 400 REQUEST_UNREADABLE은 결정 없는 400 집합 밖이라, 그 code로 끝난 승인은
            // 안전 쪽으로 MISSING_DECISION에 드러난다(I-120).
            listOf(findings(old.id), findings(recentEnd.id), findings(queryRejected.id), findings(unreadable.id), findings(duringScan.id)) shouldBe
                listOf(listOf("MISSING_DECISION|-"), emptyList(), emptyList(), listOf("MISSING_DECISION|-"), emptyList())
            statuses(old.id, recentEnd.id, duringScan.id) shouldBe listOf("EXECUTED", "EXECUTED", "EXECUTED")
        }
    }

    Given("S7 review M-1(b): the full scan stores no watermark, so it pages after the largest seq it has read") {
        Then("the next full-scan page starts after the page's largest seq, not after the stable nextWatermark; the checkpoint watermark is unchanged") {
            val wm = startAt(fullScanDue = true)
            StubPaymentOpsServer.decisionList(0L, page(listOf(orphan(wm + 10, "apr_01JC0000000000000000FSA001"), orphan(wm + 20, "apr_01JC0000000000000000FSB001")), wm + 10))
            StubPaymentOpsServer.decisionList(wm + 20, page(listOf(orphan(wm + 30, "apr_01JC0000000000000000FSC001")), wm + 30))
            StubPaymentOpsServer.decisionList(wm + 10, page(listOf(orphan(wm + 15, "apr_01JC0000000000000000FSD001")), wm + 15))
            reconciliation.reconcile().fullScan shouldBe true
            listOf(findings("apr_01JC0000000000000000FSC001"), findings("apr_01JC0000000000000000FSD001")) shouldBe listOf(listOf("ORPHAN_DECISION|-"), emptyList())
            listOf(listRequests(wm + 20), listRequests(wm + 10)) shouldBe listOf(1, 0)
            watermark() shouldBe wm
        }
    }

    Given("S7 review L-3: an item whose judgement fails (its ADMIN command row is unreadable)") {
        Then("the checkpoint stops just before it (read again next run), the other items are recorded, the run is ITEM_ERROR and last_run_at is kept; once repaired it advances") {
            val broken = seed(null)
            val original = root().use { it.scalar("SELECT CAST(pinned_target AS CHAR) FROM admin_operator_cancel_command WHERE approval_id = ?", broken.id) as String }
            root().use { it.update("UPDATE admin_operator_cancel_command SET pinned_target = JSON_OBJECT('paymentId', 1) WHERE approval_id = ?", broken.id) }
            val wm = startAt()
            oldLastRun()
            val brokenItem = StubPaymentOpsServer.listItem(
                wm + 2, broken.id, broken.paymentId, broken.requester, "adm_01JC00000000000000000000A1", "oct1:" + "d".repeat(64), cancellationId = "pcn_01JC000000000000000000TE02",
            )
            val before = "apr_01JC0000000000000000TEM001"
            val after = "apr_01JC0000000000000000TEM003"
            StubPaymentOpsServer.decisionList(wm, page(listOf(orphan(wm + 1, before), brokenItem, orphan(wm + 3, after)), wm + 3))
            StubPaymentOpsServer.decisionList(wm + 1, page(listOf(brokenItem, orphan(wm + 3, after)), wm + 3))
            val errors = counter("paycore.admin.ops.recon.run", "result", "ITEM_ERROR")
            reconciliation.reconcile().result shouldBe "ITEM_ERROR"
            watermark() shouldBe wm + 1
            listOf(findings(before), findings(after), findings(broken.id)) shouldBe listOf(listOf("ORPHAN_DECISION|-"), listOf("ORPHAN_DECISION|-"), emptyList())
            lastRunAt() shouldBe "2026-01-01 00:00:00.000"
            (counter("paycore.admin.ops.recon.run", "result", "ITEM_ERROR") - errors) shouldBe 1.0
            // 고칠 때까지 그 항목 앞에 머문다(I-119 — lag 경보로 드러난다).
            reconciliation.reconcile().result shouldBe "ITEM_ERROR"
            watermark() shouldBe wm + 1
            root().use { it.update("UPDATE admin_operator_cancel_command SET pinned_target = CAST(? AS JSON) WHERE approval_id = ?", original, broken.id) }
            reconciliation.reconcile().result shouldBe "OK"
            watermark() shouldBe wm + 3
            findings(broken.id) shouldBe listOf("DECISION_FOR_UNAPPROVED|CONTENT_MATCH")
            lastRunRecent() shouldBe true
            statuses(broken.id) shouldBe listOf("PENDING")
        }
    }

    Given("S7 review L-4: last_run_at only on a complete success") {
        Then("a page succeeds and then PAYMENT fails: the watermark keeps the progress, last_run_at is kept and the lag counts from it; a complete success sets it and the lag is 0") {
            val wm = startAt()
            oldLastRun()
            StubPaymentOpsServer.decisionList(wm, page(listOf(orphan(wm + 1, "apr_01JC0000000000000000LRA001")), wm + 1))
            StubPaymentOpsServer.decisionList(wm + 1, unavailable)
            reconciliation.reconcile().result shouldBe "PAYMENT_UNAVAILABLE"
            watermark() shouldBe wm + 1
            lastRunAt() shouldBe "2026-01-01 00:00:00.000"
            (lag() > 86_400.0) shouldBe true
            StubPaymentOpsServer.decisionList(wm + 1, page(emptyList(), wm + 1))
            reconciliation.reconcile().result shouldBe "OK"
            lastRunRecent() shouldBe true
            lag() shouldBe 0.0
        }
    }

    Given("S7 review L-A: no complete run yet (last_run_at NULL)") {
        Then("the lag counts from the application start instead of reporting 0") {
            val wm = startAt()
            root().use { it.update("UPDATE admin_ops_reconciliation_checkpoint SET last_run_at = NULL WHERE id = 1") }
            StubPaymentOpsServer.decisionList(wm, unavailable)
            Thread.sleep(1_100)
            reconciliation.reconcile().result shouldBe "PAYMENT_UNAVAILABLE"
            (lag() >= 1.0) shouldBe true
            lastRunAt() shouldBe "-"
        }
    }

    Given("S7 review L-C: a full scan with an item error") {
        Then("last_full_scan_at is kept (the full scan runs again next time); after the repair the full scan completes and is marked") {
            val broken = seed(null)
            val original = root().use { it.scalar("SELECT CAST(pinned_target AS CHAR) FROM admin_operator_cancel_command WHERE approval_id = ?", broken.id) as String }
            root().use { it.update("UPDATE admin_operator_cancel_command SET pinned_target = JSON_OBJECT('paymentId', 1) WHERE approval_id = ?", broken.id) }
            val wm = startAt(fullScanDue = true)
            fun fullScanAgeSeconds() =
                root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, last_full_scan_at, UTC_TIMESTAMP(3)) FROM admin_ops_reconciliation_checkpoint") as Number).toLong() }
            val brokenItem = StubPaymentOpsServer.listItem(
                wm - 300, broken.id, broken.paymentId, broken.requester, "adm_01JC00000000000000000000A1", "oct1:" + "d".repeat(64), cancellationId = "pcn_01JC000000000000000000KC01",
            )
            StubPaymentOpsServer.decisionList(0L, page(listOf(brokenItem), wm - 300))
            reconciliation.reconcile().result shouldBe "ITEM_ERROR"
            (fullScanAgeSeconds() > 86_000) shouldBe true
            root().use { it.update("UPDATE admin_operator_cancel_command SET pinned_target = CAST(? AS JSON) WHERE approval_id = ?", original, broken.id) }
            reconciliation.reconcile().fullScan shouldBe true
            (fullScanAgeSeconds() < 60) shouldBe true
            findings(broken.id) shouldBe listOf("DECISION_FOR_UNAPPROVED|CONTENT_MATCH")
            watermark() shouldBe wm
        }
    }

    Given("S8 (I-118): the open findings gauge and code-only detail") {
        Then("after a run paycore.admin.ops.recon.open_findings{kind} equals the recorded findings per kind, and every detail is column names or codes only") {
            val wm = startAt()
            StubPaymentOpsServer.decisionList(wm, page(listOf(orphan(wm + 1, "apr_01JC0000000000000000GAG001")), wm + 1))
            reconciliation.reconcile().result shouldBe "OK"
            val counts = root().use { c -> c.column("SELECT CONCAT(kind, '|', COUNT(*)) FROM admin_ops_reconciliation_finding GROUP BY kind ORDER BY kind") }
            counts.isEmpty() shouldBe false
            counts.map { it.substringBefore('|') to (registry.find("paycore.admin.ops.recon.open_findings").tag("kind", it.substringBefore('|')).gauge()?.value() ?: -1.0) } shouldBe
                counts.map { it.substringBefore('|') to it.substringAfter('|').toDouble() }
            root().use { it.column("SELECT DISTINCT detail_code FROM admin_ops_reconciliation_finding WHERE detail_code IS NOT NULL") }
                .filterNot { Regex("^[A-Z_]+(,[A-Z_]+)*$").matches(it) } shouldBe emptyList()
        }
    }

    Given("ReconciliationCheckpointRaceSpec: two instances reconcile the same page at the same time") {
        Then("no duplicate finding, both runs succeed, the watermark ends at nextWatermark") {
            val pending = seed(null)
            val wm = startAt()
            StubPaymentOpsServer.decisionList(
                wm,
                StubPaymentOpsServer.Reply(
                    200,
                    StubPaymentOpsServer.listPage(listOf(StubPaymentOpsServer.listItem(wm + 1, pending.id, pending.paymentId, pending.requester, "adm_01JC00000000000000000000A1", "oct1:" + "d".repeat(64), code = "CANCEL_IN_PROGRESS", recordedAt = recent)), wm + 1),
                    delayMillis = 300,
                ),
            )
            val pool = Executors.newFixedThreadPool(2)
            val results = listOf(Callable { reconciliation.reconcile() }, Callable { reconciliation.reconcile() }).map(pool::submit).map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdownNow()
            results.map { it.result } shouldBe listOf("OK", "OK")
            findings(pending.id) shouldBe listOf("DECISION_FOR_UNAPPROVED|CONTENT_MATCH")
            watermark() shouldBe wm + 1
            statuses(pending.id) shouldBe listOf("PENDING")
            checkpoint().endsWith("|1") shouldBe true
        }
    }
})
