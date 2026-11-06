package com.paycore.simulator.acquirer.domain

import java.time.Instant
import java.time.LocalDate

enum class ReceivedRecordType { SALE, CANCEL }

/** 받은 매입 파일 헤더(파일 계약 v1 `H` 11필드 — 형식 버전 "1"과 파일 ID에서 나오는 매입사·환경·업무일·버전은 [ref]로 묶었다). */
data class ReceivedHeader(
    val ref: AcquisitionFileRef,
    val senderId: String,
    val originalFileId: String?,
    val sealedAt: Instant,
    val cutoffPolicyVersion: String,
)

/**
 * 받은 매입 레코드(파일 계약 v1 `D` 21필드). 금액은 KRW minor 정수다. SALE은 [recordAmountMinor] = 승인액 − 상계 취소액(D-ACQ-002),
 * CANCEL은 원거래 참조가 있고 상계가 없다 — [AcquisitionFileV1Reader]가 그 모양을 확인한 값만 이 타입이 된다.
 */
data class ReceivedRecord(
    val seq: Int,
    val type: ReceivedRecordType,
    val transactionId: String,
    val originalTransactionId: String?,
    val merchantRef: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val originalProviderTransactionId: String?,
    val institutionTransactionDate: LocalDate,
    val eventAt: Instant,
    val currency: String,
    val approvedAmountMinor: Long,
    val nettedCancelAmountMinor: Long,
    val recordAmountMinor: Long,
    val planKind: String,
    val installmentMonths: Int?,
    val cardType: String,
    val issuerCode: String,
    val generation: Int,
    val reacquisitionOf: String?,
)

/** 받은 매입 파일 트레일러(파일 계약 v1 `T` 8필드). */
data class ReceivedTrailer(
    val recordCount: Int,
    val saleCount: Int,
    val saleAmountTotal: Long,
    val cancelCount: Int,
    val cancelAmountTotal: Long,
    val netAmount: Long,
    val bodySha256: String,
)

/** 형식·hash·합계 검증을 통과한 매입 파일 한 개. [contentSha256] = 받은 파일 전체 바이트 SHA-256(FINANCIAL 봉인 hash와 같아야 한다 — ACK로 돌려준다). */
data class ReceivedAcquisitionFile(
    val header: ReceivedHeader,
    val records: List<ReceivedRecord>,
    val trailer: ReceivedTrailer,
    val contentSha256: String,
    val contentBytes: Int,
)
