package com.paycore.financial.paymentfact.application.required

import com.paycore.financial.paymentfact.application.provided.AcquisitionAuthorizationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionCancellationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import java.time.Instant

/** 매입 조회 저장 Port(투영 읽기 + 결제별 직렬화 행 잠금 — CONVENTIONS §14 Store ②). 호출자 TX 필수. */
interface PaymentFactAcquisitionReadStore {
    fun authorizationKeys(acquirerCode: String, environment: String, approvedUpTo: Instant): List<AcquisitionAuthorizationKey>

    /** payment_id 순으로 직렬화 행을 잠근 뒤 읽는다(교착 회피를 위한 고정 순서). */
    fun lockAndRead(paymentIds: Collection<String>): List<AcquisitionSourcePayment>

    /** 원승인 투영의 매입사·환경으로 고른 취소 키(잠금 없음). */
    fun cancellationKeys(acquirerCode: String, environment: String, cancelledUpTo: Instant): List<AcquisitionCancellationKey>

    /** 승인 거래 ID → Payment ID(잠금 없음). */
    fun paymentIdsOf(authorizationTransactionIds: Collection<String>): Map<String, String>

    /** [lockAndRead]와 같은 값, 잠금 없음(비권위 미리 보기). */
    fun read(paymentIds: Collection<String>): List<AcquisitionSourcePayment>
}
