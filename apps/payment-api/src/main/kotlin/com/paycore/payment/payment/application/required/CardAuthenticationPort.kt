package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.ProviderResultLookup

/**
 * 인증 등록 요청(AUTHN-001 §13.4). [state]는 세션별 256bit 원문(기관이 echo — PayCore는 digest만 저장, 원문은 이 호출에만), [returnUrl]은 설정 origin + 결제창
 * handle 경로(판정 m-1)다.
 */
data class CardAuthenticationRegistration(val binding: AuthenticationBinding, val state: String, val returnUrl: String) {
    override fun toString(): String = "CardAuthenticationRegistration(paymentId=${binding.paymentId.value})"
}

/** 등록 실패 분류(AC1 §9.1 — 업무 예외 아님). */
enum class RegistrationFailure { TIMEOUT, CONNECTION, SERVER_ERROR, REJECTED, MALFORMED }

sealed interface RegistrationResult {
    /** [customerUrl]은 설정된 Simulator 고객 origin + 거래 경로일 때만 만든다(응답 조작으로 임의 redirect 불가 — §13.5). */
    data class Registered(val providerTransactionRef: String, val customerUrl: String) : RegistrationResult

    data class Failed(val reason: RegistrationFailure) : RegistrationResult
}

/**
 * 카드 인증기관 Port(AC1 §9.1, AUTHN-001 §13.5). 외부 호출이므로 **DB TX 밖에서만** 부른다(호출자 책임). 업무 예외를 던지지 않고 결과 타입으로 돌려준다:
 * 조회 실패·timeout·형식 위반은 [ProviderResultLookup.Unverifiable](VERIFIED 아님 — D-AUTHN-003 fail-closed). 구현은 `adapter.integration.institution`의
 * Simulator adapter뿐이다(REAL 없음 — ArchUnit, D-AUTHN-004).
 */
interface CardAuthenticationPort {
    fun register(request: CardAuthenticationRegistration): RegistrationResult

    /** 1회용 결과 참조로 서버 간 결과 조회(읽기 전용·멱등). */
    fun lookup(resultRef: String): ProviderResultLookup
}
