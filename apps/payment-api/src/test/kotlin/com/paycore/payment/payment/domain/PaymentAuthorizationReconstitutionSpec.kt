package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.apply
import com.paycore.payment.payment.domain.AuthorizationFixture.observation
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * `PaymentAuthorization.reconstitute`(AUTH-CONTRACT-001 §19.2-10, AUTH-001 명세 §4.11-5, CANCEL-CONTRACT-001 §14.1 nit 4): 영속 복원은 공식 전이로 만들 수 있는 상태만
 * 되돌리고 존재할 수 없는 조합은 거부한다(조용히 고치지 않는다). 정상 상태는 Named Fixture 경로로 만든 Authorization을 그대로 복원해 같은 상태가 되는지로 본다.
 */
class PaymentAuthorizationReconstitutionSpec : BehaviorSpec({

    fun restore(
        a: PaymentAuthorization,
        status: AuthorizationStatus = a.status,
        attempts: List<AuthorizationAttempt> = a.attempts,
        observations: List<InstitutionObservation> = a.observations,
        keyConflicting: List<InstitutionObservation> = a.keyConflictingObservations,
        transaction: AuthorizationTransaction? = a.transaction,
        failureEvidence: TerminationEvidence? = a.failureEvidence,
    ): PaymentAuthorization = PaymentAuthorization.reconstitute(
        id = a.id, paymentId = a.paymentId, orderKey = a.orderKey, intent = a.intent, plan = a.plan, authenticatedCardType = a.authenticatedCardType,
        authenticationSessionId = a.authenticationSessionId, admission = a.admission, planPolicyVersion = a.planPolicyVersion,
        limitPolicyVersion = a.limitPolicyVersion, limitKey = a.limitKey, openedAt = a.openedAt, status = status, attempts = attempts,
        observations = observations, keyConflictingObservations = keyConflicting, transaction = transaction, failureEvidence = failureEvidence,
    )

    fun sameState(restored: PaymentAuthorization, original: PaymentAuthorization) {
        restored.status shouldBe original.status
        restored.attempts.map { Triple(it.id, it.state, it.providerRequest) } shouldBe original.attempts.map { Triple(it.id, it.state, it.providerRequest) }
        restored.attempts.map { it.abandonCause to it.unknownCause } shouldBe original.attempts.map { it.abandonCause to it.unknownCause }
        restored.attempts.map { it.routeProfileVersion } shouldBe original.attempts.map { it.routeProfileVersion }
        restored.observations shouldBe original.observations
        restored.keyConflictingObservations shouldBe original.keyConflictingObservations
        restored.transaction shouldBe original.transaction
        restored.failureEvidence shouldBe original.failureEvidence
    }

    Given("authorizations made through the official transitions") {
        val scenarios = mapOf(
            "OPEN with a PREPARED attempt" to { AuthorizationFixture.started().authorization },
            "OPEN with a DISPATCHING attempt" to { AuthorizationFixture.dispatching().authorization },
            "UNKNOWN after a timeout" to { AuthorizationFixture.unknownAfterTimeout().authorization },
            "AUTHORIZED with its transaction" to { AuthorizationFixture.authorized().authorization },
            "FAILED after a confirmed decline" to { AuthorizationFixture.declined().authorization },
            "FAILED before dispatch (fenced)" to {
                AuthorizationFixture.started().also {
                    AuthorizationResolution.abandonBeforeDispatch(it.payment, it.authorization, it.usage, it.attemptId, AbandonCause.FENCED_BY_RECOVERY, startedAt.plusSeconds(1))
                }.authorization
            },
            "FAILED with a key-conflicting late approval" to {
                AuthorizationFixture.declined().also { apply(it, observation(InstitutionOutcome.APPROVED)) }.authorization
            },
            "FAILED with a late approval recorded as a transaction by reconciliation (D-CANCEL-011)" to {
                AuthorizationFixture.declined().also {
                    val late = observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")
                    apply(it, late)
                    it.authorization.recordLateApprovalByReconciliation(
                        AuthorizationTransaction(PaymentTransactionId("demo-ptx-late"), it.authorization.id, it.payment.id, late.approved!!, late.key, startedAt.plusSeconds(300)),
                    )
                }.authorization
            },
        )
        for ((name, make) in scenarios) {
            Then("$name is restored to the same state") {
                val original = make()
                sameState(restore(original), original)
            }
        }
    }

    Given("stored combinations that the transitions cannot produce") {
        // D-AUTH1-007: 버전은 전송 게이트 통과 때만 생긴다. 전송한 Attempt의 버전 없음(NULL)은 V14 이전 행이라 복원한다(회복은 불일치로 본다 — fail-closed).
        Then("a PREPARED attempt with a route profile version, or a blank version, is rejected; a dispatched attempt without one (legacy row) is restored") {
            val open = AuthorizationFixture.started().authorization
            val prepared = open.attempts.single()
            fun copy(of: AuthorizationAttempt, state: AttemptState, version: String?, unknown: UnknownCause? = of.unknownCause) =
                AuthorizationAttempt(of.id, of.providerRequest, of.preparedAt, state, of.abandonCause, unknown, version)
            shouldThrow<IllegalArgumentException> { restore(open, attempts = listOf(copy(prepared, AttemptState.PREPARED, "sim-van-a-demo-1"))) }
            shouldThrow<IllegalArgumentException> { restore(open, attempts = listOf(copy(prepared, AttemptState.DISPATCHING, ""))) }
            val unknown = AuthorizationFixture.unknownAfterTimeout().authorization
            val legacy = restore(unknown, attempts = listOf(copy(unknown.attempts.single(), AttemptState.OUTCOME_UNKNOWN, null)))
            legacy.attempts.single().routeProfileVersion shouldBe null
        }
        Then("AUTHORIZED without a transaction is rejected") {
            val a = AuthorizationFixture.authorized().authorization
            shouldThrow<IllegalArgumentException> { restore(a, transaction = null) }
        }
        Then("OPEN or UNKNOWN with a transaction is rejected") {
            val a = AuthorizationFixture.authorized().authorization
            shouldThrow<IllegalArgumentException> { restore(a, status = AuthorizationStatus.UNKNOWN) }
        }
        Then("FAILED without sufficient evidence, or evidence on a non-FAILED status, is rejected") {
            val failed = AuthorizationFixture.declined().authorization
            shouldThrow<IllegalArgumentException> { restore(failed, failureEvidence = null) }
            shouldThrow<IllegalArgumentException> { restore(failed, failureEvidence = TerminationEvidence.TIMEOUT) }
            val open = AuthorizationFixture.started().authorization
            shouldThrow<IllegalArgumentException> { restore(open, failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE) }
        }
        Then("a transaction whose source observation is not recorded, or whose terms differ from it, is rejected") {
            val a = AuthorizationFixture.authorized().authorization
            shouldThrow<IllegalArgumentException> { restore(a, observations = emptyList()) }
            val tx = a.transaction!!
            shouldThrow<IllegalArgumentException> {
                restore(a, transaction = tx.copy(terms = tx.terms.copy(approvalNumber = "A9999")))
            }
        }
        Then("no attempts, duplicate observation keys, or an observation for an unknown attempt are rejected") {
            // 검토 #1 n-4: 시도 없음은 다른 규칙(AUTHORIZED의 RESOLVED 시도·관찰의 시도 소속)에 가리지 않도록 관찰 없는 OPEN으로 본다.
            val open = AuthorizationFixture.started().authorization
            shouldThrow<IllegalArgumentException> { restore(open, attempts = emptyList()) }
            val a = AuthorizationFixture.authorized().authorization
            shouldThrow<IllegalArgumentException> { restore(a, observations = a.observations + a.observations) }
            val stray = observation(InstitutionOutcome.NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-x", attempt = AttemptId("demo-att-9999"))
            shouldThrow<IllegalArgumentException> { restore(a, observations = a.observations + stray) }
        }
        Then("a key-conflicting observation without a first observation of the same key is rejected") {
            val a = AuthorizationFixture.declined().authorization
            val conflict = observation(InstitutionOutcome.APPROVED, resultId = "never-recorded")
            shouldThrow<IllegalArgumentException> { restore(a, keyConflicting = listOf(conflict)) }
        }
        Then("OPEN whose attempt is already resolved or unknown, and AUTHORIZED without a resolved attempt, are rejected") {
            val unknown = AuthorizationFixture.unknownAfterTimeout().authorization
            shouldThrow<IllegalArgumentException> { restore(unknown, status = AuthorizationStatus.OPEN) }
            val authorized = AuthorizationFixture.authorized().authorization
            val dispatching = AuthorizationFixture.dispatching().authorization
            shouldThrow<IllegalArgumentException> { restore(authorized, attempts = dispatching.attempts) }
        }
    }

    // F-3(D-MS2-005): 기관 계약계정은 내부 식별자 등급 — toString·예외 메시지에 원문이 나오지 않는다.
    Given("the provider request reference holding the institution merchant account") {
        Then("its text form masks the institution account") {
            val ref = AuthorizationFixture.providerRequest
            ref.toString() shouldNotContain ref.institutionAccount
            AuthorizationFixture.started().authorization.attempts.single().toString() shouldNotContain ref.institutionAccount
        }
    }
})
