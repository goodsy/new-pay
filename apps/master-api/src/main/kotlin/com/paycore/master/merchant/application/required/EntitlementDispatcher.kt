package com.paycore.master.merchant.application.required

/** 전달 결과(HTTP adapter 분류, 명세 §4.7). */
sealed interface DispatchOutcome {
    /** 2xx: PAYMENT가 내구 커밋했다. */
    data class Acknowledged(val consumer: String, val outcome: String, val appliedRevision: Long, val originalOutcome: String? = null) : DispatchOutcome

    /** 5xx·timeout·연결 오류: 같은 eventId로 다시 보낸다(상한 없음 — 차단 Snapshot을 포기하지 않는다). */
    data class Retryable(val code: String) : DispatchOutcome

    /** 4xx(서명·봉투·내용 오류): 계약 오류. 재전송하지 않고 격리한다. */
    data class Rejected(val code: String) : DispatchOutcome
}

/** PAYMENT 내부 Inbox로 전달(DB TX 밖에서만 호출 — 외부 호출 규칙). */
interface EntitlementDispatcher {
    fun dispatch(envelope: RelayEnvelope): DispatchOutcome
}
