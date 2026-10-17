package com.paycore.simulator.authentication.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 가상 인증기관 거래(AUTHN-001 §13.2, D-AUTHN-004): 등록 → 고객이 가상 카드로 인증/실패/취소 → 결과 참조 발급 → 서버 간 조회. 결과는 한 번만 확정되고, 조회는
 * 읽기 전용(outcome만 시각 함수 — validUntil 경과 뒤 EXPIRED). 시나리오는 가상 카드 참조로만 정해진다(카드번호 없음, 난수 없음). 값은 Demo.
 */
class AuthenticationTransactionSpec : BehaviorSpec({

    val t0 = Instant.parse("2026-10-16T01:00:00.000Z")
    val ttl = Duration.ofMinutes(10)
    val validity = Duration.ofMinutes(5)

    fun registered(at: Instant = t0) = AuthenticationTransaction.register(
        id = TransactionId("sat_" + "T".repeat(22)), clientId = "pg-demo", request = SimulatorFixture.request(), now = at, ttl = ttl,
    )

    fun completed(card: VirtualCard, at: Instant = t0.plusSeconds(30)) =
        registered().also { it.complete(card, ResultRef("srr_" + "R".repeat(22)), "sim-evidence-0001", at, validity) }

    Given("a registration") {
        Then("it is REGISTERED with the echoed binding and expires after the TTL; only TEST is accepted (double defence §13.3)") {
            val tx = registered()
            tx.status shouldBe TransactionStatus.REGISTERED
            tx.expiresAt shouldBe t0.plus(ttl)
            tx.resultRef shouldBe null
            shouldThrow<SimulatorRejection> {
                AuthenticationTransaction.register(TransactionId("sat_" + "U".repeat(22)), "pg-demo", SimulatorFixture.request(environment = "LIVE"), t0, ttl)
            }.reason shouldBe RejectionReason.ENVIRONMENT_NOT_SUPPORTED
        }
        Then("the same state with the same content is the same registration; different content is a conflict ((client, state) idempotency)") {
            val tx = registered()
            tx.sameRequest(SimulatorFixture.request()) shouldBe true
            tx.sameRequest(SimulatorFixture.request(amountMinor = 10_001)) shouldBe false
        }
    }

    Given("the customer completes with a virtual card") {
        Then("each card produces its scenario result (issuer/acquirer/provider are distinct names)") {
            mapOf(
                VirtualCard.VC_CREDIT_OK to ProviderOutcomeCode.AUTHENTICATED,
                VirtualCard.VC_CHECK_OK to ProviderOutcomeCode.AUTHENTICATED,
                VirtualCard.VC_SPLIT_OK to ProviderOutcomeCode.AUTHENTICATED,
                VirtualCard.VC_AUTH_FAIL to ProviderOutcomeCode.FAILED,
            ).forEach { (card, outcome) ->
                withClue(card) {
                    val result = completed(card).lookup(t0.plusSeconds(60))
                    result.outcome shouldBe outcome
                }
            }
            completed(VirtualCard.VC_SPLIT_OK).lookup(t0.plusSeconds(60)).let { listOf(it.issuerCode, it.acquirerCode, it.providerCode, it.cardType) } shouldBe
                listOf("SIMISS-B", "SIMACQ-A", "SIMAUTH-2", "CREDIT")
            completed(VirtualCard.VC_AUTH_FAIL).lookup(t0.plusSeconds(60)).let { listOf(it.issuerCode, it.evidenceRef, it.authenticatedAt) } shouldBe
                listOf(null, null, null)
        }
        Then("echo-tampering cards return a binding or state different from the registration (PG must reject — R6/R7)") {
            val request = SimulatorFixture.request()
            completed(VirtualCard.VC_WRONG_AMOUNT).lookup(t0.plusSeconds(60)).binding.amountMinor shouldBe request.amountMinor + 1
            completed(VirtualCard.VC_WRONG_MID).lookup(t0.plusSeconds(60)).binding.merchantRef shouldBe request.merchantRef + "-X"
            completed(VirtualCard.VC_STATE_MISMATCH).lookup(t0.plusSeconds(60)).state shouldBe request.state.reversed()
            completed(VirtualCard.VC_OTHER_ISSUER).lookup(t0.plusSeconds(60)).issuerCode shouldBe "SIMISS-B"
            completed(VirtualCard.VC_UNSUPPORTED_TYPE).lookup(t0.plusSeconds(60)).cardType shouldBe "PREPAID"
        }
        Then("the result is fixed once; a second completion or a cancel afterwards is refused") {
            val tx = completed(VirtualCard.VC_CREDIT_OK)
            shouldThrow<SimulatorRejection> { tx.complete(VirtualCard.VC_AUTH_FAIL, ResultRef("srr_" + "S".repeat(22)), "ev-2", t0.plusSeconds(40), validity) }
                .reason shouldBe RejectionReason.ALREADY_COMPLETED
            shouldThrow<SimulatorRejection> { tx.cancel(ResultRef("srr_" + "S".repeat(22)), t0.plusSeconds(40)) }.reason shouldBe RejectionReason.ALREADY_COMPLETED
            tx.lookup(t0.plusSeconds(60)).outcome shouldBe ProviderOutcomeCode.AUTHENTICATED
        }
    }

    Given("cancel, transaction TTL and result validity") {
        Then("cancel yields CANCELLED; completing after the transaction TTL yields EXPIRED") {
            registered().also { it.cancel(ResultRef("srr_" + "C".repeat(22)), t0.plusSeconds(10)) }.lookup(t0.plusSeconds(20)).outcome shouldBe ProviderOutcomeCode.CANCELLED
            registered().also { it.complete(VirtualCard.VC_CREDIT_OK, ResultRef("srr_" + "E".repeat(22)), "ev", t0.plus(ttl), validity) }
                .lookup(t0.plus(ttl).plusSeconds(1)).outcome shouldBe ProviderOutcomeCode.EXPIRED
        }
        Then("an AUTHENTICATED result is EXPIRED at or after validUntil; VC-EXPIRED is already past its validity") {
            val tx = completed(VirtualCard.VC_CREDIT_OK, at = t0.plusSeconds(30))
            tx.lookup(t0.plusSeconds(30).plus(validity).minusMillis(1)).outcome shouldBe ProviderOutcomeCode.AUTHENTICATED
            tx.lookup(t0.plusSeconds(30).plus(validity)).outcome shouldBe ProviderOutcomeCode.EXPIRED
            completed(VirtualCard.VC_EXPIRED).lookup(t0.plusSeconds(30)).outcome shouldBe ProviderOutcomeCode.EXPIRED
        }
    }

    Given("lookup behaviour cards (Recoverability scenarios)") {
        Then("VC-LOOKUP-TIMEOUT-ONCE and VC-LOOKUP-5XX-ONCE misbehave only on the first lookup") {
            VirtualCard.VC_LOOKUP_TIMEOUT_ONCE.lookupBehaviour(previousLookups = 0) shouldBe LookupBehaviour.DELAY
            VirtualCard.VC_LOOKUP_TIMEOUT_ONCE.lookupBehaviour(previousLookups = 1) shouldBe LookupBehaviour.NORMAL
            VirtualCard.VC_LOOKUP_5XX_ONCE.lookupBehaviour(previousLookups = 0) shouldBe LookupBehaviour.SERVER_ERROR
            VirtualCard.VC_LOOKUP_5XX_ONCE.lookupBehaviour(previousLookups = 1) shouldBe LookupBehaviour.NORMAL
            VirtualCard.VC_CREDIT_OK.lookupBehaviour(previousLookups = 0) shouldBe LookupBehaviour.NORMAL
        }
    }

    Given("identifiers") {
        Then("transaction IDs and result references are prefix + 22 base64url characters (128 bit)") {
            shouldThrow<IllegalArgumentException> { TransactionId("sat_short") }
            shouldThrow<IllegalArgumentException> { ResultRef("xrr_" + "A".repeat(22)) }
            VirtualCard.fromRef("VC-CREDIT-OK") shouldBe VirtualCard.VC_CREDIT_OK
            VirtualCard.fromRef("4111111111111111") shouldBe null
        }
    }
})
