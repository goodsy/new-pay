package com.paycore.archfixture.institution

import com.paycore.payment.payment.application.required.CardAuthenticationPort
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.RegistrationFailure
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.ProviderResultLookup

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): Simulator가 아닌 실제 기관 인증 adapter를 흉내 낸다 — REAL 인증 adapter는 없어야 한다(D-AUTHN-004, AC1 §9.4).
 */
class RealCardAuthenticationAdapter : CardAuthenticationPort {
    override fun register(request: CardAuthenticationRegistration): RegistrationResult = RegistrationResult.Failed(RegistrationFailure.CONNECTION)

    override fun lookup(resultRef: String): ProviderResultLookup = ProviderResultLookup.Unverifiable(LookupFailure.CONNECTION)
}
