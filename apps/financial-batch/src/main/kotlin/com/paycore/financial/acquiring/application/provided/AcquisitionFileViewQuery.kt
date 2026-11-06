package com.paycore.financial.acquiring.application.provided

import java.time.LocalDate

/**
 * 매입 현황 관리 조회(ACQ-VIEW-001 §3.3 — 진입점은 ADMIN 채널 `AdminAcquisitionController`만). **비권위 읽기** — 매입 판정·전송·결과 반영 어디에도 쓰지 않는다
 * (권위 상태는 잠금 아래 JPA 경로가 읽는다 — ACQUIRING-001 §6). 저장소 실패·질의 시간 초과는 [AcquisitionViewFailure].
 * 상세와 레코드 쪽은 각각 한 읽기 전용 REPEATABLE READ TX(찢어진 상세 없음). 사용자 결정 D-ACQV-002의 기관 값(승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·
 * 할부)과 봉인·응답 바이트·송신자 ID는 어느 타입에도 없다.
 */
interface AcquisitionFileViewQuery {
    /** 최신 업무일순 keyset 한 쪽. */
    fun search(criteria: AcquisitionFileSearchCriteria): AcquisitionFilePage

    /** 없으면 null. 레코드 첫 쪽([records])을 같은 TX로 읽는다. */
    fun detail(fileId: String, records: AcquisitionRecordSearchCriteria = AcquisitionRecordSearchCriteria()): AcquisitionFileDetail?

    /** 파일이 없으면 null. */
    fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria): AcquisitionRecordPage?
}

/** keyset 위치 — `(businessDate, fileId)`보다 **작은** 행이 다음 쪽이다(최신 업무일순). */
data class AcquisitionFileCursor(val businessDate: LocalDate, val fileId: String)

/**
 * 파일 검색 조건(ACQ-VIEW-001 §3.3 — 형식 검사는 진입 adapter의 엄격 해석기가 하고, 여기서는 모드·기간 불변식을 다시 확인한다).
 * 모드: [fileId] 정확 조회(다른 조건 없음) / 그 밖은 업무일 기간 `[businessDateFrom, businessDateTo]`(양끝 포함, 최대 [MAX_PERIOD_DAYS]일 — I-3) 필수.
 * [needsAttention] = "확인 필요" 사유가 하나 이상인 파일만(§4.4).
 */
data class AcquisitionFileSearchCriteria(
    val businessDateFrom: LocalDate? = null,
    val businessDateTo: LocalDate? = null,
    val fileId: String? = null,
    val acquirerCode: String? = null,
    val environment: String? = null,
    val transmissionState: String? = null,
    val receiptState: String? = null,
    val resultState: String? = null,
    val needsAttention: Boolean = false,
    val after: AcquisitionFileCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit" }
        require((businessDateFrom == null) == (businessDateTo == null)) { "period needs both ends" }
        if (businessDateFrom != null && businessDateTo != null) {
            require(!businessDateTo.isBefore(businessDateFrom) && !businessDateTo.isAfter(businessDateFrom.plusDays(MAX_PERIOD_DAYS - 1))) { "period" }
        }
        if (fileId != null) {
            require(
                businessDateFrom == null && acquirerCode == null && environment == null && transmissionState == null && receiptState == null &&
                    resultState == null && !needsAttention && after == null,
            ) { "exact lookup" }
        } else {
            require(businessDateFrom != null) { "period is required" }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /** 기간 상한(I-3 — **Demo 값, 운영값 아님**, LIVE Gate — TXN-OPS-001과 같은 값). 양끝 포함 일수. */
        const val MAX_PERIOD_DAYS = 31L
    }
}

/** 파일 한 쪽. */
data class AcquisitionFilePage(val items: List<AcquisitionFileListItem>, val nextCursor: AcquisitionFileCursor?)

/** 레코드 쪽 조건(I-6): [status](선택 — 레코드 현재 상태 6값 중 하나), `seq > afterSeq` 오름차순 [limit]행. */
data class AcquisitionRecordSearchCriteria(val status: String? = null, val afterSeq: Int = 0, val limit: Int = DEFAULT_LIMIT) {
    init {
        require(status == null || status in RECORD_STATUSES) { "status" }
        require(afterSeq >= 0) { "afterSeq" }
        require(limit in 1..MAX_LIMIT) { "limit" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /** 레코드 현재 상태(`RecordResultRule.status`와 같은 정의 — AcquisitionFileViewReaderSpec 동치 시험). */
        val RECORD_STATUSES = setOf("AWAITING", "ACCEPTED", "REJECTED", "FILE_REJECTED", "UNKNOWN", "CONFLICT")
    }
}

/** 레코드 한 쪽. [nextCursor] = 다음 쪽의 `afterSeq`. */
data class AcquisitionRecordPage(val items: List<AcquisitionRecordView>, val nextCursor: Int?)

/** 조회 실패(결정 아님 — 503). 원인 문구는 담지 않는다(드라이버 원문에 값이 섞일 수 있다). */
sealed class AcquisitionViewFailure(message: String) : RuntimeException(message, null, false, false) {
    /** 질의 시간 상한 초과(MySQL 3024 — §3.2). */
    class TimedOut : AcquisitionViewFailure("acquisition view query timed out")

    /** 그 밖 저장소 접근 실패. */
    class Unavailable : AcquisitionViewFailure("acquisition view store unavailable")
}
