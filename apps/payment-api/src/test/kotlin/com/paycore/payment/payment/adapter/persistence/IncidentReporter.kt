package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.reportSuccessAfterTermination
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 시험 상태 준비 전용: 종료된 Payment의 늦은 성공 사건 보류를 저장한다(control 잠금 → Payment 잠금 → domain `reportSuccessAfterTermination` → control 저장).
 * main의 `ReportSuccessAfterTerminationService`는 AUTH-001 AU-18로 삭제됐다 — 관찰 키 없는 보고 경로가 D-AUTH-012 이후 같은 늦은 성공의 재보고를 새 hold로 만들기 때문이다.
 * 운영 경로는 관찰 키를 가진 `AuthorizationResolution`(TX2·회복)뿐이며, 이 helper는 hold가 있는 주문을 만드는 시험 준비에만 쓴다.
 */
class IncidentReporter(
    private val transactionManager: PlatformTransactionManager,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
) {
    fun report(orderKey: OrderKey, paymentId: PaymentId, reportedAt: Instant) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            val control = checkNotNull(controls.lock(orderKey)) { "order payment control not found" }
            val payment = checkNotNull(payments.lock(paymentId)) { "payment not found" }
            control.value.reportSuccessAfterTermination(payment.value)
            controls.update(control.value, control.version, reportedAt.truncatedTo(ChronoUnit.MILLIS))
        }
    }
}
