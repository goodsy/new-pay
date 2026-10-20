package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelScope
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.UnknownCause
import java.time.LocalDate

/**
 * 취소할 원 승인의 기관 참조(CANCEL-001 §6.2 echo 필드 — 원 승인 요청 ID·기관 거래 식별자·승인번호·기관 거래일). 카드번호·인증 비밀 없음.
 */
data class OriginalAuthorizationRef(
    val providerRequest: ProviderRequestRef,
    val providerTransactionId: String,
    val approvalNumber: String,
    val transactionDate: LocalDate,
) {
    init {
        require(providerRequest.operation == AUTHORIZE) { "the original is an authorization request" }
        require(providerTransactionId.isNotEmpty() && approvalNumber.isNotEmpty()) { "original identifiers must not be empty" }
    }

    private companion object {
        const val AUTHORIZE = "AUTHORIZE"
    }
}

/**
 * 기관 취소 호출 한 번(CANCEL-001 §4.5·§6.6). [providerRequest]는 취소 Attempt의 기관 요청 namespace(`cr_` 요청 ID — 같은 Attempt는 같은 ID)다. 원 승인과 같은 기관·환경·
 * 기관 계정이어야 한다(rev 0.3 m-4 — 다르면 호출을 만들 수 없다, 프로그래밍 오류). [connectDeadline] 이후에는 connect하지 않는다(승인과 같은 규칙).
 */
data class CancellationCall(
    val attemptId: CancelAttemptId,
    val providerRequest: ProviderRequestRef,
    val routeRef: String,
    val merchantRef: String,
    val original: OriginalAuthorizationRef,
    val money: Money,
    val scope: CancelScope,
    val connectDeadline: java.time.Instant,
) {
    init {
        require(providerRequest.operation == CANCEL) { "a cancel call uses the CANCEL namespace" }
        val o = original.providerRequest
        require(providerRequest.institution == o.institution && providerRequest.environment == o.environment && providerRequest.institutionAccount == o.institutionAccount) {
            "a cancel goes to the institution, environment and account of the original authorization (m-4)"
        }
    }

    override fun toString(): String = "CancellationCall(attemptId=${attemptId.value}, providerRequest=$providerRequest, routeRef=$routeRef, scope=$scope)"

    private companion object {
        const val CANCEL = "CANCEL"
    }
}

/** 취소 호출 결과(CANCEL-001 §6.6 표 — 업무 예외 없음). */
sealed interface CancellationCallResult {
    data class Approved(val observation: CancelObservation) : CancellationCallResult

    /** 기관 거절(고정 분류 [declineCategory] — CN-02 안내용, 원문 없음). */
    data class Declined(val observation: CancelObservation, val declineCategory: String) : CancellationCallResult

    /** 기관이 처리 중이라고 답했다(종료 근거 아님 — UNKNOWN 유지). */
    data class Pending(val observation: CancelObservation) : CancellationCallResult

    /** connect 단계에서 끝났다 — 한 바이트도 보내지 않았다(예산 소진·deadline 경과·모르는/취소 불가 Route). */
    data object NotSentConfirmed : CancellationCallResult

    /** 요청을 쓰기 시작한 뒤의 모든 실패 — 금융효과 없음으로 단정하지 않는다. 원 Attempt 조회로 회복. */
    data class OutcomeUnknown(val cause: UnknownCause) : CancellationCallResult
}

/**
 * 기관 취소 Port(CANCEL-001 §6.6). **DB TX 밖에서만** 부른다(호출자 책임). 구현은 `adapter.integration.institution`의 Simulator adapter뿐이다(REAL 없음 — ArchUnit
 * `cancellationPortsImplementedOnlyBySimulatorAdapter`). Adapter는 domain 전이를 부르지 않고 관찰을 돌려줄 뿐이다.
 */
interface CancellationPort {
    fun cancel(call: CancellationCall): CancellationCallResult
}
