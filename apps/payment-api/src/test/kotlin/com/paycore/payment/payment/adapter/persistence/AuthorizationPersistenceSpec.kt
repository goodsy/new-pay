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
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.ApprovedTerms
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
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.HoldReleaseFinding
import com.paycore.payment.payment.domain.IncidentHold
import com.paycore.payment.payment.domain.IncidentReason
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitBusinessDate
import com.paycore.payment.payment.domain.LimitExcess
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.LimitReservationState
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.ObservationKey
import com.paycore.payment.payment.domain.ObservationRef
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentPlanPolicy
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.ResolutionOutcome
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.TerminationEvidenceSourceKind
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import com.paycore.payment.payment.domain.UnknownCause
import com.paycore.payment.payment.domain.VerificationOutcome
import com.paycore.payment.payment.domain.VerifiedAuthentication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AUTH-001 S2 Port 구현(JPA)을 실제 MySQL 8.4·runtime 계정으로 시험한다(명세 §7): `PaymentAuthorization` Aggregate 왕복(Attempt·관찰·키 상충 관찰·거래 — append-only,
 * version 조건부 갱신), Payment당 승인 하나(UNIQUE), 일 한도 사용량 insert-if-absent·잠금·예약 상태(FORCED·excess), 인증 세션 귀속 저장과 authenticationRef 조회,
 * control의 hold occurrence·여는 관찰(H-1)·해제 기록 복원과 저장 안 된 해제 거부(n-4), 종료 근거 출처 왕복(m-6). 승인 시작 자체(TX1 Use Case)는 S5 — 여기서는
 * domain 공식 연산(`AuthorizationStart`·`AuthorizationResolution`)으로 상태를 만들고 Port로 저장한다(Demo 값).
 */
@PaymentDbTest
class AuthorizationPersistenceSpec(
    private val payments: PaymentRepository,
    private val controls: OrderPaymentControlRepository,
    private val sessions: AuthenticationSessionRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val authorizations: PaymentAuthorizationRepository,
    private val usages: DailyLimitUsageRepository,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }

    val at = issuedAt.plusSeconds(60)
    val admission = AuthorizationAdmission("es1:demo-merchant:7", "pa1:fee=pas_demo/pv_demo;stl=pas_demo2/pv_demo2", "SIMVANA", "sim-van-1", at)
    val planPolicy = PaymentPlanPolicy("plan-demo-1", PersistenceTestData.KRW, setOf(2, 3, 6), null)

    fun limitPolicy(mid: com.paycore.payment.payment.domain.MerchantAccountId, daily: Long = 5_000_000) =
        TransactionLimitPolicy("tlp_demo", mid, PersistenceTestData.KRW, LimitAmount.Limited(1_000_000), LimitAmount.Limited(daily))

    /** 저장된 REQUESTED Payment + 주문 control + VERIFIED 인증 세션(결제창 토큰·세션 포함). 값은 Payment 고정 입력에서 나온다. */
    class Prepared(val payment: Payment, val sessionRef: AuthenticationRef)

    fun prepared(): Prepared {
        val payment = requested(newOrder())
        val ref = AuthenticationRef("aref_" + DbAccess.unique("r").replace("-", "").padEnd(22, 'x').take(22))
        inTx {
            payments.insertNew(payment, paymentAttributes())
            controls.openOrLock(OrderPaymentControl.open(payment), PersistenceTestData.t0)
            val t = token(payment).also { tokens.insert(it) }
            val cs = session(t).also { checkoutSessions.insert(it) }
            val s = created(payment, at = issuedAt.plusSeconds(20))
            // 저장 Port는 새 세션을 CREATED로만 받는다(AUTHN-001) — 공식 전이를 저장하며 진행한다.
            var version = sessions.insertNew(s, attributes(cs.id))
            s.start("sat_demo0001")
            version = sessions.update(s, version)
            val evidence = VerifiedAuthentication("demo-evidence-" + payment.id.value, s.binding, CardType.CREDIT, "SIMISSA", "SIMACQA", "SIMAUTH1", issuedAt.plusSeconds(1200))
            s.verify(evidence, SecretDigest.of(SecretKind.RESULT_REFERENCE, DbAccess.unique("rr")), ref, issuedAt.plusSeconds(40)) shouldBe VerificationOutcome.VERIFIED
            sessions.update(s, version)
        }
        return Prepared(payment, ref)
    }

    fun authorizationIdOf(p: Payment) = AuthorizationId("auth-" + p.id.value.takeLast(40))
    fun attemptIdOf(p: Payment) = AttemptId("att-" + p.id.value.takeLast(40))

    /** TX1과 같은 순서로 잠가 읽고 domain 승인 시작 후 Port로 저장한다(Use Case는 S5). */
    fun started(prep: Prepared = prepared()): AuthorizationId = inTx {
        val p = prep.payment
        val control = controls.lock(p.orderKey)!!
        val payment = payments.lock(p.id)!!
        val sessionId = sessions.findIdByAuthenticationRef(prep.sessionRef)!!
        val session = sessions.lock(sessionId)!!
        val usage = usages.lockOrCreate(LimitUsageKey(p.orderKey.environment, p.orderKey.merchantAccountId, PersistenceTestData.KRW, LimitBusinessDate.of(at)), at)
        val result = AuthorizationStart.start(
            AuthorizationStartCommand(
                control.value, payment.value, PersistenceTestData.intent(), session.value, admission, planPolicy,
                LimitPolicyResolution.Resolved(limitPolicy(p.orderKey.merchantAccountId)), usage.value, authorizationIdOf(p), attemptIdOf(p),
                ProviderRequestRef("SIMVANA", p.orderKey.environment, "sim-acct-01", "AUTH", "ar_" + p.id.value.takeLast(40)), at,
            ),
        ).shouldBeInstanceOf<AuthorizationStartResult.Started>()
        authorizations.insertNew(result.authorization)
        controls.update(control.value, control.version, at)
        payments.update(payment.value, payment.version)
        sessions.update(session.value, session.version)
        usages.update(usage.value, usage.version, at)
        result.authorization.id
    }

    fun terms(p: Payment, ptx: String = "PTX-" + p.id.value.takeLast(20), approval: String = "A0001", businessDate: LocalDate? = LocalDate.of(2026, 10, 8)) =
        ApprovedTerms(PersistenceTestData.intent().money, PaymentPlan.LumpSum, CardType.CREDIT, approval, ptx, LocalDate.of(2026, 10, 7), institutionBusinessDate = businessDate)

    fun observation(p: Payment, outcome: InstitutionOutcome, source: ObservationSource = ObservationSource.RESPONSE, resultId: String = "dec-1", approved: ApprovedTerms? = null) =
        InstitutionObservation(attemptIdOf(p), source, resultId, outcome, if (outcome == InstitutionOutcome.APPROVED) approved ?: terms(p) else null, "sha256:demo", at.plusSeconds(5))

    /** TX2와 같은 순서(control → payment → authorization → 사용량)로 잠가 관찰을 반영하고 저장한다. */
    fun applyObserved(p: Payment, observation: InstitutionObservation, txId: String = "ptx-" + p.id.value.takeLast(40)): ResolutionOutcome = inTx {
        val control = controls.lock(p.orderKey)!!
        val payment = payments.lock(p.id)!!
        val authorization = authorizations.lockByPaymentId(p.id)!!
        val usage = usages.lockOrCreate(authorization.value.limitKey, at)
        val outcome = AuthorizationResolution.applyObservation(
            control.value, payment.value, authorization.value, usage.value, observation, PaymentTransactionId(txId), limitPolicy(p.orderKey.merchantAccountId), at.plusSeconds(6),
        )
        controls.update(control.value, control.version, at.plusSeconds(6))
        payments.update(payment.value, payment.version)
        authorizations.update(authorization.value, authorization.version)
        usages.update(usage.value, usage.version, at.plusSeconds(6))
        outcome
    }

    fun dispatch(p: Payment) = inTx {
        val a = authorizations.lockByPaymentId(p.id)!!
        a.value.beginDispatch(attemptIdOf(p), com.paycore.payment.payment.domain.AuthorizationFixture.ROUTE_PROFILE_VERSION)
        authorizations.update(a.value, a.version)
    }

    fun sameState(restored: PaymentAuthorization, original: PaymentAuthorization) {
        listOf(restored.id, restored.paymentId, restored.orderKey, restored.intent, restored.plan, restored.authenticatedCardType, restored.authenticationSessionId,
            restored.admission, restored.planPolicyVersion, restored.limitPolicyVersion, restored.limitKey, restored.openedAt, restored.status, restored.transaction,
            restored.failureEvidence, restored.observations, restored.keyConflictingObservations) shouldBe
            listOf(original.id, original.paymentId, original.orderKey, original.intent, original.plan, original.authenticatedCardType, original.authenticationSessionId,
                original.admission, original.planPolicyVersion, original.limitPolicyVersion, original.limitKey, original.openedAt, original.status, original.transaction,
                original.failureEvidence, original.observations, original.keyConflictingObservations)
        restored.attempts.map { listOf(it.id, it.providerRequest, it.preparedAt, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion) } shouldBe
            original.attempts.map { listOf(it.id, it.providerRequest, it.preparedAt, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion) }
    }

    Given("an authorization started in TX1 (Payment AUTHORIZING, Attempt PREPARED, session bound, limit reserved)") {
        Then("it is restored the same by id and by payment, the session is bound and the reservation is stored") {
            val prep = prepared()
            val id = started(prep)
            val byId = inTx { authorizations.lock(id) }!!
            val byPayment = inTx { authorizations.lockByPaymentId(prep.payment.id) }!!
            sameState(byPayment.value, byId.value)
            byId.value.status shouldBe AuthorizationStatus.OPEN
            byId.value.attempts.single().state shouldBe AttemptState.PREPARED
            byId.value.admission shouldBe admission
            inTx { payments.lock(prep.payment.id) }!!.value.status shouldBe PaymentStatus.AUTHORIZING
            inTx { sessions.lock(sessions.findIdByAuthenticationRef(prep.sessionRef)!!) }!!.value.boundAuthorizationId shouldBe id
            val usage = inTx { usages.lockOrCreate(byId.value.limitKey, at) }.value
            usage.reservationOf(id)?.state shouldBe LimitReservationState.RESERVED
            usage.occupiedMinor shouldBe 10_000L
        }
        Then("a second authorization for the same payment is a unique conflict (one logical authorization per payment)") {
            val prep = prepared()
            val id = started(prep)
            val stored = inTx { authorizations.lock(id) }!!.value
            val clone = PaymentAuthorization.reconstitute(
                AuthorizationId(id.value + "-x"), stored.paymentId, stored.orderKey, stored.intent, stored.plan, stored.authenticatedCardType,
                stored.authenticationSessionId, stored.admission, stored.planPolicyVersion, stored.limitPolicyVersion, stored.limitKey, stored.openedAt,
                AuthorizationStatus.OPEN,
                listOf(com.paycore.payment.payment.domain.AuthorizationAttempt(AttemptId(attemptIdOf(prep.payment).value + "-x"),
                    stored.attempts.single().providerRequest.copy(requestId = "ar_other"), at, AttemptState.PREPARED, null, null)),
                emptyList(), emptyList(), null, null,
            )
            shouldThrow<PersistenceFailure.UniqueConflict> { inTx { authorizations.insertNew(clone) } }.constraint shouldBe StoreConstraint.PAYMENT_AUTHORIZATION_PAYMENT
        }
        Then("an unknown authentication reference finds nothing") {
            inTx { sessions.findIdByAuthenticationRef(AuthenticationRef("aref_" + "Z".repeat(22))) } shouldBe null
        }
    }

    Given("results applied in TX2 and stored") {
        Then("dispatch → timeout → approved: attempt states, observations, the transaction and Payment AUTHORIZED persist; the version grows; a stale version conflicts") {
            val prep = prepared()
            val p = prep.payment
            started(prep)
            dispatch(p)
            inTx {
                val payment = payments.lock(p.id)!!
                val a = authorizations.lockByPaymentId(p.id)!!
                AuthorizationResolution.markOutcomeUnknown(payment.value, a.value, attemptIdOf(p), UnknownCause.TIMEOUT)
                payments.update(payment.value, payment.version)
                authorizations.update(a.value, a.version)
            }
            val before = inTx { authorizations.lockByPaymentId(p.id) }!!
            before.value.attempts.single().unknownCause shouldBe UnknownCause.TIMEOUT
            applyObserved(p, observation(p, InstitutionOutcome.NOT_FOUND, ObservationSource.INQUIRY, "inq-1")) shouldBe ResolutionOutcome.StillUnknown
            applyObserved(p, observation(p, InstitutionOutcome.APPROVED, ObservationSource.INQUIRY, "dec-1")).shouldBeInstanceOf<ResolutionOutcome.Authorized>()
            val after = inTx { authorizations.lockByPaymentId(p.id) }!!
            after.value.status shouldBe AuthorizationStatus.AUTHORIZED
            after.value.observations.map { it.outcome } shouldBe listOf(InstitutionOutcome.NOT_FOUND, InstitutionOutcome.APPROVED)
            after.value.transaction!!.terms shouldBeEqual terms(p)
            after.value.attempts.single().state shouldBe AttemptState.RESOLVED
            (after.version > before.version) shouldBe true
            inTx { payments.lock(p.id) }!!.value.status shouldBe PaymentStatus.AUTHORIZED
            inTx { usages.lockOrCreate(after.value.limitKey, at) }.value.reservationOf(after.value.id)?.state shouldBe LimitReservationState.CONSUMED
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { authorizations.update(before.value, before.version) } }
            db.count("SELECT COUNT(*) FROM payment_transaction WHERE payment_id = ?", p.id.value) shouldBe 1
        }
        // 검토 #1 m-2: 기관 영업일(nullable — 시간대·출처 의미는 Q-CALENDAR)은 관찰과 거래에 그대로 저장·복원된다. 값이 없으면 NULL이다.
        Then("the institution business date is stored with the observation and the transaction and restored; an absent one stays NULL") {
            listOf(LocalDate.of(2026, 10, 8), null).forEach { businessDate ->
                val prep = prepared()
                val p = prep.payment
                started(prep)
                dispatch(p)
                applyObserved(p, observation(p, InstitutionOutcome.APPROVED, approved = terms(p, businessDate = businessDate))).shouldBeInstanceOf<ResolutionOutcome.Authorized>()
                val restored = inTx { authorizations.lockByPaymentId(p.id) }!!.value
                restored.observations.single().approved!!.institutionBusinessDate shouldBe businessDate
                restored.transaction!!.terms.institutionBusinessDate shouldBe businessDate
                db.count("SELECT COUNT(*) FROM payment_transaction WHERE payment_id = ? AND institution_business_date <=> ?", p.id.value, businessDate) shouldBe 1
                db.count("SELECT COUNT(*) FROM institution_observation WHERE attempt_id = ? AND institution_business_date <=> ?", attemptIdOf(p).value, businessDate) shouldBe 1
            }
        }
        Then("a key-conflicting observation is stored as a separate variant and restored; a duplicate adds nothing") {
            val prep = prepared()
            val p = prep.payment
            started(prep)
            dispatch(p)
            applyObserved(p, observation(p, InstitutionOutcome.DECLINED, resultId = "dec-9")).shouldBeInstanceOf<ResolutionOutcome.Failed>()
            applyObserved(p, observation(p, InstitutionOutcome.DECLINED, resultId = "dec-9")) shouldBe ResolutionOutcome.NoChange
            applyObserved(p, observation(p, InstitutionOutcome.APPROVED, resultId = "dec-9"), txId = "ptx-unused-" + p.id.value.takeLast(30)) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            val restored = inTx { authorizations.lockByPaymentId(p.id) }!!.value
            restored.observations.map { it.outcome } shouldBe listOf(InstitutionOutcome.DECLINED)
            restored.keyConflictingObservations.map { it.outcome } shouldBe listOf(InstitutionOutcome.APPROVED)
            db.count("SELECT COUNT(*) FROM institution_observation WHERE attempt_id = ?", attemptIdOf(p).value) shouldBe 2
            // H-1·검토 #1 H-1: hold는 그것을 연 관찰(키 + variant)을 기억한다 — 여기서는 같은 키의 상충 증거 variant 1(첫 관찰 DECLINED가 아니다).
            inTx { controls.lock(p.orderKey) }!!.value.activeIncidentHolds.single().sourceObservation shouldBe ObservationRef(ObservationKey(attemptIdOf(p), ObservationSource.RESPONSE, "dec-9"), 1)
            db.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND source_result_id = 'dec-9' AND source_variant = 1", p.id.value) shouldBe 1
            // 늦은 승인(D-AUTH-010): FORCED 점유와 초과 표시가 저장된다.
            inTx { usages.lockOrCreate(restored.limitKey, at) }.value.reservationOf(restored.id).let {
                it?.state shouldBe LimitReservationState.FORCED
                it?.excess shouldBe LimitExcess.WITHIN
            }
        }
        Then("a confirmed decline stores the termination evidence with its observation source (m-6)") {
            val prep = prepared()
            val p = prep.payment
            started(prep)
            dispatch(p)
            applyObserved(p, observation(p, InstitutionOutcome.DECLINED, resultId = "dec-7"))
            val record = inTx { payments.lock(p.id) }!!.value.terminationEvidenceRecords.single()
            record.evidence shouldBe TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
            record.source!!.kind shouldBe TerminationEvidenceSourceKind.OBSERVATION
            record.source.ref shouldBe "${attemptIdOf(p).value}/RESPONSE/dec-7"
            record.source.recordedAt shouldBe at.plusSeconds(6)
        }
        Then("an attempt abandoned before dispatch stores ABANDONED_BEFORE_DISPATCH with its cause") {
            val prep = prepared()
            val p = prep.payment
            started(prep)
            inTx {
                val payment = payments.lock(p.id)!!
                val a = authorizations.lockByPaymentId(p.id)!!
                val usage = usages.lockOrCreate(a.value.limitKey, at)
                AuthorizationResolution.abandonBeforeDispatch(payment.value, a.value, usage.value, attemptIdOf(p), AbandonCause.FENCED_BY_RECOVERY, at.plusSeconds(1))
                payments.update(payment.value, payment.version)
                authorizations.update(a.value, a.version)
                usages.update(usage.value, usage.version, at.plusSeconds(1))
            }
            val restored = inTx { authorizations.lockByPaymentId(p.id) }!!.value
            restored.attempts.single().abandonCause shouldBe AbandonCause.FENCED_BY_RECOVERY
            restored.failureEvidence shouldBe TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
            inTx { usages.lockOrCreate(restored.limitKey, at) }.value.reservationOf(restored.id)?.state shouldBe LimitReservationState.RELEASED
        }
    }

    Given("daily limit usage rows") {
        Then("lockOrCreate creates the row once even when two transactions race for it (insert-if-absent, no unique conflict)") {
            val key = LimitUsageKey(com.paycore.payment.payment.domain.Environment.TEST, newOrder().merchantAccountId, PersistenceTestData.KRW, LocalDate.of(2026, 10, 7))
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results = (1..2).map { pool.submit<DailyLimitUsage> { inTx { usages.lockOrCreate(key, at).value } } }.map { it.get(30, TimeUnit.SECONDS) }
                results.map { it.key } shouldBe listOf(key, key)
            } finally {
                pool.shutdownNow()
            }
            db.count("SELECT COUNT(*) FROM daily_limit_usage WHERE merchant_account_id = ?", key.merchantAccountId.value) shouldBe 1
        }
        Then("a reservation change raises the usage version; a stale usage version conflicts") {
            val prep = prepared()
            val id = started(prep)
            val key = inTx { authorizations.lock(id) }!!.value.limitKey
            val stale = inTx { usages.lockOrCreate(key, at) }
            inTx {
                usages.lockOrCreate(key, at).let { u ->
                    u.value.release(id)
                    (usages.update(u.value, u.version, at.plusSeconds(1)) > u.version) shouldBe true
                }
            }
            stale.value.consume(id)
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { usages.update(stale.value, stale.version, at.plusSeconds(2)) } }
            inTx { usages.lockOrCreate(key, at) }.value.reservationOf(id)?.state shouldBe LimitReservationState.RELEASED
        }
    }

    Given("incident hold releases (written by RECON later — this task only restores them)") {
        Then("a stored release row is restored with its hold; holds keep occurrence and source; a release the store does not have is refused (n-4)") {
            val prep = prepared()
            val p = prep.payment
            started(prep)
            dispatch(p)
            applyObserved(p, observation(p, InstitutionOutcome.DECLINED, resultId = "dec-5"))
            applyObserved(p, observation(p, InstitutionOutcome.APPROVED, ObservationSource.INQUIRY, "late-5"), txId = "ptx-u-" + p.id.value.takeLast(30))
            val holdId = (db.withApp { c -> with(db) { c.scalar("SELECT id FROM incident_hold WHERE payment_id = ?", p.id.value) } } as Number).toLong()
            // RECON 해제 Use Case가 쓸 행을 root로 넣는다(runtime 계정은 INSERT 권한이 없다 — 쓰기 경로 없음).
            db.withRoot { c ->
                with(db) {
                    c.update(
                        "INSERT INTO ${db.databaseName}.incident_hold_release (incident_hold_id, requested_by, approved_by, reconciliation_match_ref, finding, correction_ref, reason, released_at) " +
                            "VALUES (?, 'demo-operator-a', 'demo-operator-b', 'recon-match-demo-1', 'NO_EFFECT_CONFIRMED', NULL, 'demo', ?)",
                        holdId, java.sql.Timestamp.from(at.plusSeconds(600)),
                    )
                }
            }
            val control = inTx { controls.lock(p.orderKey) }!!.value
            control.incidentHolds shouldBe listOf(IncidentHold.reconstitute(p.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null))
            control.incidentHolds.single().sourceObservation shouldBe ObservationRef(ObservationKey(attemptIdOf(p), ObservationSource.INQUIRY, "late-5"), 0)
            control.incidentHoldReleases.single().finding shouldBe HoldReleaseFinding.NO_EFFECT_CONFIRMED
            control.isOnIncidentHold() shouldBe false
            // 저장 안 된 해제를 domain에 더해 저장하려 하면 거부(해제 쓰기 경로·권한 없음).
            val prep2 = prepared()
            val q = prep2.payment
            started(prep2)
            dispatch(q)
            applyObserved(q, observation(q, InstitutionOutcome.DECLINED, resultId = "dec-6"))
            applyObserved(q, observation(q, InstitutionOutcome.APPROVED, ObservationSource.INQUIRY, "late-6"), txId = "ptx-v-" + q.id.value.takeLast(30))
            shouldThrow<IllegalStateException> {
                inTx {
                    val c = controls.lock(q.orderKey)!!
                    c.value.releaseIncidentHold(
                        com.paycore.payment.payment.domain.IncidentHoldRelease(c.value.activeIncidentHolds.single(), com.paycore.payment.payment.domain.OperatorId("demo-operator-a"),
                            com.paycore.payment.payment.domain.OperatorId("demo-operator-b"), "recon-match-demo-1", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "demo", at.plusSeconds(700)),
                    )
                    controls.update(c.value, c.version, at.plusSeconds(700))
                }
            }
            inTx { controls.lock(q.orderKey) }!!.value.isOnIncidentHold() shouldBe true
        }
    }

    // 명세 §7.1: domain 불변식을 DB가 이중으로 지킨다(root 연결로 우회 INSERT를 시도 — 1062 UNIQUE·3819 CHECK).
    Given("the authorization schema constraints (V9·V11)") {
        fun code(sql: String, vararg args: Any?): Int? = db.withRoot { c -> with(db) { c.errorCodeOf(sql, *args) } }
        val d = db.databaseName
        // D-AUTH1-007(V14): 전송 게이트 통과 때 Route 프로파일 버전을 Attempt 행에 고정한다. runtime 계정의 기존 표 단위 UPDATE 권한으로 쓴다(새 권한 없음).
        Then("V14: the dispatch stores the route profile version through the runtime account and restores it; PREPARED keeps NULL; CHECK refuses a version on PREPARED and an empty one") {
            val prep = prepared()
            val p = prep.payment
            val id = started(prep)
            fun stored() = db.withRoot { c -> with(db) { c.scalar("SELECT route_profile_version FROM $d.authorization_attempt WHERE authorization_id = ?", id.value) } }
            stored() shouldBe null
            code("UPDATE $d.authorization_attempt SET route_profile_version = 'sim-van-a-demo-1' WHERE authorization_id = ?", id.value) shouldBe 3819
            dispatch(p)
            stored() shouldBe com.paycore.payment.payment.domain.AuthorizationFixture.ROUTE_PROFILE_VERSION
            inTx { authorizations.lock(id) }!!.value.attempts.single().routeProfileVersion shouldBe com.paycore.payment.payment.domain.AuthorizationFixture.ROUTE_PROFILE_VERSION
            code("UPDATE $d.authorization_attempt SET route_profile_version = '' WHERE authorization_id = ?", id.value) shouldBe 3819
            // V14 이전 행(전송했지만 버전 없음)은 허용하고 복원한다 — 회복이 불일치로 본다.
            code("UPDATE $d.authorization_attempt SET route_profile_version = NULL WHERE authorization_id = ?", id.value) shouldBe null
            inTx { authorizations.lock(id) }!!.value.attempts.single().routeProfileVersion shouldBe null
            db.withRoot { c ->
                with(db) {
                    c.scalar(
                        "SELECT CONCAT_WS('/', COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'authorization_attempt' AND COLUMN_NAME = 'route_profile_version'",
                        d,
                    )
                }
            } shouldBe "varchar(64)/YES/utf8mb4_0900_bin"
        }
        Then("a second in-flight attempt, a second authorization transaction and a duplicate observation key are refused; occurrence 2 of a hold is accepted") {
            val prep = prepared()
            val p = prep.payment
            val id = started(prep)
            code("INSERT INTO $d.authorization_attempt (attempt_id, authorization_id, institution, environment, institution_account, operation, request_id, state, prepared_at, seq, version) " +
                "VALUES ('att-x-${p.id.value.takeLast(30)}', ?, 'SIMVANA', 'TEST', 'sim-acct-01', 'AUTH', 'ar_x_${p.id.value.takeLast(30)}', 'PREPARED', NOW(3), 1, 0)", id.value) shouldBe 1062
            dispatch(p)
            applyObserved(p, observation(p, InstitutionOutcome.APPROVED, resultId = "dec-3"))
            code("INSERT INTO $d.payment_transaction (transaction_id, type, payment_id, authorization_id, amount_minor, currency, plan_kind, installment_months, card_type, " +
                "approval_number, provider_transaction_id, institution_transaction_date, source_attempt_id, source_kind, source_result_id, recorded_by, confirmed_at) " +
                "VALUES ('ptx-x-${p.id.value.takeLast(30)}', 'AUTHORIZATION', ?, ?, 10000, 'KRW', 'LUMP_SUM', NULL, 'CREDIT', 'A9', 'P9', '2026-10-07', ?, 'RESPONSE', 'dec-3', 'RESOLUTION', NOW(3))",
                p.id.value, id.value, attemptIdOf(p).value) shouldBe 1062
            code("INSERT INTO $d.institution_observation (authorization_id, attempt_id, source, source_result_id, variant, outcome, observed_at) VALUES (?, ?, 'RESPONSE', 'dec-3', 0, 'NOT_FOUND', NOW(3))",
                id.value, attemptIdOf(p).value) shouldBe 1062
            code("INSERT INTO $d.incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence, created_at) VALUES (?, ?, ?, ?, 'SUCCESS_AFTER_TERMINATION', 2, NOW(3))",
                p.orderKey.environment.name, p.orderKey.merchantAccountId.value, p.orderKey.merchantOrderId.value, p.id.value) shouldBe null
            code("INSERT INTO $d.incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence, created_at) VALUES (?, ?, ?, ?, 'SUCCESS_AFTER_TERMINATION', 2, NOW(3))",
                p.orderKey.environment.name, p.orderKey.merchantAccountId.value, p.orderKey.merchantOrderId.value, p.id.value) shouldBe 1062
        }
        Then("CHECKs: observation terms only with APPROVED, release by two people with a correction only for DOUBLE, evidence source all-or-nothing, ConfirmPayment result shapes") {
            val prep = prepared()
            val p = prep.payment
            val id = started(prep)
            code("INSERT INTO $d.institution_observation (authorization_id, attempt_id, source, source_result_id, variant, outcome, approval_number, observed_at) " +
                "VALUES (?, ?, 'RESPONSE', 'x1', 0, 'DECLINED', 'A1', NOW(3))", id.value, attemptIdOf(p).value) shouldBe 3819
            code("INSERT INTO $d.incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence, created_at, source_attempt_id) VALUES (?, ?, ?, ?, 'SUCCESS_AFTER_TERMINATION', 1, NOW(3), 'att')",
                p.orderKey.environment.name, p.orderKey.merchantAccountId.value, p.orderKey.merchantOrderId.value, p.id.value) shouldBe 3819
            code("INSERT INTO $d.incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, occurrence, created_at) VALUES (?, ?, ?, ?, 'SUCCESS_AFTER_TERMINATION', 1, NOW(3))",
                p.orderKey.environment.name, p.orderKey.merchantAccountId.value, p.orderKey.merchantOrderId.value, p.id.value) shouldBe null
            val holdId = (db.withRoot { c -> with(db) { c.scalar("SELECT id FROM $d.incident_hold WHERE payment_id = ?", p.id.value) } } as Number).toLong()
            fun release(requestedBy: String, approvedBy: String, finding: String, correction: String?) = code(
                "INSERT INTO $d.incident_hold_release (incident_hold_id, requested_by, approved_by, reconciliation_match_ref, finding, correction_ref, reason, released_at) VALUES (?, ?, ?, 'm', ?, ?, 'r', NOW(3))",
                holdId, requestedBy, approvedBy, finding, correction,
            )
            release("op-a", "op-a", "NO_EFFECT_CONFIRMED", null) shouldBe 3819
            release("op-a", "op-b", "NO_EFFECT_CONFIRMED", "cancel-1") shouldBe 3819
            release("op-a", "op-b", "DOUBLE_APPROVAL_CORRECTED", null) shouldBe 3819
            code("INSERT INTO $d.payment_termination_evidence (payment_id, seq, evidence, source_kind) VALUES (?, 7, 'TIMEOUT', 'OBSERVATION')", p.id.value) shouldBe 3819
            fun idem(kind: String, code: String?, paymentId: String?, authorizationId: String?) = code(
                "INSERT INTO $d.idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id, request_fingerprint, result_kind, result_code, " +
                    "result_payment_id, result_authorization_id, created_at, expires_at, updated_at, version) " +
                    "VALUES ('TEST', ?, 'ConfirmPayment', ?, 'rr', 'c1:x', ?, ?, ?, ?, '2026-10-07 01:00:00.000', '2026-11-06 01:00:00.000', '2026-10-07 01:00:00.000', 0)",
                p.orderKey.merchantAccountId.value, DbAccess.unique("k"), kind, code, paymentId, authorizationId,
            )
            idem("SUCCESS", "ACCEPTED", p.id.value, id.value) shouldBe null
            idem("SUCCESS", "ACCEPTED", p.id.value, null) shouldBe 3819
            idem("SUCCESS", "CREATED", p.id.value, id.value) shouldBe 3819
            idem("REJECTED", "LIMIT_EXCEEDED", p.id.value, null) shouldBe null
            idem("REJECTED", "LIMIT_EXCEEDED", p.id.value, id.value) shouldBe 3819
        }
    }
})
