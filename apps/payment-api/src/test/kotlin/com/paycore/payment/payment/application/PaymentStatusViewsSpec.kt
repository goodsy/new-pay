package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.PaymentStatusRowFixture.APPROVED_AT
import com.paycore.payment.payment.application.PaymentStatusRowFixture.TERMS
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.CancelFailureCategory
import com.paycore.payment.payment.application.provided.CancellationInitiator
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.FailureCategory
import com.paycore.payment.payment.application.provided.PaymentStatusInconsistent
import com.paycore.payment.payment.application.provided.PaymentViewStatus
import com.paycore.payment.payment.application.provided.ReturnSummaryView
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.ReturnStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.testsupport.kotest.shouldBeEqualNullable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * 결제 상태 view 변환(CP-QUERY-001 §5.1 표·§4.4·§6.2 CQ-07, D-CPQ-001·002, F-1). 기대값은 명세 표에서 직접 적는다 — 구현 함수로 기대값을 만들지 않는다. 순수 단위 시험(Spring·DB 없음).
 */
class PaymentStatusViewsSpec : BehaviorSpec({

    Given("the Payment summary states (AC1 §5.1 → confirm vocabulary)") {
        Then("REQUESTED and EXPIRED have no authorization, no failure category, no return summary") {
            listOf(PaymentStatusRowFixture.requested() to PaymentViewStatus.REQUESTED, PaymentStatusRowFixture.expired() to PaymentViewStatus.EXPIRED).forEach { (row, expected) ->
                val v = PaymentStatusViews.of(row)
                v.status shouldBe expected
                v.authorization shouldBe null
                v.failureCategory shouldBe null
                v.returnSummary shouldBe null
                v.cancellations.shouldBeEmpty()
                v.onHold shouldBe false
            }
        }
        Then("AUTHORIZING is IN_PROGRESS and UNKNOWN stays UNKNOWN (not merged — CQ-12)") {
            PaymentStatusViews.of(PaymentStatusRowFixture.inProgress()).let {
                it.status shouldBe PaymentViewStatus.IN_PROGRESS
                it.authorization?.status shouldBe AuthorizationViewStatus.IN_PROGRESS
                it.authorization?.approved shouldBe null
            }
            PaymentStatusViews.of(PaymentStatusRowFixture.unknown()).let {
                it.status shouldBe PaymentViewStatus.UNKNOWN
                it.authorization?.status shouldBe AuthorizationViewStatus.UNKNOWN
            }
        }
        Then("FAILED carries the fixed category of its evidence: decline → DECLINED, pre-transmission → NOT_SENT, no-effect → NO_EFFECT") {
            mapOf(
                TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE to FailureCategory.DECLINED,
                TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED to FailureCategory.NOT_SENT,
                TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT to FailureCategory.NO_EFFECT,
            ).forEach { (evidence, category) ->
                val v = PaymentStatusViews.of(PaymentStatusRowFixture.failed(evidence))
                v.status shouldBe PaymentViewStatus.FAILED
                v.failureCategory shouldBe category
                v.authorization?.status shouldBe AuthorizationViewStatus.FAILED
                v.authorization?.failureCategory shouldBe category
                v.authorization?.approved shouldBe null
                v.returnSummary shouldBe null
            }
        }
        Then("AUTHORIZED shows the approved terms of the authorization transaction and, before any cancel, a full cancellable amount") {
            val v = PaymentStatusViews.of(PaymentStatusRowFixture.authorized())
            v.status shouldBe PaymentViewStatus.AUTHORIZED
            v.authorization?.status shouldBe AuthorizationViewStatus.AUTHORIZED
            v.authorization?.approved shouldBeEqualNullable TERMS
            v.authorization?.approvedAt shouldBe APPROVED_AT
            v.failureCategory shouldBe null
            v.returnSummary shouldBe ReturnSummaryView(authorizedMinor = 10_000, cancelledMinor = 0, inProgressMinor = 0, cancellableMinor = 10_000, returnStatus = ReturnStatus.NONE)
            v.cancellations.shouldBeEmpty()
        }
    }

    Given("an authorized Payment with merchant and operator cancels (D-CPQ-002)") {
        val v = PaymentStatusViews.of(PaymentStatusRowFixture.authorizedWithHistory())
        Then("the return summary is completed 4,000 (both initiators), in progress 2,000, cancellable 4,000, partially cancelled") {
            v.returnSummary shouldBe ReturnSummaryView(10_000, cancelledMinor = 4_000, inProgressMinor = 2_000, cancellableMinor = 4_000, returnStatus = ReturnStatus.PARTIALLY_CANCELLED)
        }
        Then("the history lists every cancel in request order; the operator cancel shows only initiator OPERATOR (no merchant cancel id)") {
            v.cancellations.map { it.status } shouldBe listOf(
                CancellationViewStatus.COMPLETED, CancellationViewStatus.COMPLETED, CancellationViewStatus.FAILED, CancellationViewStatus.UNKNOWN,
            )
            v.cancellations.map { it.initiator } shouldBe listOf(
                CancellationInitiator.MERCHANT, CancellationInitiator.OPERATOR, CancellationInitiator.MERCHANT, CancellationInitiator.MERCHANT,
            )
            v.cancellations[1].merchantCancelId shouldBe null
            v.cancellations[0].merchantCancelId?.value shouldBe "c-1"
            v.cancellations[2].failureCategory shouldBe CancelFailureCategory.DECLINED
            v.cancellations[0].cancelTransactionId shouldBe "ptx_01DEMOCANCELTX00000000001"
            v.cancellations[3].cancelTransactionId shouldBe null
        }
        Then("the history order is by request time even if the reader returns another order") {
            val row = PaymentStatusRowFixture.authorizedWithHistory()
            PaymentStatusViews.of(row.copy(cancellations = row.cancellations.reversed())).cancellations.map { it.cancellationId } shouldBe v.cancellations.map { it.cancellationId }
        }
    }

    Given("a FORCED excess after a full return (F-1, D-CANCEL-006)") {
        val v = PaymentStatusViews.of(PaymentStatusRowFixture.authorizedWithForcedExcess())
        Then("the cancellable amount is 0, never negative, the Payment is fully cancelled, the FORCED amount is not shown, and onHold is true") {
            v.returnSummary shouldBe ReturnSummaryView(10_000, cancelledMinor = 10_000, inProgressMinor = 0, cancellableMinor = 0, returnStatus = ReturnStatus.FULLY_CANCELLED)
            v.onHold shouldBe true
        }
    }

    Given("a FORCED return within the balance (D-CPQ-005)") {
        val v = PaymentStatusViews.of(PaymentStatusRowFixture.authorizedWithForcedWithinBalance())
        Then("the cancellable amount is the authoritative value with FORCED subtracted (10,000 − 4,000 = 6,000); nothing completed, nothing in progress, NONE") {
            v.returnSummary shouldBe ReturnSummaryView(10_000, cancelledMinor = 0, inProgressMinor = 0, cancellableMinor = 6_000, returnStatus = ReturnStatus.NONE)
            v.cancellations.single().status shouldBe CancellationViewStatus.FAILED
        }
    }

    Given("onHold (D-CPQ-001)") {
        Then("it reflects only the Payment's own active hold, in any state") {
            PaymentStatusViews.of(PaymentStatusRowFixture.unknown(activeHold = true)).onHold shouldBe true
            PaymentStatusViews.of(PaymentStatusRowFixture.authorized(activeHold = true)).onHold shouldBe true
            PaymentStatusViews.of(PaymentStatusRowFixture.authorized(activeHold = false)).onHold shouldBe false
        }
    }

    Given("a P1 Payment the merchant was told FAILED, with a late approval recorded and an operator refund (D-CPQ-002, D-CANCEL-013)") {
        val v = PaymentStatusViews.of(PaymentStatusRowFixture.p1WithLateApprovalAndOperatorCancel())
        Then("only FAILED and its category are shown: no approved terms, no return summary, no history; the hold flag stays") {
            v.status shouldBe PaymentViewStatus.FAILED
            v.failureCategory shouldBe FailureCategory.DECLINED
            v.authorization?.status shouldBe AuthorizationViewStatus.FAILED
            v.authorization?.approved shouldBe null
            v.authorization?.approvedAt shouldBe null
            v.returnSummary shouldBe null
            v.cancellations.shouldBeEmpty()
            v.onHold shouldBe true
        }
    }

    Given("rows that cannot come from one consistent snapshot (CQ-07 — Invalid Fixtures)") {
        Then("the view is not built: the rule name is reported, no values") {
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(PaymentStatusRowFixture.inconsistentAuthorizedWithOpenAuthorization()) }.rule shouldBe "PAYMENT_AUTHORIZATION_SUMMARY"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(PaymentStatusRowFixture.inconsistentCompletedTotal()) }.rule shouldBe "RETURN_BALANCE_COMPLETED"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(PaymentStatusRowFixture.inconsistentCancellationBeforeAuthorization()) }.rule shouldBe "CANCELLATION_ON_UNCANCELLABLE"
        }
        Then("a requested Payment with an authorization and an in-flight total mismatch are reported too") {
            shouldThrow<PaymentStatusInconsistent> {
                PaymentStatusViews.of(PaymentStatusRowFixture.requested().copy(authorization = PaymentStatusRowFixture.authorization(com.paycore.payment.payment.domain.AuthorizationStatus.OPEN)))
            }.rule shouldBe "PAYMENT_AUTHORIZATION_SUMMARY"
            val row = PaymentStatusRowFixture.authorizedWithHistory()
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(row.copy(balance = row.balance?.copy(reservedMinor = 0))) }.rule shouldBe "RETURN_BALANCE_RESERVED"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(row.copy(balance = null)) }.rule shouldBe "RETURN_BALANCE_MISSING"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(row.copy(balance = row.balance?.copy(authorizedMinor = 9_000))) }.rule shouldBe "RETURN_BALANCE_AUTHORIZED"
        }
        // 검토 #1 n-4: 이력 항목의 짝 규칙도 이름 있는 CQ-07 규칙이다(view init의 IllegalArgumentException이 아니다).
        Then("a cancellation row whose own pairs disagree is reported by name") {
            val base = PaymentStatusRowFixture.authorized()
            fun withOne(c: com.paycore.payment.payment.application.required.CancellationHistoryRow, completed: Long = 0, reserved: Long = 0) =
                base.copy(balance = com.paycore.payment.payment.application.required.ReturnBalanceRow(10_000, completed, reserved, 0), cancellations = listOf(c))
            val completed = PaymentStatusRowFixture.cancellation(1, CancellationStatus.COMPLETED, 1_000)
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(withOne(completed.copy(cancelTransactionId = null), completed = 1_000)) }.rule shouldBe "CANCELLATION_TRANSACTION"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(withOne(completed.copy(confirmedAt = null), completed = 1_000)) }.rule shouldBe "CANCELLATION_TRANSACTION"
            val open = PaymentStatusRowFixture.cancellation(2, CancellationStatus.OPEN, 1_000)
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(withOne(open.copy(cancelTransactionId = "ptx_01X"), reserved = 1_000)) }.rule shouldBe "CANCELLATION_TRANSACTION"
            shouldThrow<PaymentStatusInconsistent> {
                PaymentStatusViews.of(withOne(PaymentStatusRowFixture.cancellation(3, CancellationStatus.FAILED, 1_000, failure = null)))
            }.rule shouldBe "CANCELLATION_FAILURE_EVIDENCE"
            shouldThrow<PaymentStatusInconsistent> {
                PaymentStatusViews.of(withOne(open.copy(failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE), reserved = 1_000))
            }.rule shouldBe "CANCELLATION_FAILURE_EVIDENCE"
            shouldThrow<PaymentStatusInconsistent> { PaymentStatusViews.of(withOne(open.copy(merchantCancelId = null), reserved = 1_000)) }.rule shouldBe "CANCELLATION_INITIATOR_KEY"
            val byOperator = PaymentStatusRowFixture.cancellation(4, CancellationStatus.OPEN, 1_000, byOperator = true)
            shouldThrow<PaymentStatusInconsistent> {
                PaymentStatusViews.of(withOne(byOperator.copy(merchantCancelId = com.paycore.payment.payment.domain.MerchantCancelId("c-4")), reserved = 1_000))
            }.rule shouldBe "CANCELLATION_INITIATOR_KEY"
        }
        Then("a cancel in another currency is reported") {
            val row = PaymentStatusRowFixture.authorized()
            val usd = PaymentStatusRowFixture.cancellation(1, CancellationStatus.OPEN, 1_000).copy(
                money = com.paycore.payment.payment.domain.Money(1_000, com.paycore.payment.payment.domain.CurrencyCode("USD")),
            )
            shouldThrow<PaymentStatusInconsistent> {
                PaymentStatusViews.of(row.copy(balance = com.paycore.payment.payment.application.required.ReturnBalanceRow(10_000, 0, 1_000, 0), cancellations = listOf(usd)))
            }.rule shouldBe "CANCELLATION_CURRENCY"
        }
    }
})
