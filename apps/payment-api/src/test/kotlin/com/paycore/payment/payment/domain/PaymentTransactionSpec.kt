package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * 확정 금융 사실(D-AUTH-003): 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건. 승인 = AUTHORIZATION, 취소·부분취소 = 각각 CANCEL
 * (CANCEL-CONTRACT-001 §4.1·T1, CC-03). 취소 거래는 원 AUTHORIZATION 거래를 참조하고 정산 거래일 근거인 기관 취소 거래일과 PG 확정 시각을 따로 가진다(§5.3).
 */
class PaymentTransactionSpec : BehaviorSpec({

    Given("an authorization transaction recorded by the resolution") {
        Then("its type is AUTHORIZATION and its money is the approved money (behaviour unchanged by the CANCEL type)") {
            val s = AuthorizationFixture.authorized()
            val tx = s.authorization.transaction.shouldBeInstanceOf<AuthorizationTransaction>()
            tx.type shouldBe PaymentTransactionType.AUTHORIZATION
            tx.money shouldBe 10_000L.won()
            (tx as PaymentTransaction).paymentId shouldBe s.payment.id
        }
    }

    Given("a cancel transaction") {
        val confirmed = CancelConfirmedTerms(3_000L.won(), "PTX-SIM-0001", "PTX-SIM-C001", "C0001", LocalDate.of(2026, 10, 16))
        fun tx(id: String = "demo-ctx-0001", original: String = "demo-ptx-0001") = CancelTransaction(
            id = PaymentTransactionId(id),
            paymentId = OrderFixture.paymentId(1),
            authorizationId = AuthorizationFixture.authorizationId,
            cancellationId = CancellationFixture.cancellationId(),
            originalTransactionId = PaymentTransactionId(original),
            scope = CancelScope.PARTIAL,
            terms = confirmed,
            sourceObservation = CancelObservationKey(CancellationFixture.cancelAttemptId(), ObservationSource.RESPONSE, "sim-cancel-result-1"),
            confirmedAt = CancellationFixture.at.plusSeconds(3),
        )
        Then("its type is CANCEL, its money is the institution-confirmed cancel money and it refers to the original authorization transaction") {
            val t = tx()
            t.type shouldBe PaymentTransactionType.CANCEL
            t.money shouldBe 3_000L.won()
            t.originalTransactionId shouldBe PaymentTransactionId("demo-ptx-0001")
        }
        Then("the settlement transaction date is the institution cancel date, kept apart from the PG confirmation instant") {
            val t = tx()
            t.institutionTransactionDate shouldBe LocalDate.of(2026, 10, 16)
            t.confirmedAt shouldBe CancellationFixture.at.plusSeconds(3)
        }
        Then("a cancel transaction cannot be its own original") {
            shouldThrow<IllegalArgumentException> { tx(id = "demo-ptx-0001", original = "demo-ptx-0001") }
        }
        Then("confirmed cancel terms need the original and the cancel provider transaction ids") {
            shouldThrow<IllegalArgumentException> { confirmed.copy(originalProviderTransactionId = "") }
            shouldThrow<IllegalArgumentException> { confirmed.copy(providerTransactionId = "") }
            shouldThrow<IllegalArgumentException> { confirmed.copy(cancelApprovalNumber = "") }
            confirmed.copy(cancelApprovalNumber = null).cancelApprovalNumber shouldBe null
        }
    }
})
