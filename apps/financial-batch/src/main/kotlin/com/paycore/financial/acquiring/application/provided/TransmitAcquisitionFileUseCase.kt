package com.paycore.financial.acquiring.application.provided

/**
 * 봉인된 매입 파일 SFTP 전송(ACQUIRING-001 §4.5 — 2-D). 절차: 점유 TX(SEALED/결과 모름/lease 만료 → TRANSMITTING + lease + 시도 행) → TX 밖 SFTP
 * (최종명 확인 → 없으면 `.part` 업로드 → rename → 재접속 stat·되읽기 hash 대조) → 결과 TX(@Version 기대 version + 결과 행).
 * 전송 바이트는 **저장된 봉인 바이트**다(봉인을 다시 부르지 않는다 — 리뷰 NIT-1: 마감 정책이 소급 변경돼도 이미 봉인된 파일 전송이 막히지 않는다). 저장 바이트가
 * 저장 헤더·레코드와 맞지 않으면 점유하지 않는다(INTEGRITY_VIOLATION).
 *
 * 같은 파일 ID·같은 바이트 재업로드만 한다(새 ID 재전송 없음 — I-10). 자동 재매입(D-ACQ-004)과 무관하다: 재업로드는 같은 파일의 전달 확인이다.
 */
interface TransmitAcquisitionFileUseCase {
    fun transmit(fileId: String): TransmissionResult

    /** lane의 전송 차례 파일(SEALED·TRANSMISSION_UNKNOWN·lease 만료)을 업무일 순으로 최대 [limit]개 전송한다(회복 경로 포함). */
    fun transmitPending(acquirerCode: String, environment: String, limit: Int): List<TransmissionResult>
}

/** [outcome] = 이번 호출의 결과, [state] = 호출 뒤 파일 전송 상태(점유하지 못했으면 현재 상태, 파일이 없으면 null). */
data class TransmissionResult(val fileId: String, val outcome: Kind, val state: String?, val detail: String?) {
    enum class Kind {
        TRANSMITTED,
        ALREADY_TRANSMITTED,
        NOT_DELIVERED,
        OUTCOME_UNKNOWN,
        QUARANTINED,

        /** 다른 시도가 lease 안에서 점유 중. */
        IN_FLIGHT,

        /** 이 시도의 lease가 지나 다른 시도가 이어받았다(이 시도 결과는 버림 — 이어받은 시도가 확인한다). */
        SUPERSEDED,
        INTEGRITY_VIOLATION,
        NOT_CONFIGURED,
        NOT_FOUND,

        /** LIVE 파일 전송 거부(D-ACQ-008 이중 방어 — 리뷰 m-3). */
        LIVE_BLOCKED,

        /** 예기치 않은 실패(점유 커밋 결과 모름 등). [TransmissionResult.detail] = 분류 코드·예외 클래스 이름만. */
        ERROR,
    }
}
