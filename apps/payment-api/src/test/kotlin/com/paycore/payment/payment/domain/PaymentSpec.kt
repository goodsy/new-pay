package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.OrderFixture.demoOrder
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import com.paycore.payment.payment.domain.TerminationEvidence.INQUIRY_NOT_FOUND
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT
import com.paycore.payment.payment.domain.TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
import com.paycore.payment.payment.domain.TerminationEvidence.TIMEOUT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Payment 상태와 확정 불변식(활성 규칙에 필요한 최소 전이만. 기관 승인 전이 상세는 AUTH 범위).
 * - 확정 문서 §3: 이미 전송했거나 전송 여부가 불명확한 시도(AUTHORIZING/UNKNOWN)는 시간 경과(timeout·lease 만료·근거 없음)나
 *   조회 NOT_FOUND만으로 EXPIRED/FAILED로 전환하지 않는다 — D-CPD-002 충분 근거가 있을 때만 종료한다.
 * - 오래된 결제창: 만료/종료된 Payment는 승인을 다시 시작할 수 없다(확정 문서 §3, §6-3의 domain 부분).
 * - 확정 거절/만료 뒤 늦은 성공은 종료 상태를 덮어쓰지 않는다(사건 처리는 OrderPaymentControl, §4).
 */
class PaymentSpec : BehaviorSpec({

    Given("a customer opens a payment window for an order") {
        When("the payment is requested through the domain factory") {
            val p = Payment.request(paymentId(1), demoOrder, originalIntent, 1, PaymentFixture.demoWindow)
            Then("it is REQUESTED with no termination evidence and no safe termination") {
                p.status shouldBe REQUESTED
                p.id shouldBe paymentId(1)
                p.orderKey shouldBe demoOrder
                p.intent shouldBe originalIntent
                p.slotGeneration shouldBe 1L
                p.terminationEvidence shouldBe emptyList()
                p.hasSafeTermination() shouldBe false
            }
        }
        When("the slot generation is not positive (invalid input, explicit)") {
            Then("the factory rejects it") {
                shouldThrow<IllegalArgumentException> { Payment.request(paymentId(1), demoOrder, originalIntent, 0, PaymentFixture.demoWindow) }
            }
        }
    }

    Given("a REQUESTED payment") {
        Then("authorization starts, may become UNKNOWN and then AUTHORIZED") {
            val p = PaymentFixture.requested()
            p.startAuthorization(); p.status shouldBe AUTHORIZING
            p.markUnknown(); p.status shouldBe UNKNOWN
            p.markAuthorized(); p.status shouldBe AUTHORIZED
        }
        Then("it cannot be marked UNKNOWN or AUTHORIZED without starting authorization") {
            shouldThrow<InvalidPaymentTransitionException> { PaymentFixture.requested().markUnknown() }
            shouldThrow<InvalidPaymentTransitionException> { PaymentFixture.requested().markAuthorized() }
        }
        Then("since nothing was transmitted it may expire or fail without evidence") {
            PaymentFixture.expiredWithoutEvidence().let {
                it.status shouldBe EXPIRED
                it.terminationEvidence shouldBe emptyList()
                it.hasSafeTermination() shouldBe false
            }
        }
    }

    // AUTH-001 L-3(AUTH-CONTRACT-001 §5.1·§5.5, 명세 AUTH-001 §4.11-1): REQUESTED = 미전송. 미전송 Payment에는 기관 결과·전송 근거가 있을 수 없다.
    // 허용: 근거 없음(FAILED·EXPIRED), EXPIRED + NOT_TRANSMITTED_AND_SESSION_DISCARDED(결제창 만료). 그 밖은 거부 — PRE_TRANSMISSION은 Attempt가 있어야 하므로 AUTHORIZING에서만.
    Given("a REQUESTED payment (not transmitted) closed with evidence") {
        for (target in listOf(FAILED, EXPIRED)) {
            for (evidence in TerminationEvidenceOracle.evidenceOrNone) {
                val allowed = evidence == null || (target == EXPIRED && evidence == TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
                val label = TerminationEvidenceOracle.label(evidence)
                When("it is closed as $target with $label") {
                    Then(if (allowed) "it becomes $target" else "the source of the evidence is impossible for an untransmitted payment: rejected, nothing changes") {
                        val p = PaymentFixture.requested()
                        val close = { if (target == FAILED) p.fail(evidence) else p.expire(evidence) }
                        if (allowed) {
                            close()
                            p.status shouldBe target
                            p.terminationEvidence shouldBe listOfNotNull(evidence)
                        } else {
                            shouldThrow<InvalidPaymentTransitionException> { close() }
                            p.status shouldBe REQUESTED
                            p.terminationEvidence shouldBe emptyList()
                        }
                    }
                }
            }
        }
        Then("a legacy row stored before AUTH-001 (REQUESTED closed with TIMEOUT) is still restorable and is not a safe termination") {
            val legacy = PaymentFixture.legacyStored(FAILED, TIMEOUT)
            legacy.status shouldBe FAILED
            legacy.terminationEvidence shouldBe listOf(TIMEOUT)
            legacy.terminationEvidenceRecords.single().source shouldBe null
            legacy.hasSafeTermination() shouldBe false
        }
    }

    // AUTH-001 rev 0.3 m-6: 새로 기록하는 종료 근거에는 출처 기록(종류·참조·시각·기록자)이 필요하다. 출처가 없는 근거는 AUTH-001 이전 저장 행(legacy)뿐이다.
    Given("a termination evidence source record (m-6)") {
        val at = java.time.Instant.parse("2026-10-15T03:00:05Z")
        fun source(kind: TerminationEvidenceSourceKind) = TerminationEvidenceSource(kind, "demo-ref", at, TerminationEvidenceSource.SYSTEM)
        Then("new evidence without a source is rejected and nothing changes") {
            val p = PaymentFixture.authorizing()
            shouldThrow<IllegalArgumentException> { p.fail(INSTITUTION_CONFIRMED_DECLINE, null) }
            p.status shouldBe AUTHORIZING
            val expired = PaymentFixture.expiredWithoutEvidence()
            shouldThrow<IllegalArgumentException> { expired.addTerminationEvidence(INSTITUTION_CONFIRMED_NO_EFFECT, null) }
            expired.terminationEvidence shouldBe emptyList()
        }
        Then("a source of the wrong kind for the evidence is rejected (decline/no effect ← observation, pre-transmission ← attempt, not transmitted ← checkout expiry)") {
            shouldThrow<IllegalArgumentException> {
                PaymentFixture.authorizing().fail(INSTITUTION_CONFIRMED_DECLINE, source(TerminationEvidenceSourceKind.ATTEMPT_ABANDON))
            }
            shouldThrow<IllegalArgumentException> {
                PaymentFixture.authorizing().fail(PRE_TRANSMISSION_FAILURE_CONFIRMED, source(TerminationEvidenceSourceKind.OBSERVATION))
            }
            shouldThrow<IllegalArgumentException> {
                PaymentFixture.requested().expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED, source(TerminationEvidenceSourceKind.OBSERVATION))
            }
        }
        Then("a source is recorded with the evidence; closing without evidence takes no source") {
            val p = PaymentFixture.authorizing()
            p.fail(INSTITUTION_CONFIRMED_DECLINE, source(TerminationEvidenceSourceKind.OBSERVATION))
            p.terminationEvidenceRecords shouldBe listOf(TerminationEvidenceRecord(INSTITUTION_CONFIRMED_DECLINE, source(TerminationEvidenceSourceKind.OBSERVATION)))
            shouldThrow<IllegalArgumentException> { PaymentFixture.requested().fail(null, source(TerminationEvidenceSourceKind.OBSERVATION)) }
            shouldThrow<IllegalArgumentException> { TerminationEvidenceSource(TerminationEvidenceSourceKind.OBSERVATION, "", at, TerminationEvidenceSource.SYSTEM) }
        }
    }

    // 확정 문서 §3 + D-CPD-002: 전송했거나 불명확한 시도의 종료는 충분 근거가 있을 때만.
    for (from in listOf(AUTHORIZING, UNKNOWN)) {
        Given("a $from payment (transmitted or transmission uncertain)") {
            for (target in listOf(FAILED, EXPIRED)) {
                for (evidence in TerminationEvidenceOracle.evidenceOrNone) {
                    val allowed = TerminationEvidenceOracle.isSufficient(target, evidence)
                    val label = TerminationEvidenceOracle.label(evidence)
                    When("it is closed as $target with $label") {
                        Then(if (allowed) "it becomes $target with a safe termination" else "the transition is rejected and nothing changes") {
                            val p = PaymentFixture.inStatus(from)
                            val close = { if (target == FAILED) p.fail(evidence) else p.expire(evidence) }
                            if (allowed) {
                                close()
                                p.status shouldBe target
                                p.terminationEvidence shouldBe listOfNotNull(evidence)
                                p.hasSafeTermination() shouldBe true
                            } else {
                                shouldThrow<InvalidPaymentTransitionException> { close() }
                                p.status shouldBe from
                                p.terminationEvidence shouldBe emptyList()
                            }
                        }
                    }
                }
            }
            Then("authorization cannot start again") {
                val p = PaymentFixture.inStatus(from)
                shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
                p.status shouldBe from
            }
        }
    }

    Given("an expired payment window (stale screen)") {
        val p = PaymentFixture.expiredUntransmittedWithSessionDiscarded()
        Then("authorization cannot start") {
            shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
            p.status shouldBe EXPIRED
        }
    }

    val finals = mapOf(
        "failed with a confirmed decline" to { PaymentFixture.failedWithConfirmedDecline() },
        "expired with confirmed no effect" to { PaymentFixture.expiredWithConfirmedNoEffect() },
        "authorized" to { PaymentFixture.authorized() },
    )
    for ((name, make) in finals) {
        Given("a payment $name") {
            When("a late result (success, unknown, decline, expiry) arrives for it") {
                Then("every authorization transition is rejected and the status is not overwritten") {
                    val p = make()
                    val before = p.status
                    shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
                    shouldThrow<InvalidPaymentTransitionException> { p.markUnknown() }
                    shouldThrow<InvalidPaymentTransitionException> { p.markAuthorized() }
                    shouldThrow<InvalidPaymentTransitionException> { p.fail(INSTITUTION_CONFIRMED_DECLINE) }
                    shouldThrow<InvalidPaymentTransitionException> { p.expire(INSTITUTION_CONFIRMED_NO_EFFECT) }
                    p.status shouldBe before
                }
            }
        }
    }

    // AUTH-001 검토 #1 m-1: 승인을 시작하지 않은 Payment(REQUESTED에서 근거 없이 종료)는 나중에 덧붙이는 근거도 결제창 만료 근거(미전송·세션 폐기)만 받는다 —
    // 전송한 적 없는 Payment가 기관 확정 거절·무효과 근거로 "안전 종료"가 되는 우회(REQUESTED → 근거 없이 종료 → 근거 추가)를 막는다.
    Given("a payment closed from REQUESTED without evidence (authorization never started — review #1 m-1)") {
        Then("institution, transmission or timeout evidence added later is rejected and the termination stays unsafe") {
            for (evidence in listOf(INSTITUTION_CONFIRMED_DECLINE, INSTITUTION_CONFIRMED_NO_EFFECT, PRE_TRANSMISSION_FAILURE_CONFIRMED, TIMEOUT, INQUIRY_NOT_FOUND)) {
                for (closed in listOf(PaymentFixture.requested().also { it.fail(null) }, PaymentFixture.expiredWithoutEvidence())) {
                    shouldThrow<InvalidPaymentTransitionException> { closed.addTerminationEvidence(evidence) }
                    closed.terminationEvidence shouldBe emptyList()
                    closed.hasSafeTermination() shouldBe false
                }
            }
        }
        Then("checkout expiry evidence (not transmitted, session discarded) is still accepted") {
            val p = PaymentFixture.expiredWithoutEvidence()
            p.addTerminationEvidence(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            p.hasSafeTermination() shouldBe true
        }
        Then("the never-started fact survives persistence restore; a started payment keeps accepting later evidence") {
            PaymentFixture.expiredWithoutEvidence().authorizationStarted shouldBe false
            PaymentFixture.legacyStored(FAILED, INSTITUTION_CONFIRMED_DECLINE).authorizationStarted shouldBe false
            val started = PaymentFixture.failedWithConfirmedDecline()
            started.authorizationStarted shouldBe true
            started.addTerminationEvidence(INQUIRY_NOT_FOUND)
            started.terminationEvidence shouldBe listOf(INSTITUTION_CONFIRMED_DECLINE, INQUIRY_NOT_FOUND)
        }
    }

    Given("an expired payment without evidence") {
        When("evidence is confirmed later") {
            Then("it is appended (append-only) and only a sufficient one makes the termination safe") {
                // 검토 #1 m-1: 승인을 시작하지 않은 종료에 덧붙일 수 있는 근거는 결제창 만료 근거뿐이다 — EXPIRED에는 충분, FAILED에는 불충분(D-CPD-003 교차 불인정).
                val failed = PaymentFixture.requested(n = 2).also { it.fail(null) }
                failed.addTerminationEvidence(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
                failed.hasSafeTermination() shouldBe false
                val p = PaymentFixture.expiredWithoutEvidence()
                p.addTerminationEvidence(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
                p.hasSafeTermination() shouldBe true
                p.terminationEvidence shouldBe listOf(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            }
        }
    }

    Given("a payment that is not FAILED/EXPIRED") {
        Then("termination evidence cannot be added") {
            for (status in listOf(REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED)) {
                val p = PaymentFixture.inStatus(status)
                shouldThrow<InvalidPaymentTransitionException> { p.addTerminationEvidence(INSTITUTION_CONFIRMED_NO_EFFECT) }
                p.terminationEvidence shouldBe emptyList()
            }
        }
    }

    Given("the evidence list exposed by a payment") {
        Then("mutating it does not change the payment") {
            val p = PaymentFixture.failedWithConfirmedDecline()
            @Suppress("UNCHECKED_CAST")
            (p.terminationEvidence as? MutableList<TerminationEvidence>)?.let { runCatching { it.clear() } }
            p.terminationEvidence shouldBe listOf(INSTITUTION_CONFIRMED_DECLINE)
        }
    }
})
