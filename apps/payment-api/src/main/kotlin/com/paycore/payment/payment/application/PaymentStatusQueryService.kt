package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.provided.PaymentStatusQuery
import com.paycore.payment.payment.application.provided.PaymentStatusResult
import com.paycore.payment.payment.application.required.PaymentStatusReader
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.UnexpectedRollbackException

/**
 * 결제 상태 조회(CP-QUERY-001 §4.2·§6.1). TX를 열지 않는다(Reader가 읽기 전용 스냅샷 TX를 소유 — 명령 TX와 섞이지 않는다). 범위는 인증 주체의 environment·MID 그대로 Reader 조건으로
 * 넘기고(CQ-02), 형식 밖 paymentId·없음·범위 밖은 같은 NotFound다(존재 비노출 — §4.3; 형식 술어는 web 경로와 같은 [PaymentId.isPresentable] — 검토 #1 n-1).
 * 저장소 실패([PersistenceFailure])와 Reader TX 시작·종료 실패(연결 획득·커밋/rollback 실패 — 검토 #1 m-2)는 provided 결과 [PaymentStatusResult.Unavailable](분류만 — 503)로 돌려준다.
 * web adapter는 application.required 타입을 모른다(ArchUnit webAdapterUsesOnlyApplication — 전체 check에서 드러난 위반 수정).
 * Reader 사용처는 이 서비스뿐(ArchUnit CQ-06 — 비권위 값이 명령 판정에 쓰이지 않게).
 */
@Service
class PaymentStatusQueryService(private val reader: PaymentStatusReader) : PaymentStatusQuery {
    override fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: String): PaymentStatusResult {
        if (!PaymentId.isPresentable(paymentId)) return PaymentStatusResult.NotFound
        val id = try {
            PaymentId(paymentId)
        } catch (e: IllegalArgumentException) {
            return PaymentStatusResult.NotFound
        }
        val row = try {
            reader.find(environment, merchantAccountId, id)
        } catch (e: PersistenceFailure) {
            return PaymentStatusResult.Unavailable(e.failureKind)
        } catch (e: CannotCreateTransactionException) {
            return PaymentStatusResult.Unavailable(FailureKind.DB_UNAVAILABLE)
        } catch (e: TransactionSystemException) {
            return PaymentStatusResult.Unavailable(FailureKind.DB_UNAVAILABLE)
        } catch (e: UnexpectedRollbackException) {
            return PaymentStatusResult.Unavailable(FailureKind.DB_UNAVAILABLE)
        } ?: return PaymentStatusResult.NotFound
        return PaymentStatusResult.Found(PaymentStatusViews.of(row))
    }
}
