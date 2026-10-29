package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.OperatorApprovalId
import java.time.Instant

/**
 * 운영자 승인 결정 조회(ADMIN-PAYMENT-OPS-001 §4.3 L-5·§4.17 — 진입점은 ADMIN 채널 `OperatorDecisionController`만, ArchUnit). 비권위 읽기(jOOQ) — 판정은 실행 TX1·기한 확인 TX가 한다.
 */
interface OperatorDecisionQuery {
    /** approvalId의 기록 결정(없으면 NONE, 결정 행 없는 옛 취소 행은 legacy STARTED — I-29). */
    fun decision(approvalId: OperatorApprovalId): OperatorDecisionView

    /** `seq > watermark` 오름차순 최대 [OperatorDecisionList.MAX_ITEMS]행, 안정 watermark(§4.17). */
    fun after(watermark: Long): OperatorDecisionList
}

/** 결정 상태(§4.3). */
enum class OperatorDecisionState { STARTED, REJECTED, NONE }

/**
 * 결정 조회 응답 재료(§4.3 공통 필드 L-b). NONE이면 [state] 외 모두 null. [legacy] = 결정 행 없이 옛 취소 행에서 만든 STARTED(내용 hash 없음). 사유·근거 문구 없음.
 */
data class OperatorDecisionView(
    val state: OperatorDecisionState,
    val code: String? = null,
    val cancellationId: String? = null,
    val cancellationStatus: CancellationViewStatus? = null,
    val paymentId: String? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val requestedBy: String? = null,
    val approvedBy: String? = null,
    val approvedTargetFingerprint: String? = null,
    val contentHash: String? = null,
    val decidedAt: Instant? = null,
    val legacy: Boolean = false,
    val seq: Long? = null,
    val recordedAt: Instant? = null,
    val origin: String? = null,
    val approvalId: String? = null,
) {
    companion object {
        val NONE = OperatorDecisionView(OperatorDecisionState.NONE)
    }
}

/** 결정 목록 한 페이지. [nextWatermark]는 안정 구간의 마지막 seq(없으면 요청 watermark), [serverTime]은 DB 시각. */
data class OperatorDecisionList(val items: List<OperatorDecisionView>, val nextWatermark: Long, val serverTime: Instant) {
    companion object {
        const val MAX_ITEMS = 200
    }
}

/** 관리 조회·기한 확인의 일시 실패(결정 아님 → 503). */
sealed class OperatorQueryFailure(message: String, cause: Throwable?) : RuntimeException(message, cause, false, false) {
    /** 질의 시간 상한 초과(MySQL 3024 — §4.7). */
    class TimedOut(cause: Throwable?) : OperatorQueryFailure("admin read timed out", cause)

    /** 저장소 일시 실패. */
    class Unavailable(cause: Throwable?) : OperatorQueryFailure("admin read unavailable", cause)
}
