package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.OperatorApprovalId
import java.time.Instant

/** 기록 결정 조회 행(비권위 — §4.3 결정 조회·목록). [decision]은 저장 값, [cancellationStatus]는 STARTED일 때 현재 취소 상태. [seq]·[recordedAt]은 DB 값. */
data class OperatorDecisionRow(
    val seq: Long,
    val decision: OperatorApprovalDecision,
    val cancellationStatus: CancellationStatus?,
    val recordedAt: Instant,
)

/** 결정 행 없이 approvalId로 남은 옛 운영자 취소 행(I-29 — legacy STARTED). 지문·내용 hash는 옛 행에 없어 담지 않는다. */
data class LegacyOperatorCancellationRow(
    val approvalId: OperatorApprovalId,
    val cancellationId: CancellationId,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedTargetFingerprint: String,
    val status: CancellationStatus,
    val openedAt: Instant,
)

/** 결정 목록 한 페이지(D-OPS-008, §4.17). [serverTime]은 같은 읽기에서 읽은 DB 시각(`NOW(3)`), 행은 `seq > watermark` 오름차순. */
data class OperatorDecisionPage(val rows: List<OperatorDecisionRow>, val serverTime: Instant)

/**
 * 운영자 결정 조회 Port(jOOQ, 비권위 — 판정에 쓰지 않는다. 사용처는 조회 서비스뿐 — ArchUnit). 관리 조회 SELECT는 질의 시간 상한(`MAX_EXECUTION_TIME`, §4.7)을 갖고 초과는
 * [PersistenceFailure.Unavailable]이 아니라 [QueryTimedOut]으로 던진다.
 */
interface OperatorDecisionReader {
    fun find(approvalId: OperatorApprovalId): OperatorDecisionRow?

    fun findLegacy(approvalId: OperatorApprovalId): LegacyOperatorCancellationRow?

    fun after(watermark: Long, limit: Int): OperatorDecisionPage
}

/** 결정 목록 안정 지연(§4.17 — Demo 2분, 시험 2초, 운영값 LIVE Gate). */
data class OperatorDecisionListSettings(val stableDelay: java.time.Duration) {
    init {
        require(!stableDelay.isNegative) { "stable delay must not be negative" }
    }
}

/** 관리 조회가 질의 시간 상한을 넘었다(MySQL 3024 — §4.7). 결과는 503(결정 아님). */
class QueryTimedOut(cause: Throwable?) : RuntimeException("admin read exceeded its execution time limit", cause, false, false)
