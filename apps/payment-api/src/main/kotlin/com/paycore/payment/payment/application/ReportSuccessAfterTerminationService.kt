package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.ReportSuccessAfterTerminationUseCase
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 늦은 성공 보고 → 주문 사건 보류(CP-DOMAIN-001 M-3). 응답·통보·조회가 같은 늦은 성공을 동시에 여러 번 보고해도 보류는 하나다:
 * control 행 잠금(control → Payment 순서)으로 직렬화하고 domain이 이미 있는 보류를 no-op으로 처리한다. 잠금 밖 경합이 있더라도
 * DB UNIQUE(OrderKey, paymentId, reason)가 두 번째 행을 거부하고, 그 충돌은 새 TX에서 다시 읽어 no-op으로 수렴한다.
 * Payment 상태는 덮어쓰지 않는다. 자동 보상취소·보류 해제는 별도 정책(G-4)이라 여기 없다.
 */
@Service
class ReportSuccessAfterTerminationService(
    transactionManager: PlatformTransactionManager,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
) : ReportSuccessAfterTerminationUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun report(orderKey: OrderKey, paymentId: PaymentId, reportedAt: Instant) {
        transactions.execute(::isRetryable) {
            val control = controls.lock(orderKey) ?: throw NoSuchElementException("order payment control not found")
            val payment = payments.lock(paymentId) ?: throw NoSuchElementException("payment not found")
            control.value.reportSuccessAfterTermination(payment.value)
            controls.update(control.value, control.version, reportedAt.truncatedTo(ChronoUnit.MILLIS))
        }
    }

    private fun isRetryable(e: RuntimeException): Boolean = when (e) {
        is PersistenceFailure.Deadlock, is PersistenceFailure.OptimisticConflict -> true
        is PersistenceFailure.UniqueConflict -> e.constraint == StoreConstraint.INCIDENT_HOLD
        else -> false
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
