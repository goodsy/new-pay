package com.paycore.payment.payment.adapter.query

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.SnapshotProbe
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.AuthorizationResultService
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.CancellationResultService
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ConfirmStartService
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.provided.HomeKpiQuery
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationTransaction
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.HomeKpiFigures
import com.paycore.payment.payment.domain.HomeKpiWindow
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentTransactionId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 운영 홈 KPI jOOQ Reader(ADMIN-HOME-KPI-001 §3·§9·§12 K-1)를 실 MySQL 8.4(runtime 계정)·운영 confirm/cancel/결과 반영 경로로 만든 상태에서 시험한다. 상태는 Named Fixture
 * (`ConfirmFixture`·`CancelFixture`)와 공식 Use Case·결과 반영 서비스로만 만든다. 예외 하나: 종료 Payment의 늦은 승인 거래(T-K1-8)는 RECON 기록 Use Case가 아직 없어 기존 internal
 * fixture(`CancellationFixture.lateConfirmedFirstGeneration`)와 같은 모듈 internal 전이 `recordLateApprovalByReconciliation` → 공식 Repository 저장으로 만든다(직접 SQL 없음).
 * 집계는 전역(모든 MID)이라 시험마다 **다른 시험이 쓰지 않는 KST 날짜**(공유 DB의 가장 늦은 확정 날짜 — 최소 2040-01-01 — 다음부터 증가)를 써서 공유 DB의 다른 행과 섞이지 않게 한다. 확정 시각 = 시험 시계(offset).
 * 기대값은 명세 §3에서 직접 적는다. 한계(보고 대상): 시험 Route는 TEST·KRW뿐이라 LIVE·다른 통화의 확정 사실을 공식 경로로 만들 수 없다 — 환경·통화 분리는 SQL 모양·서비스 단위 시험으로만 본다.
 */
@PaymentApiTest
class HomeKpiReaderSpec(
    private val reader: JooqHomeKpiReader,
    private val query: HomeKpiQuery,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val confirm: ConfirmPaymentUseCase,
    private val start: ConfirmStartService,
    private val authResults: AuthorizationResultService,
    private val cancelResults: CancellationResultService,
    private val authorizations: PaymentAuthorizationRepository,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val faults: FaultInjector,
    private val db: DbAccess,
    private val clock: MutablePaymentClock,
    private val probe: SnapshotProbe,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val tx = TransactionTemplate(transactionManager)
    val seoul = ZoneId.of("Asia/Seoul")
    afterEach {
        faults.reset()
        clock.reset()
        probe.disarm()
    }

    fun h(day: Instant, hours: Long): Instant = day.plus(Duration.ofHours(hours))

    fun kpi(asOf: Instant): List<HomeKpiItem> = reader.read(HomeKpiWindow.of(asOf))

    fun row(approved: Long, cancelled: Long, count: Long, environment: String = "TEST", currency: String = "KRW") =
        HomeKpiItem(environment, currency, HomeKpiFigures(BigInteger.valueOf(approved), BigInteger.valueOf(cancelled), count))

    fun q(sql: String, vararg args: Any?) = fixture.scalar(sql, *args)

    // 리뷰 NIT-5(rev 0.3a): 임의 시작 대신 충돌 없는 시작 — 공유 DB에 이미 있는 확정 사실 중 가장 늦은 KST 날짜(최소 2040-01-01, 다른 시험의 T0 = 2026-10-14 + 400~3400일보다 뒤)
    // 다음부터 쓴다. 이전 실행(같은 DB 재사용)·다른 시험의 행과 날짜가 겹치지 않는다. 시험마다 3일 간격(어제 창 D − 1도 앞 시험·기존 행과 겹치지 않게).
    val firstDay: LocalDate by lazy {
        val latest = (q("SELECT CAST(MAX(confirmed_at) AS CHAR) FROM payment_transaction") as String?)
            ?.let { LocalDateTime.parse(it.replace(' ', 'T')).toInstant(ZoneOffset.UTC).atZone(seoul).toLocalDate() }
        if (latest == null || latest.isBefore(LocalDate.of(2040, 1, 1))) LocalDate.of(2040, 1, 1) else latest
    }
    val dayCounter = AtomicLong(0)

    /** 새 KST 날짜 D의 00:00:00.000 순간. */
    fun newDay(): Instant = firstDay.plusDays(dayCounter.addAndGet(3)).atStartOfDay(seoul).toInstant()

    /** 시험 시계 [at]에 운영 confirm 경로로 승인된 Payment(확정 시각 = at). */
    fun authorizedAt(at: Instant, amount: Long = 10_000): CancelFixture.Authorized {
        clock.set(at)
        return fixture.authorized(amount = amount, base = at)
    }

    fun cancelAt(a: CancelFixture.Authorized, at: Instant, amount: Long = a.money.amountMinor) = run {
        clock.set(at)
        cancel.cancel(a.command(money = Money(amount, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>().view
    }

    fun storedConfirmedAt(transactionColumn: String, value: String): List<Instant> = db.withApp { c ->
        with(db) {
            c.rows("SELECT CAST(confirmed_at AS CHAR) AS at FROM payment_transaction WHERE $transactionColumn = ?", value)
                .map { LocalDateTime.parse((it["at"] as String).replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        }
    }

    /** 응답 유실로 UNKNOWN이 된 승인(시험 시계 [at]). */
    fun unknownAuthorizationAt(at: Instant): Pair<ConfirmFixture.Prepared, AuthorizationId> {
        clock.set(at)
        val p = confirmFixture.prepared(base = at)
        van.onAuth(p.order.merchantAccountId.value) { ProgrammableVan.Script(null, Action.Close) }
        confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>().view.status shouldBe AuthorizationViewStatus.UNKNOWN
        return p to AuthorizationId(q("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String)
    }

    fun attemptOf(authorizationId: AuthorizationId) = AttemptId(q("SELECT attempt_id FROM authorization_attempt WHERE authorization_id = ?", authorizationId.value) as String)

    fun cancelAttemptOf(cancellationId: CancellationId) = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", cancellationId.value) as String)

    fun originalProviderTx(a: CancelFixture.Authorized) =
        q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String

    fun cancelApproval(c: CancellationId, a: CancelFixture.Authorized, amount: Long, ptx: String, at: Instant) = CancelObservation(
        cancelAttemptOf(c), ObservationSource.INQUIRY, "vd_KPI_$ptx", InstitutionOutcome.APPROVED,
        CancelConfirmedTerms(Money(amount, a.money.currency), originalProviderTx(a), ptx, "40000077", LocalDate.ofInstant(at, seoul), LocalDate.ofInstant(at, seoul)),
        null, at,
    )

    fun checkValues(constraint: String): Set<String> = db.withRoot { c ->
        with(db) {
            val clause = c.scalar(
                "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_NAME = ?", db.databaseName, constraint,
            ) as String
            // information_schema는 문자열 상수를 `_utf8mb4\'VALUE\'`처럼 escape해 보여 준다.
            withClue(clause) { Regex("\\\\?'([A-Z_]+)\\\\?'").findAll(clause).map { it.groupValues[1] }.toSet() }
        }
    }

    Given("T-K1-1 approvals confirmed at KST yesterday 23:59:59.999, today 00:00:00.000 and exactly at asOf") {
        Then("[from, asOf): the first and the last are outside, the midnight one is inside; confirmed_at is the PG confirmation instant (the test clock)") {
            val d = newDay()
            val asOf = h(d, 10)
            val yesterday = authorizedAt(d.minusMillis(1), amount = 1_000)
            val midnight = authorizedAt(d, amount = 20_000)
            val atAsOf = authorizedAt(asOf, amount = 300)
            storedConfirmedAt("authorization_id", yesterday.authorizationId) shouldBe listOf(d.minusMillis(1))
            storedConfirmedAt("authorization_id", midnight.authorizationId) shouldBe listOf(d)
            storedConfirmedAt("authorization_id", atAsOf.authorizationId) shouldBe listOf(asOf)
            kpi(asOf) shouldBe listOf(row(20_000, 0, 1))
            // 경계를 1ms 옮기면 각 사실이 들어온다(같은 데이터 — 경계 조건이 실제로 판정한다).
            kpi(asOf.plusMillis(1)) shouldBe listOf(row(20_300, 0, 2))
            kpi(d.plusMillis(1)) shouldBe listOf(row(20_000, 0, 1))
            // 어제 창 [D−1일, D): 23:59:59.999 승인은 asOf = D에서 들어가고 asOf = D − 1ms에서는 빠진다.
            kpi(d.minusMillis(1)).shouldBeEmpty()
        }
    }

    Given("T-K1-2 the query service asked exactly at the KST midnight") {
        Then("the snapshot is the new day with empty items even though yesterday has facts; one millisecond later it is the same day with its facts") {
            val d = newDay()
            authorizedAt(d.minus(Duration.ofHours(1)))
            clock.set(d)
            query.today().let {
                it.asOf shouldBe d
                it.from shouldBe d
                it.businessDate shouldBe LocalDate.ofInstant(d, seoul)
                it.timeZone shouldBe "Asia/Seoul"
                it.items.shouldBeEmpty()
            }
            clock.set(d.minusMillis(1))
            query.today().let {
                it.from shouldBe d.minus(Duration.ofDays(1))
                it.items shouldBe listOf(row(10_000, 0, 1))
            }
        }
    }

    Given("T-K1-3 a partial cancel today of today's approval") {
        Then("approved 10,000, cancelled 3,000, net 7,000, count 1") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            cancelAt(a, h(d, 2), 3_000).status shouldBe CancellationViewStatus.COMPLETED
            kpi(h(d, 10)).single().let {
                it shouldBe row(10_000, 3_000, 1)
                it.figures.netApprovedMinor shouldBe BigInteger.valueOf(7_000)
            }
        }
    }

    Given("T-K1-4 full cancels today: one FULL cancel, and two PARTIAL cancels adding up to the approval") {
        Then("net 0 and count 0 for both payments") {
            val d = newDay()
            val full = authorizedAt(h(d, 1))
            cancelAt(full, h(d, 2)).status shouldBe CancellationViewStatus.COMPLETED
            val parts = authorizedAt(h(d, 3))
            cancelAt(parts, h(d, 4), 3_000)
            cancelAt(parts, h(d, 5), 7_000).status shouldBe CancellationViewStatus.COMPLETED
            q("SELECT GROUP_CONCAT(cancel_scope ORDER BY confirmed_at) FROM payment_transaction WHERE payment_id = ? AND type = 'CANCEL'", parts.paymentId.value) shouldBe
                "PARTIAL,PARTIAL"
            kpi(h(d, 10)).single().let {
                it shouldBe row(20_000, 20_000, 0)
                it.figures.netApprovedMinor shouldBe BigInteger.ZERO
            }
            // 두 번째 부분취소 전 asOf: 아직 전액취소가 아니다(누적 3,000 < 10,000).
            kpi(h(d, 5)) shouldBe listOf(row(20_000, 13_000, 1))
        }
    }

    Given("T-K1-5 yesterday's approval cancelled today") {
        Then("approved 0, cancelled 5,000, net −5,000, count 0 (the row exists because a CANCEL fact is in the window)") {
            val d = newDay()
            val a = authorizedAt(d.minus(Duration.ofHours(2)), amount = 5_000)
            cancelAt(a, h(d, 1))
            kpi(h(d, 10)).single().let {
                it shouldBe row(0, 5_000, 0)
                it.figures.netApprovedMinor shouldBe BigInteger.valueOf(-5_000)
            }
            // 어제 창(asOf = D − 1ms): 승인만, 취소는 asOf 이후라 전액 판정에 안 들어간다.
            kpi(d.minusMillis(1)) shouldBe listOf(row(5_000, 0, 1))
        }
    }

    Given("T-K1-6 unconfirmed facts today: REQUESTED, AUTHORIZING, UNKNOWN and FAILED payments; FAILED and UNKNOWN cancels") {
        Then("only the one authorized payment counts; none of the others changes the sums or the count") {
            val d = newDay()
            val t = h(d, 1)
            val control = authorizedAt(t)
            clock.set(t)
            confirmFixture.prepared(base = t) // REQUESTED
            val authorizing = confirmFixture.prepared(base = t)
            start.start(authorizing.command())
            confirmFixture.paymentStatus(authorizing) shouldBe "AUTHORIZING"
            val (unknown, _) = unknownAuthorizationAt(t)
            confirmFixture.paymentStatus(unknown) shouldBe "UNKNOWN"
            clock.set(t)
            val failed = confirmFixture.prepared(base = t)
            van.onAuth(failed.order.merchantAccountId.value) { van.decline(it) }
            confirm.confirm(failed.command())
            confirmFixture.paymentStatus(failed) shouldBe "FAILED"
            van.onCancel(control.mid.value) { van.declineCancel(it) }
            cancelAt(control, h(d, 2), 2_000).status shouldBe CancellationViewStatus.FAILED
            van.onCancel(control.mid.value) { ProgrammableVan.Script(null, Action.Close) }
            cancelAt(control, h(d, 3), 3_000).status shouldBe CancellationViewStatus.UNKNOWN
            q("SELECT COUNT(*) FROM payment_transaction WHERE payment_id = ? AND type = 'CANCEL'", control.paymentId.value).toString() shouldBe "0"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
        }
    }

    // 리뷰 MINOR-1(rev 0.3a): 취소 OPEN. 공식 취소 경로 + 기존 FaultInjector(CancelFlowSpec "a TX2 failure after the VAN approved (§9.2)"와 같은 주입) — 기관은 전액 취소를 승인했지만
    // TX2가 실패해 취소는 OPEN·Attempt DISPATCHING·반환 예약 RESERVED로 남고 CANCEL 거래가 없다. 직접 SQL 없음.
    Given("T-K1-6 a full cancel left OPEN after the VAN approved it (cancel TX2 failure injected)") {
        Then("no CANCEL fact: the in-flight full cancel is neither subtracted nor part of the full-cancel test — approved 10,000, cancelled 0, count 1") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            val updates = AtomicInteger()
            faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
            val open = cancelAt(a, h(d, 2))
            faults.reset()
            open.status shouldBe CancellationViewStatus.IN_PROGRESS
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", open.cancellationId.value) shouldBe "OPEN"
            q("SELECT state FROM cancel_attempt WHERE cancellation_id = ?", open.cancellationId.value) shouldBe "DISPATCHING"
            q("SELECT state FROM return_reservation WHERE cancellation_id = ?", open.cancellationId.value) shouldBe "RESERVED"
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
            q("SELECT COUNT(*) FROM payment_transaction WHERE payment_id = ? AND type = 'CANCEL'", a.paymentId.value).toString() shouldBe "0"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", open.cancellationId.value) shouldBe "OPEN"
        }
    }

    Given("T-K1-7 a FORCED late success of a declined cancel (D-ADMIN-HOME-002 ②)") {
        Then("FORCED is neither subtracted nor part of the full-cancel test: completed 6,000 + FORCED 4,000 = 10,000 still counts the payment") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            val declined = cancelAt(a, h(d, 2), 4_000).cancellationId
            van.onCancel(a.mid.value) { van.approveCancel(it) }
            cancelAt(a, h(d, 3), 6_000).status shouldBe CancellationViewStatus.COMPLETED
            clock.set(h(d, 4))
            cancelResults.applyObservation(declined, cancelApproval(declined, a, 4_000, "SIMPTX-KPI-F-" + a.paymentId.value.takeLast(8), h(d, 4)))
            q("SELECT state FROM return_reservation WHERE cancellation_id = ?", declined.value) shouldBe "FORCED"
            q("SELECT forced_minor FROM return_balance WHERE payment_id = ?", a.paymentId.value).toString() shouldBe "4000"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 6_000, 1))
        }
    }

    Given("T-K1-8 a late approval recorded on a terminated (FAILED) payment and its operator correction cancel (D-ADMIN-HOME-002 ①), next to an authorized payment") {
        Then("both the late AUTHORIZATION and its correction CANCEL are excluded; T-K1-18 the record path values are fixed") {
            val d = newDay()
            val t = h(d, 1)
            val control = authorizedAt(t)
            clock.set(t)
            val p1 = confirmFixture.prepared(base = t)
            admission.respondFor(p1.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, routeRef = CancelFixture.ROUTE_C) }
            van.onAuth(p1.order.merchantAccountId.value) { van.decline(it) }
            confirm.confirm(p1.command())
            confirmFixture.paymentStatus(p1) shouldBe "FAILED"
            val authorizationId = AuthorizationId(q("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p1.paymentId.value) as String)
            val terms = ApprovedTerms(p1.money, PaymentPlan.LumpSum, CardType.CREDIT, "30000088", "SIMPTX-KPI-L-" + p1.paymentId.value.takeLast(8), LocalDate.ofInstant(t, seoul), null)
            clock.set(h(d, 2))
            val late = InstitutionObservation(attemptOf(authorizationId), ObservationSource.INQUIRY, "vd_KPI_LATE_" + p1.paymentId.value.takeLast(8), InstitutionOutcome.APPROVED, terms, "sha256:demo", h(d, 2))
            authResults.applyObservation(authorizationId, late)
            confirmFixture.paymentStatus(p1) shouldBe "FAILED"
            // RECON 기록(기존 internal fixture 경로 — CancellationFixture.lateConfirmedFirstGeneration과 같은 전이), 공식 Repository 저장.
            tx.executeWithoutResult {
                val v = checkNotNull(authorizations.lock(authorizationId))
                v.value.recordLateApprovalByReconciliation(
                    AuthorizationTransaction(PaymentTransactionId("ptx-kpi-late-" + UUID.randomUUID().toString().take(12)), authorizationId, p1.paymentId, terms, late.key, h(d, 2)),
                )
                authorizations.update(v.value, v.version)
            }
            // 보정 취소 전: 종료 Payment의 늦은 승인은 합계에도 건수에도 없다(건수 문장의 status 조건 — 취소가 없으니 누적 취소 조건으로는 빠지지 않는다).
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
            val p1Authorized = CancelFixture.Authorized(p1, authorizationId.value)
            clock.set(h(d, 3))
            operator.cancel(p1Authorized.operatorCommand(money = p1.money, approvedAt = h(d, 3).minusSeconds(60)))
                .shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>().view.status shouldBe CancellationViewStatus.COMPLETED
            confirmFixture.paymentStatus(p1) shouldBe "FAILED"
            q("SELECT GROUP_CONCAT(CONCAT(type, '|', recorded_by) ORDER BY confirmed_at) FROM payment_transaction WHERE payment_id = ?", p1.paymentId.value) shouldBe
                "AUTHORIZATION|RECONCILIATION,CANCEL|RESOLUTION"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
            // T-K1-18(MINOR-5): 기록 경로 guard — 값 집합과 경로별 값(AUTHORIZED 승인·취소 = RESOLUTION, 종료 Payment 늦은 승인 = RECONCILIATION).
            checkValues("ck_payment_transaction_recorded_by") shouldBe setOf("RESOLUTION", "RECONCILIATION")
            cancelAt(control, h(d, 4), 1_000)
            q("SELECT GROUP_CONCAT(DISTINCT recorded_by) FROM payment_transaction WHERE payment_id = ?", control.paymentId.value) shouldBe "RESOLUTION"
        }
    }

    Given("T-K1-10 a cancel committed by another connection between the sum statement and the count statement") {
        Then("both statements read one read-only REPEATABLE READ snapshot: the cancel is in neither (no torn result); the next read sees it in both") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            var committed: CancellationViewStatus? = null
            probe.arm("`home_kpi_sum`") {
                committed = CompletableFuture.supplyAsync { cancelAt(a, h(d, 2)).status }.get(30, TimeUnit.SECONDS)
            }
            val torn = kpi(h(d, 10))
            probe.observed.get() shouldBe SnapshotProbe.TxState("REPEATABLE-READ", 1)
            committed shouldBe CancellationViewStatus.COMPLETED
            torn shouldBe listOf(row(10_000, 0, 1))
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 10_000, 0))
        }
    }

    Given("T-K1-12 the payment_transaction type CHECK") {
        Then("the value set is exactly {AUTHORIZATION, CANCEL} — a new return type must redefine §3") {
            checkValues("ck_payment_transaction_type_v2") shouldBe setOf("AUTHORIZATION", "CANCEL")
        }
    }

    Given("T-K1-13 a full cancel confirmed after asOf") {
        Then("it is not part of the full-cancel test nor of the cancelled sum at that asOf") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            cancelAt(a, h(d, 11))
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
            kpi(h(d, 11).plusMillis(1)) shouldBe listOf(row(10_000, 10_000, 0))
        }
    }

    Given("T-K1-14(a) the sum statement") {
        Then("the SQL SUM result type is DECIMAL and neither statement (S1 sum, S2 count with its cancelled-sum subquery) casts to BIGINT or SIGNED") {
            val d = newDay()
            authorizedAt(h(d, 1))
            probe.capture()
            kpi(h(d, 10))
            val statements = probe.captured.toList()
            probe.disarm()
            statements.size shouldBe 2
            val sum = statements.single { it.contains("`home_kpi_sum`") }
            // 리뷰 NIT-2(rev 0.3a): CAST 금지 검사를 S2(건수 — subquery의 취소 합)에도 적용한다.
            statements.single { it.contains("`home_kpi_count`") }
            statements.forEach { s -> withClue(s) { Regex("(?i)cast\\s*\\(.*\\b(signed|bigint|unsigned)\\b").containsMatchIn(s) shouldBe false } }
            db.withApp { c ->
                c.createStatement().use { s ->
                    s.executeQuery(sum).use { rs ->
                        val md = rs.metaData
                        val column = (1..md.columnCount).single { md.getColumnLabel(it) == "home_kpi_sum" }
                        md.getColumnTypeName(column) shouldBe "DECIMAL"
                        md.getScale(column) shouldBe 0
                    }
                }
            }
        }
    }

    Given("T-K1-15 an operator correction cancel on an AUTHORIZED payment (MINOR-2a)") {
        Then("its CANCEL fact is subtracted like any completed cancel") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            clock.set(h(d, 2))
            operator.cancel(a.operatorCommand(money = Money(1_000, a.money.currency), approvedAt = h(d, 2).minusSeconds(60)))
                .shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>().view.status shouldBe CancellationViewStatus.COMPLETED
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 1_000, 1))
        }
    }

    Given("T-K1-16 an AUTHORIZED payment with an active CONFLICTING_INSTITUTION_RESULT hold (MINOR-2b)") {
        Then("its approval is included (a hold is an attention item — C-6 — not an exclusion)") {
            val d = newDay()
            val a = authorizedAt(h(d, 1))
            // 공식 결과 반영 경로: 승인 확정 뒤 같은 Attempt에 반대 결과(거절)가 오면 상태는 그대로 두고 CONFLICTING_INSTITUTION_RESULT hold를 연다(D-AUTH-002·008).
            val authorizationId = AuthorizationId(a.authorizationId)
            clock.set(h(d, 2))
            authResults.applyObservation(
                authorizationId,
                InstitutionObservation(attemptOf(authorizationId), ObservationSource.INQUIRY, "vd_KPI_C_" + a.paymentId.value.takeLast(8), InstitutionOutcome.DECLINED, null, "sha256:demo", h(d, 2)),
            )
            confirmFixture.paymentStatus(a.prepared) shouldBe "AUTHORIZED"
            q("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CONFLICTING_INSTITUTION_RESULT'", a.paymentId.value).toString() shouldBe "1"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 0, 1))
        }
    }

    Given("T-K1-17 an approval UNKNOWN since yesterday and a cancel UNKNOWN since yesterday, both confirmed today (MINOR-2c)") {
        Then("each counts on the day it was confirmed: today approved + count, today cancelled; yesterday shows neither") {
            val d = newDay()
            val (p, authorizationId) = unknownAuthorizationAt(d.minus(Duration.ofHours(1)))
            val other = authorizedAt(d.minus(Duration.ofHours(3)))
            van.onCancel(other.mid.value) { ProgrammableVan.Script(null, Action.Close) }
            val pending = cancelAt(other, d.minus(Duration.ofHours(2))).also { it.status shouldBe CancellationViewStatus.UNKNOWN }.cancellationId
            // 어제 창: UNKNOWN 승인은 없고, 다른 Payment 승인만(취소는 미확정 — 전액 판정 밖).
            kpi(d.minusMillis(1)) shouldBe listOf(row(10_000, 0, 1))
            clock.set(h(d, 1))
            val terms = ApprovedTerms(p.money, PaymentPlan.LumpSum, CardType.CREDIT, "30000077", "SIMPTX-KPI-U-" + p.paymentId.value.takeLast(8), LocalDate.ofInstant(h(d, 1), seoul), null)
            authResults.applyObservation(
                authorizationId, InstitutionObservation(attemptOf(authorizationId), ObservationSource.INQUIRY, "vd_KPI_U_" + p.paymentId.value.takeLast(8), InstitutionOutcome.APPROVED, terms, "sha256:demo", h(d, 1)),
            )
            confirmFixture.paymentStatus(p) shouldBe "AUTHORIZED"
            storedConfirmedAt("authorization_id", authorizationId.value) shouldBe listOf(h(d, 1))
            clock.set(h(d, 2))
            cancelResults.applyObservation(pending, cancelApproval(pending, other, 10_000, "SIMPTX-KPI-UC-" + other.paymentId.value.takeLast(8), h(d, 2)))
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", pending.value) shouldBe "COMPLETED"
            kpi(h(d, 10)) shouldBe listOf(row(10_000, 10_000, 1))
            kpi(d.minusMillis(1)) shouldBe listOf(row(10_000, 0, 1))
        }
    }

    // §9 P-1: 두 문장의 실행 계획. PAYMENT-QUERY-PLAN-FLAKE-001: 시험이 스스로 데이터를 만들고 EXPLAIN 직전 읽는 표 전부 ANALYZE TABLE(status OK 단언). 재시도 없음.
    Given("the execution plans of the two statements (P-1)") {
        Then("t uses ix_payment_transaction_confirmed, p PRIMARY (eq_ref), c2 fk_payment_transaction_payment; no full scan, no filesort (Using temporary allowed)") {
            val d = newDay()
            repeat(5) { i -> authorizedAt(h(d, 1L + i)).let { a -> cancelAt(a, h(d, 7L + i), 1_000) } }
            probe.capture()
            kpi(h(d, 20))
            val statements = probe.captured.toList()
            probe.disarm()
            statements.size shouldBe 2
            // 리뷰 MINOR-2(rev 0.3a): 두 문장의 GROUP BY 키가 정확히 (환경, 통화[, 타입])이다. 시험 Route가 TEST·KRW뿐이라(L-7) 결과 값으로는 환경·통화를 섞는 회귀를 잡을 수 없어
            // 실제 실행한 문장(값이 들어간 jOOQ 렌더링)으로 단언한다.
            fun groupBy(sql: String) = sql.substringAfterLast(" group by ", missingDelimiterValue = "<no group by>")
            statements.single { it.contains("`home_kpi_sum`") }.let { withClue(it) { groupBy(it) shouldBe "`p`.`environment`, `t`.`currency`, `t`.`type`" } }
            statements.single { it.contains("`home_kpi_count`") }.let { withClue(it) { groupBy(it) shouldBe "`p`.`environment`, `t`.`currency`" } }
            val planTables = setOf("payment_transaction", "payment")
            val named = statements.flatMap { sql -> Regex("(?:from|join) `(\\w+)`").findAll(sql).map { it.groupValues[1] }.toList() }.toSet()
            withClue("tables named by the reader's statements $statements") { named shouldBe planTables }
            val analyzed = db.withRoot { c -> with(db) { c.rows("ANALYZE TABLE " + planTables.joinToString { "${db.databaseName}.`$it`" }) } }
            withClue("ANALYZE TABLE result $analyzed") {
                analyzed.map { "${it["Table"]} ${it["Msg_type"]} ${it["Msg_text"]}" }.toSet() shouldBe planTables.map { "${db.databaseName}.$it status OK" }.toSet()
            }
            val plans = statements.map { sql -> sql to db.withApp { c -> with(db) { c.rows("EXPLAIN $sql") } } }
            // 증거 기록(보고서 system-out): 문장과 EXPLAIN 행.
            plans.forEach { (sql, plan) -> println("P-1 SQL: $sql"); plan.forEach { println("P-1 PLAN: $it") } }
            plans.forEach { (sql, plan) ->
                withClue("$sql\n$plan") {
                    plan.map { it["type"] }.filter { it == "ALL" }.shouldBeEmpty()
                    plan.filter { (it["Extra"] as String?)?.contains("filesort") == true }.shouldBeEmpty()
                    plan.single { it["table"] == "t" }.let {
                        it["key"] shouldBe "ix_payment_transaction_confirmed"
                        it["type"] shouldBe "range"
                    }
                    plan.single { it["table"] == "p" }.let {
                        it["key"] shouldBe "PRIMARY"
                        it["type"] shouldBe "eq_ref"
                    }
                }
            }
            val count = plans.single { (sql, _) -> sql.contains("`home_kpi_count`") }
            withClue("${count.first}\n${count.second}") {
                count.second.single { it["table"] == "c2" }["key"] shouldBe "fk_payment_transaction_payment"
            }
        }
    }
})
