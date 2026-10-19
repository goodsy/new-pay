package com.paycore.archfixture.institution

import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationCallResult
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.application.required.AuthorizationInquiryPort
import com.paycore.payment.payment.application.required.AuthorizationPort
import com.paycore.payment.payment.application.required.InquiryCallResult
import com.paycore.payment.payment.application.required.InquiryUnavailableReason

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): Simulator가 아닌 실제 VAN 승인 adapter를 흉내 낸다 — REAL 승인·조회 adapter는 없어야 한다(AUTH-001 §5.6 겹 1, AC1 §9.4).
 */
class RealVanAuthorizationAdapter : AuthorizationPort, AuthorizationInquiryPort {
    override fun authorize(call: AuthorizationCall): AuthorizationCallResult = AuthorizationCallResult.NotSentConfirmed

    override fun inquire(call: AuthorizationInquiryCall): InquiryCallResult = InquiryCallResult.Unavailable(InquiryUnavailableReason.CONNECTION)
}
