package com.paycore.payment.payment.application.required

import java.time.Duration

/** FINANCIAL 전달 결과(HTTP adapter 분류 — FIN-FACT-RELAY-001 §4.1, golden `statusClassification`). [code]는 고정 분류 코드(`^[A-Z0-9_]{1,48}$`, 본문·값 없음). */
sealed interface FinancialFactDispatchOutcome {
    /** 2xx + 읽을 수 있고 보낸 eventId·effectKey와 같은 ACK: FINANCIAL이 내구 커밋했다. */
    data class Acknowledged(val consumer: String, val outcome: String, val originalOutcome: String?) : FinancialFactDispatchOutcome

    /** 연결 실패·timeout·5xx·401·404·3xx·계약 오류 코드가 없는 4xx·읽을 수 없는 ACK: 같은 eventId로 다시 보낸다(상한 없음 — 연령 gauge로 경보). */
    data class Retryable(val code: String) : FinancialFactDispatchOutcome

    /** 400·409·422 + FINANCIAL 계약 오류 코드(본문 `error`): FINANCIAL이 이 내용 자체를 거절했다(같은 내용은 다시 보내도 거절) — 격리. */
    data class Rejected(val code: String) : FinancialFactDispatchOutcome
}

/** FINANCIAL 내부 Inbox로 전달(DB TX 밖에서만 호출 — 외부 호출 규칙). 예외 대신 [FinancialFactDispatchOutcome]으로 분류한다. */
interface FinancialFactDispatcher {
    /**
     * 한 [dispatch] 호출의 최대 소요(연결 + 요청 + 응답 본문 — 구현이 이 시간 안에 반드시 반환한다). Relay는 claim lease가 이 예산보다 많이 남았을 때만 보낸다
     * (lease가 끝난 뒤 보내는 중이면 다른 인스턴스가 같은 행을 다시 집어 겹쳐 보낸다 — 검토 M-1).
     */
    val sendBudget: Duration

    fun dispatch(envelope: FinancialFactRelayEnvelope): FinancialFactDispatchOutcome
}
