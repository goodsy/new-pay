package com.paycore.admin.merchantops.application.required

/**
 * MASTER 등록 명령 호출 결과(MERCHANT-OPS-002 §4.1 — 예외가 아니라 값).
 * - [Decided]: MASTER가 **저장한 판정**(200). 성공이면 [reference](만든 ID), 거절이면 [rejection] — 거절에는 reference가 없다(C-12, 응답에 오면 해석 불가 = [Unknown]).
 * - [KeyReused]: 같은 키·다른 본문(409 — 결함 신호, 효과 없음).
 * - [Busy]: ADMIN 쓰기 permit 없음 또는 MASTER 쓰기 permit 없음(503 `ADMIN_WRITE_BUSY`) — MASTER Use Case 미호출.
 * - [RetrySameKey]: MASTER가 결과를 확정하지 못했다(503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE`) — **같은 키로 다시**.
 * - [Unknown]: timeout·연결 끊김·해석 불가 등 — **효과 유무를 모른다**(효과 없음으로 단정하지 않는다). 같은 키 재전송 또는 조회 화면 read-back으로 확인한다.
 */
sealed interface MasterCommandResult {
    data class Decided(val outcome: Outcome, val rejection: String?, val reference: String?, val replayed: Boolean) : MasterCommandResult {
        init {
            require((outcome == Outcome.SUCCEEDED) == (reference != null && rejection == null)) { "a success has a reference and no rejection" }
            require((outcome == Outcome.REJECTED) == (rejection != null && reference == null)) { "a rejection has a code and no reference" }
        }
    }

    enum class Outcome { SUCCEEDED, REJECTED }

    data object KeyReused : MasterCommandResult

    data class Busy(val reason: String) : MasterCommandResult

    data class RetrySameKey(val reason: String) : MasterCommandResult

    data class Unknown(val reason: String) : MasterCommandResult
}
