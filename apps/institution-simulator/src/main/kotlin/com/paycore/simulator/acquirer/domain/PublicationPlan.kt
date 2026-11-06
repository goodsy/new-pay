package com.paycore.simulator.acquirer.domain

import java.time.Instant

/** 결과 파일 한 줄(레코드 하나의 게시 결과 — 결과 중복·상충 시나리오에서는 기관 판정과 다른 값이 게시될 수 있다). */
data class PlannedResultLine(
    val recordSeq: Int,
    val transactionId: String,
    val recordType: ReceivedRecordType,
    val result: RecordResult,
    val reason: String?,
    val recordAmountMinor: Long,
) {
    init {
        require(recordSeq >= 1 && recordAmountMinor >= 1) { "a result line refers to a record and its amount" }
        require((result == RecordResult.REJECTED) == (reason != null)) { "a reason exists exactly for REJECTED" }
    }
}

/** 결과 파일 한 개의 계획(회차 [seq] = 01..99, 게시 예정 시각 [dueAt] = 결과 파일 producedAt). */
data class PlannedResultFile(val seq: Int, val dueAt: Instant, val lines: List<PlannedResultLine>) {
    init {
        require(seq in 1..AcquisitionFileRef.MAX_SEQ) { "result sequence must be 01..${AcquisitionFileRef.MAX_SEQ}" }
        require(lines.map { it.recordSeq } == lines.map { it.recordSeq }.distinct().sorted()) { "result lines are in record order without repetition" }
    }
}

/**
 * 수신 하나의 게시 계획(수신 TX 안에서 한 번 정해 기록한다 — Worker는 기록된 계획만 게시하므로 재시작·재게시가 같은 바이트를 만든다).
 * [ackDueAt] = null이면 ACK 유실 시나리오(DROP_ACK).
 */
data class PublicationPlan(val ackDueAt: Instant?, val resultFiles: List<PlannedResultFile>) {
    init {
        require(resultFiles.map { it.seq } == (1..resultFiles.size).toList()) { "result files are numbered 01.. without gaps" }
    }
}
