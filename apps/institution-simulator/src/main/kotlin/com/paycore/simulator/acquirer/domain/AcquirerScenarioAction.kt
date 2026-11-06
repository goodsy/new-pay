package com.paycore.simulator.acquirer.domain

/**
 * 가상 매입사 결정적 시나리오(ACQUIRING-001 §7 — **Demo 대역, 운영 동작 아님**). 가상 VAN의 카드별 동작(`VanBehaviour`)·가상 가맹점 수신기의 수신기별 시나리오처럼
 * 난수 없이 설정만으로 같은 결과를 재현한다. 시각은 Simulator 시계(시험 = 가상 시계)로 계산한다.
 *
 * 레코드 시나리오는 파일 ID(선택)와 거래 ID 또는 승인액(Demo에서 결제 금액으로 고를 수 있다) 중 정확히 하나로 고른다. 파일·SFTP 시나리오는 파일 ID로 고른다.
 */
enum class AcquirerScenarioAction(val scope: Scope) {
    /** 레코드를 거절한다(대조 결과와 무관 — reason 필수). */
    REJECT(Scope.RECORD),

    /** 레코드 결과를 어느 결과 파일에도 싣지 않는다(결과 누락 — FINANCIAL은 결과 마감 뒤 UNKNOWN, D-ACQ-004). 기관 내부 판정은 기록한다. */
    WITHHOLD_RESULT(Scope.RECORD),

    /** 레코드 결과를 결과 지연 [AcquirerScenarioRule.delay] 뒤 별도 결과 파일로 싣는다(늦은 결과 — D-ACQ-010). */
    DELAY_RESULT(Scope.RECORD),

    /** 같은 결과를 다음 결과 파일에 한 번 더 싣는다(결과 중복 — 받는 쪽 멱등). */
    DUPLICATE_RESULT(Scope.RECORD),

    /** 반대 결과를 다음 결과 파일에 싣는다(결과 상충 — 받는 쪽 격리, D-ACQ-010). */
    CONFLICTING_RESULT(Scope.RECORD),

    /** 파일 전체를 거절한다(ACK FILE_REJECTED·SCENARIO_FILE_REJECTED, 레코드 판정 없음). */
    FILE_REJECT(Scope.FILE),

    /** ACK를 게시하지 않는다(ACK 유실 — 결과 파일은 그대로, I-8 RESULT_IMPLIED). */
    DROP_ACK(Scope.FILE),

    /** ACK를 [AcquirerScenarioRule.delay] 늦게 게시한다. */
    DELAY_ACK(Scope.FILE),

    /** 그 파일 임시명(`.part`) 첫 쓰기 요청에서 SFTP 세션을 끊는다(업로드 중 끊김) — [AcquirerScenarioRule.times]번. */
    DISCONNECT_DURING_UPLOAD(Scope.SFTP),

    /** 그 파일 rename을 적용한 **뒤** 응답 전에 세션을 끊는다(rename 응답 유실) — [AcquirerScenarioRule.times]번. */
    DROP_RENAME_RESPONSE(Scope.SFTP),
    ;

    enum class Scope { RECORD, FILE, SFTP }
}
