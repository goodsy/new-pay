package com.paycore.simulator.acquirer.application.provided

/**
 * 가상 매입사 Worker 한 회차(ACQUIRING-001 2-E §7): 모든 매입사 inbox의 최종명을 수신 판정하고(같은 내용 재확인은 멱등), 게시 시각이 된 ACK·결과 파일을
 * outbox에 게시한다. 스케줄러(adapter)와 시험이 부른다. 회차는 직렬화된다(같은 JVM 안 동시 실행 없음 — 다른 인스턴스와의 경합은 DB UNIQUE·@Version이 막는다).
 */
interface RunAcquirerWorkerUseCase {
    fun runOnce(): AcquirerWorkerReport
}

/**
 * 한 회차 결과: 새로 기록한 수신 수, 게시(또는 같은 바이트 확인)한 파일 수, 덮어쓰기를 거부해 남긴 작업 수, 이번에 읽어 hash한 inbox 파일 수(같은 이름·크기·수정
 * 시각이면 다시 읽지 않는다 — 리뷰 m-1), 처리 중 실패해 다음 회차로 넘긴 파일·작업 수(리뷰 m-4), 지운 오래된 업로드 임시 파일 수.
 */
data class AcquirerWorkerReport(
    val newReceipts: Int,
    val published: Int,
    val publishRefused: Int,
    val hashedFiles: Int = 0,
    val failed: Int = 0,
    val sweptTemps: Int = 0,
)
