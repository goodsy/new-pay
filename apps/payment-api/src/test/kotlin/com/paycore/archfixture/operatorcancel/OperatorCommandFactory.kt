package com.paycore.archfixture.operatorcancel

import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId
import java.time.Instant

/** ArchUnit 위반 fixture(§4.8): 진입 경로 밖에서 운영자 명령을 생성자로 만든다(명령 위조 경로). */
object OperatorCommandFactory {
    fun forge(): OperatorCorrectionCancelCommand = OperatorCorrectionCancelCommand(
        "pay_x", Money(1, CurrencyCode("KRW")), OperatorId("a"), OperatorId("b"), OperatorApprovalId("apr_x"), "r", OperatorCancelPurpose.IncidentOrReconCorrection("c"),
        "oct1:x", Instant.EPOCH, Instant.EPOCH, "corr",
    )
}
