package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.attributes
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.created
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.issuedAt
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.session
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.token
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.attributes as paymentAttributes
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.requested
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.CancellationRecoverySchedule
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthorizationAdmission
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationResolution
import com.paycore.payment.payment.domain.AuthorizationStart
import com.paycore.payment.payment.domain.AuthorizationStartCommand
import com.paycore.payment.payment.domain.AuthorizationStartResult
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelCapability
import com.paycore.payment.payment.domain.CancelCapabilityResolution
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelInitiator
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelResolutionOutcome
import com.paycore.payment.payment.domain.CancelStart
import com.paycore.payment.payment.domain.CancelStartCommand
import com.paycore.payment.payment.domain.CancelStartResult
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationResolution
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.IncidentReason
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitBusinessDate
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorCancelTarget
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentPlanPolicy
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.PaymentTransactionType
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.ResolutionOutcome
import com.paycore.payment.payment.domain.ReturnBalance
import com.paycore.payment.payment.domain.ReturnExcess
import com.paycore.payment.payment.domain.ReturnReservationState
import com.paycore.payment.payment.domain.ReturnStatus
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import com.paycore.payment.payment.domain.UnknownCause
import com.paycore.payment.payment.domain.VerificationOutcome
import com.paycore.payment.payment.domain.VerifiedAuthentication
import com.paycore.testsupport.kotest.shouldBeEqualNullable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDate

/**
 * CANCEL-001 S2 취소 영속(명세 §8): 실제 MySQL 8.4·runtime 계정으로 `PaymentCancellation`(Attempt·관찰·키 상충·CANCEL 거래·사건 수)·`ReturnBalance`(예약·합계 열·revision)
 * 왕복, 업무 키 조회, DB 이중 방어(CHECK·생성 열 UNIQUE·권한 — 명세 §9.1 K10·K11, rev 0.3 m-2·n-3), 취소 거래가 승인 복원·승인 view에 영향 없음(m-2 회귀),
 * 취소 회복 일정 Store(CN-24, rev 0.3 m-1). 취소 시작·결과 반영 Use Case는 S5·S6 — 여기서는 domain 공식 연산(`CancelStart`·`CancellationResolution`)으로 상태를 만들고
 * Port로 TX1·TX2 잠금 순서대로 저장한다(Demo 값, 운영값 아님).
 */
@PaymentDbTest
class CancellationPersistenceSpec(
    private val payments: PaymentRepository,
    private val controls: OrderPaymentControlRepository,
    private val sessions: AuthenticationSessionRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val authorizations: PaymentAuthorizationRepository,
    private val usages: DailyLimitUsageRepository,
    private val cancellations: PaymentCancellationRepository,
    private val balances: ReturnBalanceRepository,
    private val schedules: CancellationRecoveryScheduleStore,
    private val rejections: com.paycore.payment.payment.application.required.OperatorCancelRejectionStore,
    private val views: AuthorizationViewReader,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }

    val at = issuedAt.plusSeconds(60)
    val cancelAt = at.plus(Duration.ofHours(1))
    val admission = AuthorizationAdmission("es1:demo-merchant:7", "pa1:fee=pas_demo/pv_demo;stl=pas_demo2/pv_demo2", "SIM-VAN-A", "sim-van-1", at)
    val planPolicy = PaymentPlanPolicy("plan-demo-1", PersistenceTestData.KRW, setOf(2, 3, 6), null)
    val capability = CancelCapability("demo-cancel-cap-v1", partialCancelLumpSum = setOf(CardType.CREDIT, CardType.CHECK), partialCancelInstallment = setOf(CardType.CREDIT))
    val routeVersion = "demo-route-v1"

    fun krw(minor: Long) = Money(minor, PersistenceTestData.KRW)

    fun authorizationIdOf(p: Payment) = AuthorizationId("auth-" + p.id.value.takeLast(40))
    fun attemptIdOf(p: Payment) = AttemptId("att-" + p.id.value.takeLast(40))
    fun originalTxOf(p: Payment) = PaymentTransactionId("ptx-" + p.id.value.takeLast(40))
    fun ptxOf(p: Payment) = "PTX-" + p.id.value.takeLast(20)

    /** 승인까지 끝난 저장 Payment(10,000 KRW 일시불 신용 — 승인 경로는 AuthorizationPersistenceSpec과 같은 순서의 공식 연산). */
    fun authorized(): Payment {
        val payment = requested(newOrder())
        val ref = AuthenticationRef("aref_" + DbAccess.unique("r").replace("-", "").padEnd(22, 'x').take(22))
        inTx {
            payments.insertNew(payment, paymentAttributes())
            controls.openOrLock(OrderPaymentControl.open(payment), PersistenceTestData.t0)
            val t = token(payment).also { tokens.insert(it) }
            val cs = session(t).also { checkoutSessions.insert(it) }
            val s = created(payment, at = issuedAt.plusSeconds(20))
            var version = sessions.insertNew(s, attributes(cs.id))
            s.start("sat_demo0001")
            version = sessions.update(s, version)
            val evidence = VerifiedAuthentication("demo-evidence-" + payment.id.value, s.binding, CardType.CREDIT, "SIMISS-A", "SIMACQ-A", "SIMAUTH-1", issuedAt.plusSeconds(1200))
            s.verify(evidence, SecretDigest.of(SecretKind.RESULT_REFERENCE, DbAccess.unique("rr")), ref, issuedAt.plusSeconds(40)) shouldBe VerificationOutcome.VERIFIED
            sessions.update(s, version)
        }
        inTx {
            val p = payment
            val control = controls.lock(p.orderKey)!!
            val locked = payments.lock(p.id)!!
            val session = sessions.lock(sessions.findIdByAuthenticationRef(ref)!!)!!
            val usage = usages.lockOrCreate(LimitUsageKey(p.orderKey.environment, p.orderKey.merchantAccountId, PersistenceTestData.KRW, LimitBusinessDate.of(at)), at)
            val policy = TransactionLimitPolicy("tlp_demo", p.orderKey.merchantAccountId, PersistenceTestData.KRW, LimitAmount.Limited(1_000_000), LimitAmount.Limited(5_000_000))
            val result = AuthorizationStart.start(
                AuthorizationStartCommand(
                    control.value, locked.value, PersistenceTestData.intent(), session.value, admission, planPolicy, LimitPolicyResolution.Resolved(policy), usage.value,
                    authorizationIdOf(p), attemptIdOf(p), ProviderRequestRef("SIM-VAN-A", p.orderKey.environment, "sim-acct-01", "AUTH", "ar_" + p.id.value.takeLast(40)), at,
                ),
            ).shouldBeInstanceOf<AuthorizationStartResult.Started>()
            authorizations.insertNew(result.authorization)
            controls.update(control.value, control.version, at)
            payments.update(locked.value, locked.version)
            sessions.update(session.value, session.version)
            usages.update(usage.value, usage.version, at)
        }
        inTx {
            val a = authorizations.lockByPaymentId(payment.id)!!
            a.value.beginDispatch(attemptIdOf(payment), routeVersion)
            authorizations.update(a.value, a.version)
        }
        inTx {
            val control = controls.lock(payment.orderKey)!!
            val locked = payments.lock(payment.id)!!
            val a = authorizations.lockByPaymentId(payment.id)!!
            val usage = usages.lockOrCreate(a.value.limitKey, at)
            val terms = ApprovedTerms(PersistenceTestData.intent().money, PaymentPlan.LumpSum, CardType.CREDIT, "A0001", ptxOf(payment), LocalDate.of(2026, 10, 7), null)
            val o = InstitutionObservation(attemptIdOf(payment), ObservationSource.RESPONSE, "dec-1", InstitutionOutcome.APPROVED, terms, "sha256:demo", at.plusSeconds(5))
            AuthorizationResolution.applyObservation(control.value, locked.value, a.value, usage.value, o, originalTxOf(payment), null, at.plusSeconds(6))
                .shouldBeInstanceOf<ResolutionOutcome.Authorized>()
            controls.update(control.value, control.version, at.plusSeconds(6))
            payments.update(locked.value, locked.version)
            authorizations.update(a.value, a.version)
            usages.update(usage.value, usage.version, at.plusSeconds(6))
        }
        return payment
    }

    fun cancellationIdOf(p: Payment, n: Int) = CancellationId("cnl-$n-" + p.id.value.takeLast(36))
    fun cancelAttemptIdOf(p: Payment, n: Int) = CancelAttemptId("catt-$n-" + p.id.value.takeLast(36))

    /** TX1과 같은 순서(control → payment → authorization → 반환 잔액)로 잠가 읽고 `CancelStart` 결과를 저장한다(취소 행이 예약 FK보다 먼저). */
    fun startCancel(p: Payment, money: Money, n: Int = 1, initiator: CancelInitiator = CancelInitiator.Merchant(MerchantCancelId("mc-$n"))): CancelStartResult = inTx {
        val control = controls.lock(p.orderKey)!!
        val payment = payments.lock(p.id)!!
        val a = authorizations.lockByPaymentId(p.id)!!
        val balance = balances.lockByPaymentId(p.id)
        val original = a.value.attempts.single { it.state == AttemptState.RESOLVED }.providerRequest
        val result = CancelStart.start(
            CancelStartCommand(
                control.value, payment.value, a.value, balance?.value, money, initiator, CancelCapabilityResolution.Resolved(capability),
                cancellationIdOf(p, n), cancelAttemptIdOf(p, n), original.copy(operation = "CANCEL", requestId = "cr_$n" + p.id.value.takeLast(30)), cancelAt,
            ),
        )
        if (result is CancelStartResult.Started) {
            cancellations.insertNew(result.cancellation)
            if (result.balanceOpened) balances.insertNew(result.balance, cancelAt) else balances.update(result.balance, checkNotNull(balance).version, cancelAt)
        }
        result
    }

    fun dispatchCancel(p: Payment, n: Int = 1) = inTx {
        val c = cancellations.lock(cancellationIdOf(p, n))!!
        c.value.beginDispatch(cancelAttemptIdOf(p, n), routeVersion)
        cancellations.update(c.value, c.version)
    }

    fun cancelTerms(p: Payment, money: Money, n: Int = 1, businessDate: LocalDate? = LocalDate.of(2026, 10, 9)) =
        CancelConfirmedTerms(money, ptxOf(p), "PTX-C$n-" + p.id.value.takeLast(16), if (n % 2 == 1) "C000$n" else null, LocalDate.of(2026, 10, 8), businessDate)

    fun cancelObservation(p: Payment, n: Int, outcome: InstitutionOutcome, source: ObservationSource = ObservationSource.RESPONSE, resultId: String = "cdec-$n", terms: CancelConfirmedTerms? = null) =
        CancelObservation(cancelAttemptIdOf(p, n), source, resultId, outcome, terms, "sha256:demo-cancel", cancelAt.plusSeconds(5))

    /** TX2와 같은 순서(control → payment → 반환 잔액 → 취소)로 잠가 결과를 반영·저장한다. */
    fun applyCancel(p: Payment, n: Int, observation: CancelObservation): CancelResolutionOutcome = inTx {
        val control = controls.lock(p.orderKey)!!
        val payment = payments.lock(p.id)!!
        val balance = balances.lockByPaymentId(p.id)!!
        val c = cancellations.lock(cancellationIdOf(p, n))!!
        val outcome = CancellationResolution.applyObservation(
            control.value, payment.value, c.value, balance.value, observation, PaymentTransactionId("ctx-$n-" + p.id.value.takeLast(36)), cancelAt.plusSeconds(6),
        )
        controls.update(control.value, control.version, cancelAt.plusSeconds(6))
        balances.update(balance.value, balance.version, cancelAt.plusSeconds(6))
        cancellations.update(c.value, c.version)
        outcome
    }

    fun same(restored: PaymentCancellation, original: PaymentCancellation) {
        listOf(restored.id, restored.paymentId, restored.authorizationId, restored.originalTransactionId, restored.orderKey, restored.money, restored.scope,
            restored.initiator, restored.originalProviderTransactionId, restored.policyAssignmentVersion, restored.entitlementVersion, restored.capabilityVersion,
            restored.openedAt, restored.status, restored.observations, restored.keyConflictingObservations, restored.transaction, restored.failureEvidence,
            restored.incidentCount) shouldBe
            listOf(original.id, original.paymentId, original.authorizationId, original.originalTransactionId, original.orderKey, original.money, original.scope,
                original.initiator, original.originalProviderTransactionId, original.policyAssignmentVersion, original.entitlementVersion, original.capabilityVersion,
                original.openedAt, original.status, original.observations, original.keyConflictingObservations, original.transaction, original.failureEvidence,
                original.incidentCount)
        restored.attempts.map { listOf(it.id, it.providerRequest, it.preparedAt, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion) } shouldBe
            original.attempts.map { listOf(it.id, it.providerRequest, it.preparedAt, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion) }
    }

    fun sameBalance(restored: ReturnBalance, original: ReturnBalance) {
        listOf(restored.paymentId, restored.authorizationId, restored.originalTransactionId, restored.authorized, restored.reservations, restored.revision) shouldBe
            listOf(original.paymentId, original.authorizationId, original.originalTransactionId, original.authorized, original.reservations, original.revision)
    }

    Given("a cancellation started in TX1 (cancellation OPEN, attempt PREPARED, return reserved — balance opened lazily)") {
        Then("the cancellation and the balance are restored the same; business keys find the cancellation; the balance columns hold the sums") {
            val p = authorized()
            val started = startCancel(p, krw(3_000)).shouldBeInstanceOf<CancelStartResult.Started>()
            val c = inTx { cancellations.lock(started.cancellation.id) }!!
            same(c.value, started.cancellation)
            val b = inTx { balances.lockByPaymentId(p.id) }!!
            sameBalance(b.value, started.balance)
            b.value.reservedMinor shouldBe 3_000L
            inTx { cancellations.findIdByMerchantKey(p.id, MerchantCancelId("mc-1")) } shouldBe started.cancellation.id
            inTx { cancellations.findIdByMerchantKey(p.id, MerchantCancelId("mc-9")) } shouldBe null
            db.withApp { c2 ->
                with(db) {
                    c2.rows("SELECT authorized_minor, completed_minor, reserved_minor, forced_minor, revision FROM return_balance WHERE payment_id = ?", p.id.value).single()
                }
            }.values.map { (it as Number).toLong() } shouldBe listOf(10_000L, 0L, 3_000L, 0L, 1L)
            db.count("SELECT COUNT(*) FROM payment_cancellation WHERE payment_id = ? AND in_flight_payment_id = ?", p.id.value, p.id.value) shouldBe 1
        }
        Then("an operator correction cancellation keeps its two-person approval columns and is found by its approval id") {
            val p = authorized()
            val requester = OperatorId("op-req-1")
            val approver = OperatorId("op-apr-1")
            val approval = OperatorApprovalId("apv-" + p.id.value.takeLast(40))
            val purpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-1")
            val op = CancelInitiator.Operator(requester, approver, approval, "이중 승인 보정(Demo)", purpose,
                OperatorCancelTarget(p.id, krw(4_000), 0, approval, requester, approver, purpose).fingerprint())
            val started = startCancel(p, krw(4_000), initiator = op).shouldBeInstanceOf<CancelStartResult.Started>()
            same(inTx { cancellations.lock(started.cancellation.id) }!!.value, started.cancellation)
            inTx { cancellations.findIdByApprovalId(approval) } shouldBe started.cancellation.id
        }
    }

    Given("results applied in TX2 and stored") {
        Then("dispatch → unknown → approved: states, observations, the CANCEL transaction, CONSUMED with sequence 1 persist; stale versions conflict") {
            val p = authorized()
            startCancel(p, krw(3_000))
            dispatchCancel(p)
            inTx {
                val c = cancellations.lock(cancellationIdOf(p, 1))!!
                val b = balances.lockByPaymentId(p.id)!!
                CancellationResolution.markOutcomeUnknown(c.value, b.value, cancelAttemptIdOf(p, 1), UnknownCause.TIMEOUT) shouldBe CancelResolutionOutcome.BecameUnknown
                cancellations.update(c.value, c.version)
            }
            val before = inTx { cancellations.lock(cancellationIdOf(p, 1)) }!!
            val balanceBefore = inTx { balances.lockByPaymentId(p.id) }!!
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.NOT_FOUND, ObservationSource.INQUIRY, "inq-1")) shouldBe CancelResolutionOutcome.StillUnknown
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, ObservationSource.INQUIRY, "cdec-1", cancelTerms(p, krw(3_000))))
                .shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            val after = inTx { cancellations.lock(cancellationIdOf(p, 1)) }!!
            after.value.status shouldBe CancellationStatus.COMPLETED
            after.value.attempts.single().state shouldBe AttemptState.RESOLVED
            after.value.attempts.single().routeProfileVersion shouldBe routeVersion
            after.value.observations.map { it.outcome } shouldBe listOf(InstitutionOutcome.NOT_FOUND, InstitutionOutcome.APPROVED)
            val txn = after.value.transaction!!
            txn.type shouldBe PaymentTransactionType.CANCEL
            txn.terms shouldBe cancelTerms(p, krw(3_000))
            (after.version > before.version) shouldBe true
            val balance = inTx { balances.lockByPaymentId(p.id) }!!.value
            balance.reservationOf(cancellationIdOf(p, 1))?.state shouldBe ReturnReservationState.CONSUMED
            balance.completionOf(cancellationIdOf(p, 1))?.sequence shouldBe 1
            balance.status shouldBe ReturnStatus.PARTIALLY_CANCELLED
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { cancellations.update(before.value, before.version) } }
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { balances.update(balanceBefore.value, balanceBefore.version, cancelAt) } }
            db.count("SELECT COUNT(*) FROM payment_transaction WHERE payment_id = ? AND type = 'CANCEL' AND cancellation_id = ? AND original_transaction_id = ?",
                p.id.value, cancellationIdOf(p, 1).value, originalTxOf(p).value) shouldBe 1
            db.count("SELECT COUNT(*) FROM payment_cancellation WHERE payment_id = ? AND in_flight_payment_id IS NOT NULL", p.id.value) shouldBe 0
        }
        // rev 0.3 m-2 회귀: CANCEL 거래 행은 승인 복원(승인 거래 1건)과 승인 view에 영향을 주지 않는다.
        Then("a stored CANCEL transaction does not change the authorization restore or the authorization view") {
            val p = authorized()
            val viewBefore = views.find(authorizationIdOf(p))
            val authBefore = inTx { authorizations.lockByPaymentId(p.id) }!!.value
            startCancel(p, krw(10_000))
            dispatchCancel(p)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(10_000)))).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            val authAfter = inTx { authorizations.lockByPaymentId(p.id) }!!.value
            authAfter.transaction shouldBeEqualNullable authBefore.transaction
            authAfter.status shouldBe AuthorizationStatus.AUTHORIZED
            views.find(authorizationIdOf(p)) shouldBeEqualNullable viewBefore
            inTx { balances.lockByPaymentId(p.id) }!!.value.status shouldBe ReturnStatus.FULLY_CANCELLED
        }
        Then("a decline, the late success (FORCED) and a key-conflicting result persist with the incident count and the cancel-result hold (no hold source)") {
            val p = authorized()
            startCancel(p, krw(3_000))
            dispatchCancel(p)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.DECLINED)) shouldBe CancelResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(3_000)))).shouldBeInstanceOf<CancelResolutionOutcome.Incident>()
            val c = inTx { cancellations.lock(cancellationIdOf(p, 1)) }!!.value
            c.status shouldBe CancellationStatus.FAILED
            c.keyConflictingObservations.size shouldBe 1
            c.incidentCount shouldBe 1
            val b = inTx { balances.lockByPaymentId(p.id) }!!.value
            b.reservationOf(cancellationIdOf(p, 1))?.state shouldBe ReturnReservationState.FORCED
            b.reservationOf(cancellationIdOf(p, 1))?.excess shouldBe ReturnExcess.WITHIN
            b.forcedMinor shouldBe 3_000L
            inTx { controls.lock(p.orderKey) }!!.value.hasActiveHold(p.id, IncidentReason.CANCEL_RESULT_CONFLICT) shouldBe true
            db.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CANCEL_RESULT_CONFLICT' AND source_kind IS NULL", p.id.value) shouldBe 1
            db.count("SELECT COUNT(*) FROM cancel_observation WHERE cancellation_id = ? AND variant = 1", cancellationIdOf(p, 1).value) shouldBe 1
        }
        Then("a second cancellation after a completed partial one gets sequence 2 and the balance revision keeps counting business changes") {
            val p = authorized()
            startCancel(p, krw(3_000))
            dispatchCancel(p)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(3_000))))
            startCancel(p, krw(7_000), n = 2).shouldBeInstanceOf<CancelStartResult.Started>()
            dispatchCancel(p, 2)
            applyCancel(p, 2, cancelObservation(p, 2, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(7_000), n = 2)))
                .shouldBeInstanceOf<CancelResolutionOutcome.Completed>().completion.sequence shouldBe 2
            val b = inTx { balances.lockByPaymentId(p.id) }!!.value
            b.status shouldBe ReturnStatus.FULLY_CANCELLED
            b.revision shouldBe 4L
            b.reservations.map { it.cancellationId } shouldBe listOf(cancellationIdOf(p, 1), cancellationIdOf(p, 2))
        }
    }

    // mid-review #1 n-3: CANCEL 거래의 원 거래는 같은 Authorization·같은 Payment의 AUTHORIZATION 거래여야 한다 — FK만으로는 다른 Payment의 거래도 통과하므로 저장 Port가 확인한다.
    Given("n-3 the original transaction of a cancellation") {
        Then("a cancellation whose original transaction is another payment's authorization transaction is refused at insert, nothing is stored") {
            val p = authorized()
            val q = authorized()
            val started = startCancel(p, krw(1_000), n = 7).shouldBeInstanceOf<CancelStartResult.Started>()
            inTx { cancellations.lock(started.cancellation.id) }!!.value.originalTransactionId shouldBe originalTxOf(p)
            val c = started.cancellation
            val foreign = PaymentCancellation.reconstitute(
                CancellationId("cnl-f-" + q.id.value.takeLast(30)), c.paymentId, c.authorizationId, originalTxOf(q), c.orderKey, c.money, c.scope,
                CancelInitiator.Merchant(MerchantCancelId("mc-foreign")), c.originalProviderTransactionId, c.policyAssignmentVersion, c.entitlementVersion,
                c.capabilityVersion, c.openedAt, CancellationStatus.FAILED,
                listOf(com.paycore.payment.payment.domain.CancelAttempt(CancelAttemptId("catt-f-" + q.id.value.takeLast(30)),
                    c.attempts.single().providerRequest.copy(requestId = "cr_foreign" + q.id.value.takeLast(20)), c.openedAt, AttemptState.ABANDONED_BEFORE_DISPATCH,
                    com.paycore.payment.payment.domain.AbandonCause.GATE_REFUSED, null)),
                emptyList(), emptyList(), null, TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED, 0,
            )
            shouldThrow<IllegalStateException> { inTx { cancellations.insertNew(foreign) } }
            db.count("SELECT COUNT(*) FROM payment_cancellation WHERE cancellation_id = ?", foreign.id.value) shouldBe 0
        }
    }

    Given("unique business and in-flight constraints through the Port (CC §9.2, rev 0.3 m-3)") {
        fun clone(of: PaymentCancellation, id: CancellationId, attempt: CancelAttemptId, initiator: CancelInitiator = of.initiator, requestId: String = "cr_clone-" + id.value.takeLast(20)) =
            PaymentCancellation.reconstitute(
                id, of.paymentId, of.authorizationId, of.originalTransactionId, of.orderKey, of.money, of.scope, initiator, of.originalProviderTransactionId,
                of.policyAssignmentVersion, of.entitlementVersion, of.capabilityVersion, of.openedAt, CancellationStatus.OPEN,
                listOf(com.paycore.payment.payment.domain.CancelAttempt(attempt, of.attempts.single().providerRequest.copy(requestId = requestId), of.openedAt, AttemptState.PREPARED, null, null)),
                emptyList(), emptyList(), null, null, 0,
            )
        Then("a second in-flight cancellation for the same payment, the same merchant key and the same approval id are unique conflicts") {
            val p = authorized()
            val started = startCancel(p, krw(3_000)).shouldBeInstanceOf<CancelStartResult.Started>()
            shouldThrow<PersistenceFailure.UniqueConflict> {
                inTx { cancellations.insertNew(clone(started.cancellation, CancellationId("cnl-x-" + p.id.value.takeLast(30)), CancelAttemptId("catt-x-" + p.id.value.takeLast(30)), CancelInitiator.Merchant(MerchantCancelId("mc-other")))) }
            }.constraint shouldBe StoreConstraint.PAYMENT_CANCELLATION_IN_FLIGHT
            // 업무 키(영구)는 첫 취소가 끝난 뒤에도 같은 ID를 막는다 — 진행 중 UNIQUE와 겹치지 않게 먼저 완료한다.
            dispatchCancel(p)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(3_000)))).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            shouldThrow<PersistenceFailure.UniqueConflict> {
                inTx { cancellations.insertNew(clone(started.cancellation, CancellationId("cnl-y-" + p.id.value.takeLast(30)), CancelAttemptId("catt-y-" + p.id.value.takeLast(30)))) }
            }.constraint shouldBe StoreConstraint.PAYMENT_CANCELLATION_MERCHANT_KEY
            // 같은 approvalId를 다른 Payment에 쓰면 UNIQUE(approval_id) — m-3: application은 새 TX에서 재판정해 CONFLICTING_CONTENT로 돌려준다(S5).
            val q = authorized()
            val approval = OperatorApprovalId("apv-dup-" + p.id.value.takeLast(30))
            fun op(target: Payment) = CancelInitiator.Operator(OperatorId("op-a"), OperatorId("op-b"), approval, "보정(Demo)", OperatorCancelPurpose.IncidentOrReconCorrection("case-1"),
                OperatorCancelTarget(target.id, krw(1_000), 0, approval, OperatorId("op-a"), OperatorId("op-b"), OperatorCancelPurpose.IncidentOrReconCorrection("case-1")).fingerprint())
            startCancel(q, krw(1_000), n = 5, initiator = op(q)).shouldBeInstanceOf<CancelStartResult.Started>()
            val r = authorized()
            shouldThrow<PersistenceFailure.UniqueConflict> { startCancel(r, krw(1_000), n = 5, initiator = op(r)) }.constraint shouldBe StoreConstraint.PAYMENT_CANCELLATION_APPROVAL
            db.count("SELECT COUNT(*) FROM payment_cancellation WHERE payment_id = ?", r.id.value) shouldBe 0
            db.count("SELECT COUNT(*) FROM return_balance WHERE payment_id = ?", r.id.value) shouldBe 0
        }
    }

    Given("database double defence bypassing the domain (root connection — K10·K11, rev 0.3 m-2·n-3·n-4)") {
        Then("CHECK and UNIQUE reject impossible rows") {
            val p = authorized()
            startCancel(p, krw(3_000))
            dispatchCancel(p)
            applyCancel(p, 1, cancelObservation(p, 1, InstitutionOutcome.APPROVED, terms = cancelTerms(p, krw(3_000))))
            val pid = p.id.value
            val cid = cancellationIdOf(p, 1).value
            val d = db.databaseName
            val codes = db.withRoot { c ->
                with(db) {
                    mapOf(
                        "balance over authorized" to c.errorCodeOf("UPDATE $d.return_balance SET completed_minor = 8000, reserved_minor = 3000 WHERE payment_id = ?", pid),
                        "balance negative" to c.errorCodeOf("UPDATE $d.return_balance SET forced_minor = -1 WHERE payment_id = ?", pid),
                        "second balance" to c.errorCodeOf(
                            "INSERT INTO $d.return_balance (payment_id, authorization_id, original_transaction_id, authorized_minor, currency, completed_minor, reserved_minor, " +
                                "forced_minor, revision, created_at, updated_at, version) SELECT payment_id, authorization_id, original_transaction_id, authorized_minor, currency, 0, 0, 0, 0, " +
                                "created_at, updated_at, 0 FROM $d.return_balance WHERE payment_id = ?", pid,
                        ),
                        "second cancel transaction" to c.errorCodeOf(
                            "INSERT INTO $d.payment_transaction (transaction_id, type, payment_id, authorization_id, cancellation_id, original_transaction_id, cancel_scope, amount_minor, " +
                                "currency, provider_transaction_id, original_provider_transaction_id, institution_transaction_date, source_attempt_id, source_kind, source_result_id, " +
                                "recorded_by, confirmed_at) SELECT 'ctx-dup', type, payment_id, authorization_id, cancellation_id, original_transaction_id, cancel_scope, amount_minor, " +
                                "currency, 'PTX-DUP', original_provider_transaction_id, institution_transaction_date, source_attempt_id, source_kind, 'other', recorded_by, confirmed_at " +
                                "FROM $d.payment_transaction WHERE cancellation_id = ?", cid,
                        ),
                        "authorization row without plan" to c.errorCodeOf(
                            "INSERT INTO $d.payment_transaction (transaction_id, type, payment_id, authorization_id, amount_minor, currency, provider_transaction_id, " +
                                "institution_transaction_date, source_attempt_id, source_kind, source_result_id, recorded_by, confirmed_at) " +
                                "SELECT 'ptx-null-plan', 'AUTHORIZATION', payment_id, authorization_id, amount_minor, currency, 'PTX-N', institution_transaction_date, source_attempt_id, " +
                                "source_kind, 'n', recorded_by, confirmed_at FROM $d.payment_transaction WHERE cancellation_id = ?", cid,
                        ),
                        // rev 0.3 m-2: 승인 행의 필수 열을 하나씩만 비워도 거부된다(변이 RED Ma가 묶음 행만으로는 잡히지 않음을 보였다).
                        "authorization row plan null" to c.errorCodeOf(
                            "UPDATE $d.payment_transaction SET plan_kind = NULL, installment_months = NULL WHERE payment_id = ? AND type = 'AUTHORIZATION'", pid,
                        ),
                        "authorization row card null" to c.errorCodeOf("UPDATE $d.payment_transaction SET card_type = NULL WHERE payment_id = ? AND type = 'AUTHORIZATION'", pid),
                        "authorization row approval null" to c.errorCodeOf(
                            "UPDATE $d.payment_transaction SET approval_number = NULL WHERE payment_id = ? AND type = 'AUTHORIZATION'", pid,
                        ),
                        "cancel row with plan" to c.errorCodeOf("UPDATE $d.payment_transaction SET plan_kind = 'LUMP_SUM' WHERE cancellation_id = ?", cid),
                        "cancel row without cancellation" to c.errorCodeOf(
                            "INSERT INTO $d.payment_transaction (transaction_id, type, payment_id, authorization_id, amount_minor, currency, provider_transaction_id, " +
                                "institution_transaction_date, source_attempt_id, source_kind, source_result_id, recorded_by, confirmed_at) " +
                                "SELECT 'ctx-orphan', 'CANCEL', payment_id, authorization_id, amount_minor, currency, 'PTX-O', institution_transaction_date, source_attempt_id, " +
                                "source_kind, 'o', recorded_by, confirmed_at FROM $d.payment_transaction WHERE cancellation_id = ?", cid,
                        ),
                        "authorization row with cancel columns" to c.errorCodeOf(
                            "UPDATE $d.payment_transaction SET cancel_scope = 'FULL' WHERE payment_id = ? AND type = 'AUTHORIZATION'", pid,
                        ),
                        "dispatched attempt without version" to c.errorCodeOf("UPDATE $d.cancel_attempt SET route_profile_version = NULL WHERE cancellation_id = ?", cid),
                        "prepared attempt with version" to c.errorCodeOf("UPDATE $d.cancel_attempt SET state = 'PREPARED' WHERE cancellation_id = ?", cid),
                        "open with incident" to c.errorCodeOf("UPDATE $d.payment_cancellation SET status = 'OPEN', incident_count = 1 WHERE cancellation_id = ?", cid),
                        "failed without evidence" to c.errorCodeOf("UPDATE $d.payment_cancellation SET status = 'FAILED' WHERE cancellation_id = ?", cid),
                        "operator same person" to c.errorCodeOf(
                            "UPDATE $d.payment_cancellation SET initiator_kind = 'OPERATOR', merchant_cancel_id = NULL, approval_id = 'apv-x', requested_by = 'op-1', " +
                                "approved_by = 'op-1', operator_reason = 'r', purpose_kind = 'INCIDENT_OR_RECON_CORRECTION', purpose_ref = 'c', approved_target_fingerprint = 'oct1:x' " +
                                "WHERE cancellation_id = ?", cid,
                        ),
                        "consumed without sequence" to c.errorCodeOf("UPDATE $d.return_reservation SET completion_sequence = NULL WHERE cancellation_id = ?", cid),
                        "observation approved without terms" to c.errorCodeOf(
                            "INSERT INTO $d.cancel_observation (cancellation_id, attempt_id, source, source_result_id, variant, outcome, observed_at) " +
                                "VALUES (?, ?, 'NOTIFICATION', 'x', 0, 'APPROVED', NOW(3))", cid, cancelAttemptIdOf(p, 1).value,
                        ),
                    )
                }
            }
            codes shouldBe mapOf(
                "balance over authorized" to 3819, "balance negative" to 3819, "second balance" to 1062, "second cancel transaction" to 1062,
                "authorization row without plan" to 3819, "authorization row plan null" to 3819, "authorization row card null" to 3819,
                "authorization row approval null" to 3819, "cancel row with plan" to 3819, "cancel row without cancellation" to 3819,
                "authorization row with cancel columns" to 3819, "dispatched attempt without version" to 3819, "prepared attempt with version" to 3819,
                "open with incident" to 3819, "failed without evidence" to 3819, "operator same person" to 3819, "consumed without sequence" to 3819,
                "observation approved without terms" to 3819,
            )
            // CANCEL-001 S5(V20 — D-S2-1): CancelPayment 멱등 결과는 취소 참조(성공)·Payment 참조(거절)만, 다른 operation 행에는 취소 참조가 없다.
            fun idem(operation: String, kind: String, code: String?, cancellationId: String?, authorizationId: String? = null) = db.withRoot { c ->
                with(db) {
                    c.errorCodeOf(
                        "INSERT INTO $d.idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id, request_fingerprint, result_kind, " +
                            "result_code, result_payment_id, result_authorization_id, result_cancellation_id, created_at, expires_at, updated_at, version) " +
                            "VALUES ('TEST', ?, ?, ?, 'rr', 'x1:x', ?, ?, ?, ?, ?, '2026-10-07 01:00:00.000', '2026-11-06 01:00:00.000', '2026-10-07 01:00:00.000', 0)",
                        p.orderKey.merchantAccountId.value, operation, DbAccess.unique("k"), kind, code, pid, authorizationId, cancellationId,
                    )
                }
            }
            idem("CancelPayment", "SUCCESS", "ACCEPTED", cid) shouldBe null
            idem("CancelPayment", "SUCCESS", "EXISTING_CANCELLATION", cid) shouldBe null
            idem("CancelPayment", "SUCCESS", "ACCEPTED", null) shouldBe 3819
            idem("CancelPayment", "SUCCESS", "CREATED", cid) shouldBe 3819
            idem("CancelPayment", "REJECTED", "CANCEL_IN_PROGRESS", null) shouldBe null
            idem("CancelPayment", "REJECTED", "CANCEL_IN_PROGRESS", cid) shouldBe 3819
            idem("CancelPayment", "REJECTED", "LIMIT_EXCEEDED", null) shouldBe 3819
            idem("ConfirmPayment", "REJECTED", "LIMIT_EXCEEDED", cid) shouldBe 3819
            idem("CreatePayment", "SUCCESS", "CREATED", cid) shouldBe 3819
            idem("RefundPayment", "SUCCESS", "ACCEPTED", cid) shouldBe 3819
            // 검토 #3 n-1: 결과가 있는 행(SUCCESS·REJECTED)은 result_code가 있어야 한다 — 상속한 CreatePayment·ConfirmPayment 가지 포함.
            val authId = db.withRoot { c -> with(db) { c.scalar("SELECT authorization_id FROM $d.payment_authorization WHERE payment_id = ?", pid) } } as String
            idem("CancelPayment", "SUCCESS", null, cid) shouldBe 3819
            idem("CancelPayment", "REJECTED", null, null) shouldBe 3819
            idem("ConfirmPayment", "SUCCESS", null, null, authId) shouldBe 3819
            idem("ConfirmPayment", "REJECTED", null, null) shouldBe 3819
            idem("CreatePayment", "SUCCESS", null, null) shouldBe 3819
            idem("CreatePayment", "REJECTED", null, null) shouldBe 3819
            // CN-17: 합계 열이 예약 행과 다르면(CHECK는 통과하는 값) 복원이 조용히 고치지 않고 거부한다.
            db.withRoot { c -> with(db) { c.update("UPDATE $d.return_balance SET forced_minor = 1 WHERE payment_id = ?", pid) } } shouldBe 1
            shouldThrow<IllegalStateException> { inTx { balances.lockByPaymentId(p.id) } }
            db.withRoot { c -> with(db) { c.update("UPDATE $d.return_balance SET forced_minor = 0 WHERE payment_id = ?", pid) } } shouldBe 1
            // 진행 중 예약 둘(생성 열 UNIQUE): 다음 취소를 시작해 RESERVED 하나를 둔 뒤 root로 완료 예약을 RESERVED로 되돌리면 1062.
            startCancel(p, krw(1_000), n = 3).shouldBeInstanceOf<CancelStartResult.Started>()
            db.withRoot { c -> with(db) { c.errorCodeOf("UPDATE $d.return_reservation SET state = 'RESERVED', completion_sequence = NULL WHERE cancellation_id = ?", cid) } } shouldBe 1062
        }
    }

    Given("the runtime account (표 단위 권한 — append-only 관찰, 삭제 경로 없음)") {
        Then("cancel observations cannot be updated, deleted or lock-read; cancellation, balance and schedule rows cannot be deleted") {
            db.withApp { c ->
                with(db) {
                    mapOf(
                        "observation update" to c.errorCodeOf("UPDATE cancel_observation SET outcome = 'APPROVED' WHERE attempt_id = 'none'"),
                        "observation delete" to c.errorCodeOf("DELETE FROM cancel_observation WHERE attempt_id = 'none'"),
                        "cancellation delete" to c.errorCodeOf("DELETE FROM payment_cancellation WHERE cancellation_id = 'none'"),
                        "attempt delete" to c.errorCodeOf("DELETE FROM cancel_attempt WHERE attempt_id = 'none'"),
                        "balance delete" to c.errorCodeOf("DELETE FROM return_balance WHERE payment_id = 'none'"),
                        "reservation delete" to c.errorCodeOf("DELETE FROM return_reservation WHERE cancellation_id = 'none'"),
                        "schedule delete" to c.errorCodeOf("DELETE FROM cancellation_recovery_schedule WHERE attempt_id = 'none'"),
                    )
                }
            } shouldBe mapOf(
                "observation update" to 1142, "observation delete" to 1142, "cancellation delete" to 1142, "attempt delete" to 1142, "balance delete" to 1142,
                "reservation delete" to 1142, "schedule delete" to 1142,
            )
            db.withApp { c -> c.prepareStatement("SELECT * FROM cancel_observation WHERE attempt_id = 'none' FOR UPDATE").use { ps -> runCatching { ps.executeQuery().close() } } }
                .exceptionOrNull().let { (it as java.sql.SQLException).errorCode } shouldBe 1142
        }
    }

    Given("the operator command rejection audit (D-CANCEL1-006·008 — append-only, one row per rejection, same TX as the decision, no personal data)") {
        Then("every rejection is a new timestamped row, also when the same approval is rejected again for the same reason; the row has no free text") {
            val p = authorized()
            val approval = OperatorApprovalId("apv-rej-" + p.id.value.takeLast(30))
            val r = com.paycore.payment.payment.application.required.OperatorCancelRejection(approval, p.id, OperatorId("op-a"), OperatorId("op-a"), "OPERATOR_SAME_PERSON", cancelAt)
            val again = r.copy(rejectedAt = cancelAt.plusSeconds(60))
            val other = r.copy(reason = "OPERATOR_TARGET_STALE", approvedBy = OperatorId("op-b"), rejectedAt = cancelAt.plusSeconds(90))
            inTx { rejections.append(r) }
            inTx { rejections.append(again) }
            inTx { rejections.append(other) }
            inTx { rejections.findByApproval(approval) } shouldBe listOf(r, again, other)
            db.withApp { c ->
                with(db) { c.rows("SELECT * FROM operator_cancel_rejection WHERE approval_id = ?", approval.value).first().keys }
            } shouldBe setOf("id", "approval_id", "payment_id", "requested_by", "approved_by", "rejection_reason", "rejected_at")
            // 사유 코드 밖 자유 문구는 3819.
            db.withRoot { c ->
                with(db) {
                    c.errorCodeOf("INSERT INTO ${db.databaseName}.operator_cancel_rejection (approval_id, payment_id, requested_by, approved_by, rejection_reason, rejected_at) " +
                        "VALUES (?, ?, 'op-a', 'op-b', 'free text reason', NOW(3))", approval.value, p.id.value)
                }
            } shouldBe 3819
        }
        Then("the runtime account cannot update or delete a rejection row (1142)") {
            db.withApp { c ->
                with(db) {
                    listOf(
                        c.errorCodeOf("UPDATE operator_cancel_rejection SET rejection_reason = 'X' WHERE approval_id = 'none'"),
                        c.errorCodeOf("DELETE FROM operator_cancel_rejection WHERE approval_id = 'none'"),
                    )
                }
            } shouldBe listOf(1142, 1142)
        }
    }

    Given("the cancellation recovery schedule store (CN-24 — copy of the authorization schedule design)") {
        Then("insert, lock, update and claimDue (SKIP LOCKED) round-trip; identity, closed state and the config version never change") {
            val p = authorized()
            val started = startCancel(p, krw(2_000)).shouldBeInstanceOf<CancelStartResult.Started>()
            val s = CancellationRecoverySchedule(
                attemptId = cancelAttemptIdOf(p, 1), cancellationId = started.cancellation.id, paymentId = p.id, orderKey = p.orderKey, dispatchLeaseUntil = null,
                nextCheckAt = cancelAt.plusSeconds(30), inquiryCount = 0, escalatedAt = null, closedAt = null, recoveryConfigVersion = "demo-cancel-recovery-v1",
            )
            inTx { schedules.insert(s, cancelAt) }
            inTx { schedules.lock(s.attemptId) } shouldBe s
            val leased = s.copy(dispatchLeaseUntil = cancelAt.plusSeconds(10))
            inTx { schedules.update(leased, cancelAt.plusSeconds(1)) }
            inTx { schedules.claimDue(cancelAt.plusSeconds(31), 1000) }.filter { it.attemptId == s.attemptId } shouldBe listOf(leased)
            inTx { schedules.claimDue(cancelAt.plusSeconds(29), 1000) }.none { it.attemptId == s.attemptId } shouldBe true
            shouldThrow<IllegalStateException> { inTx { schedules.update(leased.copy(recoveryConfigVersion = "other"), cancelAt.plusSeconds(2)) } }
            val closed = leased.copy(closedAt = cancelAt.plusSeconds(3))
            inTx { schedules.update(closed, cancelAt.plusSeconds(3)) }
            shouldThrow<IllegalStateException> { inTx { schedules.update(closed.copy(closedAt = null), cancelAt.plusSeconds(4)) } }
            inTx { schedules.claimDue(cancelAt.plusSeconds(3600), 1000) }.none { it.attemptId == s.attemptId } shouldBe true
        }
    }
})
