package com.paycore.financial.acquiring.application.provided

/**
 * 가상 매입사 outbox의 ACK·결과 파일 수신·반영(ACQUIRING-001 §4.1·§5.1 — 2-F). 절차: TX 밖 SFTP(목록 → 아직 기록하지 않은 (이름, 크기)만 읽기) → 응답 파일마다
 * 로컬 TX 하나(파일 행 `FOR UPDATE` → (이름, SHA-256) 처리 기록 확인 → 해석·대조 → 수신 축·레코드 결과 행·처리 기록을 함께 커밋).
 *
 * - 같은 파일을 다시 읽어도 (이름, hash) 기록이 있어 새 행이 없다(멱등). 같은 이름 다른 hash = 덮어쓰기 의심 → 격리(NAME_REUSED).
 * - 전송 중(TRANSMITTING) 파일의 응답은 이번 회차에 반영하지 않는다(DEFERRED — 다음 회차에 같은 파일을 다시 읽는다, outbox 파일은 지워지지 않는다).
 * - 문법·트레일러 hash·합계 위반, 봉인 hash와 다른 ACK·결과, 서로 모순된 ACK는 격리(자동 반영 없음 — 응답 바이트 보존).
 * - LIVE는 받지 않는다(D-ACQ-008). 접속 설정이 없으면 NOT_CONFIGURED(fail-closed).
 */
interface ReceiveAcquirerResponsesUseCase {
    fun receive(acquirerCode: String, environment: String): ResponseRunResult
}

/**
 * [outcome] = 회차 결과, [applied]·[quarantined]·[deferred]·[alreadyProcessed] = 응답 파일 수, [recordsDecided] = 새 결정 행(ACCEPTED·REJECTED) 수,
 * [duplicates] = 같은 결과의 반복(행 없음) 수, [conflicts] = 새 CONFLICT 행 수.
 */
data class ResponseRunResult(
    val outcome: Outcome,
    val applied: Int = 0,
    val quarantined: Int = 0,
    val deferred: Int = 0,
    val alreadyProcessed: Int = 0,
    val recordsDecided: Int = 0,
    val duplicates: Int = 0,
    val conflicts: Int = 0,
    val errors: Int = 0,
) {
    enum class Outcome { COMPLETED, NOT_CONFIGURED, LIVE_BLOCKED, CHANNEL_FAILED }
}
