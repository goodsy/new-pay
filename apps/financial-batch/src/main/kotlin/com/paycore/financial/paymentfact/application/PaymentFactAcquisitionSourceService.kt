package com.paycore.financial.paymentfact.application

import com.paycore.financial.paymentfact.application.provided.AcquisitionAuthorizationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionCancellationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.application.required.PaymentFactAcquisitionReadStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [PaymentFactAcquisitionSource] 구현 — 호출자(매입 봉인 TX)의 TX에 참여한다(MANDATORY: TX 없이 부르면 실패). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PaymentFactAcquisitionSourceService(private val store: PaymentFactAcquisitionReadStore) : PaymentFactAcquisitionSource {
    override fun authorizationKeys(acquirerCode: String, environment: String, approvedUpTo: Instant): List<AcquisitionAuthorizationKey> =
        store.authorizationKeys(acquirerCode, environment, approvedUpTo)

    override fun cancellationKeys(acquirerCode: String, environment: String, cancelledUpTo: Instant): List<AcquisitionCancellationKey> =
        store.cancellationKeys(acquirerCode, environment, cancelledUpTo)

    override fun lockAndRead(paymentIds: Collection<String>): List<AcquisitionSourcePayment> =
        if (paymentIds.isEmpty()) emptyList() else store.lockAndRead(paymentIds)

    override fun paymentIdsOf(authorizationTransactionIds: Collection<String>): Map<String, String> =
        if (authorizationTransactionIds.isEmpty()) emptyMap() else store.paymentIdsOf(authorizationTransactionIds)

    override fun readWithoutLock(paymentIds: Collection<String>): List<AcquisitionSourcePayment> =
        if (paymentIds.isEmpty()) emptyList() else store.read(paymentIds)
}
