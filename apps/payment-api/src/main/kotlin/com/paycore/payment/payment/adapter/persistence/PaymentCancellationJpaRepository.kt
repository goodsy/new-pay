package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** CANCEL-001 S2 Spring Data repository(관례 §14). 잠금 읽기는 `PessimisticRowLock.lockRow`를 쓰고 JPQL @Lock 조회는 쓰지 않는다(관례 §9). */
interface PaymentCancellationJpaRepository : JpaRepository<JpaPaymentCancellationEntity, String> {
    @Query("select c.cancellationId from JpaPaymentCancellationEntity c where c.paymentId = :paymentId and c.merchantCancelId = :merchantCancelId")
    fun findIdByMerchantKey(@Param("paymentId") paymentId: String, @Param("merchantCancelId") merchantCancelId: String): String?

    @Query("select c.cancellationId from JpaPaymentCancellationEntity c where c.approvalId = :approvalId")
    fun findIdByApprovalId(@Param("approvalId") approvalId: String): String?
}
