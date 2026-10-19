package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.UnknownCause
import java.time.Instant

/** 승인 요청에 싣는 인증 근거 참조(카드번호·CVC·인증 비밀 없음 — 증거 참조와 기관 코드만, AUTH-001 §5.2). */
data class AuthorizationEvidenceRef(val evidenceRef: String, val providerCode: String, val issuerCode: String, val acquirerCode: String)

/**
 * 기관 승인 호출 한 번(AUTH-001 §4.5). namespace·요청 ID는 Attempt의 [providerRequest]다(같은 Attempt는 같은 요청 ID — 재연결도 같다). [connectDeadline]은 dispatch lease
 * 마감에서 안전 여유를 뺀 시각이다: 이 시각 이후에는 connect하지 않는다(AU-07·AU-34 — 재시도마다 다시 확인). [merchantRef]는 MID(인증 바인딩과 같은 값).
 */
data class AuthorizationCall(
    val attemptId: AttemptId,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val merchantRef: String,
    val money: Money,
    val plan: PaymentPlan,
    val evidence: AuthorizationEvidenceRef,
    val connectDeadline: Instant,
) {
    override fun toString(): String = "AuthorizationCall(attemptId=${attemptId.value}, providerRequest=$providerRequest, routeRef=$routeRef)"
}

/** 승인 호출 결과(AC1 §9.1 — 업무 예외 없음, AUTH-001 §4.5·§5.7.2). */
sealed interface AuthorizationCallResult {
    data class Approved(val observation: InstitutionObservation) : AuthorizationCallResult

    data class Declined(val observation: InstitutionObservation) : AuthorizationCallResult

    /** 기관이 처리 중이라고 답했다(PENDING 관찰 — 종료 근거 아님, n-1). */
    data class Pending(val observation: InstitutionObservation) : AuthorizationCallResult

    /** connect 단계에서 끝났다 — 한 바이트도 보내지 않았다(같은 요청 ID 재연결 예산 소진·deadline 경과·모르는 Route). */
    data object NotSentConfirmed : AuthorizationCallResult

    /** 요청을 쓰기 시작한 뒤의 모든 실패(AC1 §5.2) — 금융효과 없음으로 단정하지 않는다. 원 Attempt 조회로 회복. */
    data class OutcomeUnknown(val cause: UnknownCause) : AuthorizationCallResult
}

/**
 * 기관 승인 Port(AUTH-001 §5.7). **DB TX 밖에서만** 부른다(호출자 책임). 구현은 `adapter.integration.institution`의 Simulator adapter뿐이다(REAL 없음 — ArchUnit
 * `authorizationPortsImplementedOnlyBySimulatorAdapter`). Adapter는 domain 전이를 부르지 않고 관찰을 돌려줄 뿐이다.
 */
interface AuthorizationPort {
    fun authorize(call: AuthorizationCall): AuthorizationCallResult
}
