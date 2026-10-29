package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.MalformedOperatorCommand
import com.paycore.payment.payment.application.provided.OperatorCancelMalformedRecorder
import com.paycore.payment.payment.application.required.OperatorCancelRejection
import com.paycore.payment.payment.application.required.OperatorCancelRejectionStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import java.time.temporal.ChronoUnit

/**
 * 형식 오류 감사(ADMIN-PAYMENT-OPS-001 §4.6, AC4): 서명 통과 뒤 형식 단계에서 거절된 명령의 거절 감사 행 하나를 **자기 로컬 TX**에 남긴다(결정 행 없음). 원문은 저장·로그하지 않는다.
 * 저장 실패(TX 불가·저장소 일시 실패·commit 불명확)는 [CancelPaymentFailure.TemporarilyUnavailable] — 감사 실패 = 행위 실패(503).
 */
@Service
class OperatorCancelMalformedAuditService(
    transactionManager: PlatformTransactionManager,
    private val rejections: OperatorCancelRejectionStore,
    private val clock: PaymentClock,
) : OperatorCancelMalformedRecorder {
    private val log = LoggerFactory.getLogger(OperatorCancelMalformedAuditService::class.java)
    private val transactions = LocalTransactions(transactionManager, 1)

    override fun record(rejection: MalformedOperatorCommand) {
        try {
            transactions.execute({ false }) {
                rejections.append(
                    OperatorCancelRejection(
                        OperatorApprovalId(rejection.approvalId), PaymentId(rejection.paymentId), OperatorId(rejection.requestedBy), OperatorId(rejection.approvedBy),
                        rejection.code.name, clock.now().truncatedTo(ChronoUnit.MILLIS),
                    ),
                )
            }
        } catch (e: TransactionException) {
            throw CancelPaymentFailure.TemporarilyUnavailable("MALFORMED_AUDIT_UNAVAILABLE", e)
        } catch (e: CommitOutcomeUnknownException) {
            throw CancelPaymentFailure.TemporarilyUnavailable("MALFORMED_AUDIT_UNAVAILABLE", e)
        } catch (e: com.paycore.payment.payment.application.required.PersistenceFailure) {
            throw CancelPaymentFailure.TemporarilyUnavailable("MALFORMED_AUDIT_UNAVAILABLE", e)
        }
        log.info("operation=OperatorCorrectionCancel correlationId={} result=REJECTED reason={}", rejection.correlationId, rejection.code.name)
    }
}
