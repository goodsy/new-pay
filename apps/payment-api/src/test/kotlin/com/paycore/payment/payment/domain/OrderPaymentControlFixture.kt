package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentFixture.advance
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE

/** 한 주문의 control과 현재 Payment(및 과거 Payment)를 함께 묶은 시나리오. */
class OrderPaymentScenario(
    val control: OrderPaymentControl,
    val current: Payment,
    val history: List<Payment> = listOf(current),
)

/**
 * OrderPaymentControl Named Test Fixture. control은 domain 공식 경로(`OrderPaymentControl.open`·`occupy`·`recordAuthorized`)로만
 * 만들고, currentPaymentId·generation과 현재 Payment는 서로 독립적으로 고르지 않는다(open/occupy가 함께 정한다).
 */
object OrderPaymentControlFixture {

    /** 첫 Payment(REQUESTED, generation 1)로 연 주문. */
    fun openedWithRequestedPayment(): OrderPaymentScenario {
        val first = PaymentFixture.requested(n = 1, slotGeneration = 1)
        return OrderPaymentScenario(OrderPaymentControl.open(first), first)
    }

    /**
     * 첫 Payment로 주문을 연 뒤 Payment를 업무 경로대로 [status]까지 진행한다(FAILED/EXPIRED는 [evidence]에 맞는 경로).
     * 승인 성공이면 같은 TX에서 하듯 control에 성공 이력을 기록한다.
     */
    fun withCurrentPayment(status: PaymentStatus, evidence: TerminationEvidence? = null): OrderPaymentScenario {
        val s = openedWithRequestedPayment()
        s.current.advance(status, evidence)
        if (status == AUTHORIZED) s.control.recordAuthorized(s.current)
        return s
    }

    /** 승인 성공 주문(이후 취소/환불 여부와 무관하게 성공 이력이 남는다). */
    fun authorizedOrder(): OrderPaymentScenario = withCurrentPayment(AUTHORIZED)

    /** 첫 시도가 기관 확정 거절로 종료된 뒤 같은 고정 입력의 새 Payment(generation 2)가 점유한 주문. */
    fun retriedAfterConfirmedDecline(): OrderPaymentScenario {
        val s = withCurrentPayment(PaymentStatus.FAILED, INSTITUTION_CONFIRMED_DECLINE)
        val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
        s.control.occupy(second, s.current)
        return OrderPaymentScenario(s.control, second, listOf(s.current, second))
    }
}
