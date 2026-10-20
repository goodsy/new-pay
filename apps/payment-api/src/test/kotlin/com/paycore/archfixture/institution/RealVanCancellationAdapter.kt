package com.paycore.archfixture.institution

import com.paycore.payment.payment.application.required.CancelInquiryCall
import com.paycore.payment.payment.application.required.CancelInquiryCallResult
import com.paycore.payment.payment.application.required.CancelInquiryPort
import com.paycore.payment.payment.application.required.CancellationCall
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.CancellationPort
import com.paycore.payment.payment.application.required.InquiryUnavailableReason

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): Simulator가 아닌 실제 VAN 취소 adapter를 흉내 낸다 — REAL 취소·취소 조회 adapter는 없어야 한다(CANCEL-001 §6.6).
 */
class RealVanCancellationAdapter : CancellationPort, CancelInquiryPort {
    override fun cancel(call: CancellationCall): CancellationCallResult = CancellationCallResult.NotSentConfirmed

    override fun inquire(call: CancelInquiryCall): CancelInquiryCallResult = CancelInquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
}
