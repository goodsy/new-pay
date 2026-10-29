package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.OperatorDecisionList
import com.paycore.payment.payment.application.provided.OperatorDecisionQuery
import com.paycore.payment.payment.application.provided.OperatorDecisionState
import com.paycore.payment.payment.application.provided.OperatorDecisionView
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.application.required.LegacyOperatorCancellationRow
import com.paycore.payment.payment.application.required.OperatorDecisionKind
import com.paycore.payment.payment.application.required.OperatorDecisionListSettings
import com.paycore.payment.payment.application.required.OperatorDecisionReader
import com.paycore.payment.payment.application.required.OperatorDecisionRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.QueryTimedOut
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.OperatorApprovalId
import org.springframework.stereotype.Service

/**
 * 운영자 결정 조회(ADMIN-PAYMENT-OPS-001 §4.3·§4.17 — 비권위 jOOQ 읽기, 판정에 쓰지 않는다). 결정 목록의 안정 watermark: 반환 행을 seq 순으로 볼 때 DB `recorded_at ≤ serverTime − 안정 지연`인
 * 앞쪽 연속 구간의 마지막 seq(없으면 요청 watermark) — 커밋이 안정 지연보다 늦은 행은 증분에서 건너뛸 수 있고 ADMIN 전체 스캔이 최종 안전망이다.
 */
@Service
class OperatorDecisionQueryService(
    private val reader: OperatorDecisionReader,
    private val settings: OperatorDecisionListSettings,
) : OperatorDecisionQuery {

    override fun decision(approvalId: OperatorApprovalId): OperatorDecisionView = OperatorDecisionViews.of(reader, approvalId)

    override fun after(watermark: Long): OperatorDecisionList = OperatorDecisionViews.guard {
        require(watermark >= 0) { "watermark must not be negative" }
        val page = reader.after(watermark, OperatorDecisionList.MAX_ITEMS)
        val stableUntil = page.serverTime.minus(settings.stableDelay)
        val stable = page.rows.takeWhile { !it.recordedAt.isAfter(stableUntil) }
        OperatorDecisionList(page.rows.map(OperatorDecisionViews::of), stable.lastOrNull()?.seq ?: watermark, page.serverTime)
    }
}

/** 결정 조회 응답 재료 변환(조회 서비스·기한 확인 서비스 공용 — 실패는 [OperatorQueryFailure]). */
internal object OperatorDecisionViews {
    fun of(reader: OperatorDecisionReader, approvalId: OperatorApprovalId): OperatorDecisionView = guard {
        reader.find(approvalId)?.let(::of) ?: reader.findLegacy(approvalId)?.let(::legacy) ?: OperatorDecisionView.NONE
    }

    fun of(row: OperatorDecisionRow): OperatorDecisionView {
        val d = row.decision
        return OperatorDecisionView(
            state = if (d.kind == OperatorDecisionKind.STARTED) OperatorDecisionState.STARTED else OperatorDecisionState.REJECTED,
            code = d.rejectionCode,
            cancellationId = d.cancellationId?.value,
            cancellationStatus = row.cancellationStatus?.let(::status),
            paymentId = d.paymentId.value,
            amountMinor = d.amountMinor,
            currency = d.currency.value,
            requestedBy = d.requestedBy.value,
            approvedBy = d.approvedBy.value,
            approvedTargetFingerprint = d.approvedTargetFingerprint,
            contentHash = d.contentHash,
            decidedAt = d.decidedAt,
            legacy = false,
            seq = row.seq,
            recordedAt = row.recordedAt,
            origin = d.origin.name,
            approvalId = d.approvalId.value,
        )
    }

    private fun legacy(row: LegacyOperatorCancellationRow) = OperatorDecisionView(
        state = OperatorDecisionState.STARTED,
        cancellationId = row.cancellationId.value,
        cancellationStatus = status(row.status),
        paymentId = row.paymentId,
        amountMinor = row.amountMinor,
        currency = row.currency,
        requestedBy = row.requestedBy,
        approvedBy = row.approvedBy,
        approvedTargetFingerprint = row.approvedTargetFingerprint,
        decidedAt = row.openedAt,
        legacy = true,
        approvalId = row.approvalId.value,
    )

    private fun status(s: CancellationStatus): CancellationViewStatus = when (s) {
        CancellationStatus.OPEN -> CancellationViewStatus.IN_PROGRESS
        CancellationStatus.UNKNOWN -> CancellationViewStatus.UNKNOWN
        CancellationStatus.COMPLETED -> CancellationViewStatus.COMPLETED
        CancellationStatus.FAILED -> CancellationViewStatus.FAILED
    }

    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: QueryTimedOut) {
        throw OperatorQueryFailure.TimedOut(e)
    } catch (e: PersistenceFailure) {
        throw OperatorQueryFailure.Unavailable(e)
    }
}
