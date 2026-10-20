package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnBalance
import java.time.Instant

/**
 * 반환 가능 금액 Aggregate 저장 Port(CANCEL-001 명세 §8.3, CC-02 — Payment 1:1, 예약 포함). 권위 상태는 이 행이며 캐시·Redis가 대신하지 않는다. 같은 Payment의 서로 다른 취소
 * 사이 합계 불변식은 이 행의 비관적 잠금(앞의 control·payment 잠금 다음)과 DB CHECK(completed + reserved ≤ authorized)로 지킨다. 예약 행은 지우지 않는다. TX 필수(MANDATORY).
 */
interface ReturnBalanceRepository {
    /** 첫 취소 시작에서 연 잔액(예약 포함)을 INSERT한다. 같은 Payment의 두 번째 잔액은 [PersistenceFailure.UniqueConflict]. 최초 version. */
    fun insertNew(balance: ReturnBalance, at: Instant): Long

    /** Payment의 반환 잔액을 잠가 읽는다(`FOR UPDATE`). 아직 열리지 않았으면 null. */
    fun lockByPaymentId(paymentId: PaymentId): Versioned<ReturnBalance>?

    /** 예약 추가·상태 변경·합계·revision을 반영한다. 새 version을 돌려준다. */
    fun update(balance: ReturnBalance, expectedVersion: Long, at: Instant): Long
}
