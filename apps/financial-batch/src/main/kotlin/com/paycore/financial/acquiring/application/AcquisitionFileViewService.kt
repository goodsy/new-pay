package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.AcquisitionAttention
import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileDetail
import com.paycore.financial.acquiring.application.provided.AcquisitionFileListItem
import com.paycore.financial.acquiring.application.provided.AcquisitionFilePage
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSummary
import com.paycore.financial.acquiring.application.provided.AcquisitionFileViewQuery
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordPage
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordView
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileViewReader
import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
import com.paycore.financial.acquiring.domain.ReacquisitionBadge
import com.paycore.financial.acquiring.domain.ReacquisitionBadgeRule
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 매입 현황 조회 Use Case(ACQ-VIEW-001 §3·§4). TX를 열지 않는다 — 각 읽기는 Reader가 한 읽기 전용 REPEATABLE READ TX로 한다. 여기서는 keyset 다음 쪽 판정(limit + 1행)과
 * "확인 필요" 사유·재매입 검토 표시를 계산한다(§4.4 — I-4: 화면은 계산하지 않는다). RESULT_OVERDUE 기준점 = 지금 − 결과 마감(설정 없으면 계산하지 않는다 — fail-closed:
 * 임의 기본값으로 지연을 단정하지 않는다). 사유는 표시 분류이며 어떤 상태도 바꾸지 않는다.
 */
@Service
class AcquisitionFileViewService(
    private val reader: AcquisitionFileViewReader,
    private val resultPolicy: AcquisitionResultPolicy,
    private val clock: AcquiringClock,
) : AcquisitionFileViewQuery {

    override fun search(criteria: AcquisitionFileSearchCriteria): AcquisitionFilePage {
        val overdueBefore = overdueBefore()
        val rows = reader.search(criteria, overdueBefore, criteria.limit + 1)
        val shown = rows.take(criteria.limit)
        return AcquisitionFilePage(shown.map { item(it, overdueBefore) }, if (rows.size > criteria.limit) cursorOf(shown.last()) else null)
    }

    override fun detail(fileId: String, records: AcquisitionRecordSearchCriteria): AcquisitionFileDetail? {
        val readAt = clock.now()
        val rows = reader.detail(fileId, records, records.limit + 1) ?: return null
        return AcquisitionFileDetail(
            file = item(rows.summary, overdueBefore(readAt)),
            cutoffPolicyVersion = rows.cutoffPolicyVersion,
            cutoffAt = rows.cutoffAt,
            contentSha256 = rows.contentSha256,
            contentBytes = rows.contentBytes,
            transmissionLeaseUntil = rows.transmissionLeaseUntil,
            updatedAt = rows.updatedAt,
            attempts = rows.attempts,
            responses = rows.responses,
            records = page(rows.records, records.limit),
            readAt = readAt,
        )
    }

    override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria): AcquisitionRecordPage? =
        reader.records(fileId, criteria, criteria.limit + 1)?.let { page(it, criteria.limit) }

    /** RESULT_OVERDUE 기준점(지금 − 결과 마감). 결과 마감 설정이 없으면 null(계산하지 않음). */
    private fun overdueBefore(now: Instant = clock.now()): Instant? = resultPolicy.resultDeadline?.let { now.minus(it) }

    companion object {
        /** "확인 필요" 사유 계산(§4.4). Reader의 needsAttention SQL 조건과 같은 정의(AcquisitionFileViewReaderSpec이 둘을 대조한다). */
        fun attentionOf(s: AcquisitionFileSummary, overdueBefore: Instant?): List<AcquisitionAttention> = AcquisitionAttention.entries.filter { reason ->
            when (reason) {
                AcquisitionAttention.TRANSMISSION_UNKNOWN -> s.transmissionState == TRANSMISSION_UNKNOWN
                AcquisitionAttention.TRANSMISSION_QUARANTINED -> s.transmissionState == TRANSMISSION_QUARANTINED
                AcquisitionAttention.TRANSMISSION_WITHHELD -> s.transmissionState == TRANSMISSION_WITHHELD
                AcquisitionAttention.RECEIPT_CONFLICT -> s.receiptState == RECEIPT_CONFLICT
                AcquisitionAttention.RESPONSE_QUARANTINED -> s.quarantinedResponses > 0
                AcquisitionAttention.RECORD_UNKNOWN -> s.recordStatusCounts.unknown > 0
                AcquisitionAttention.RECORD_CONFLICT -> s.recordStatusCounts.conflict > 0
                AcquisitionAttention.RESULT_DEADLINE_PASSED -> s.resultState == DEADLINE_PASSED
                AcquisitionAttention.RESULT_OVERDUE -> overdueBefore != null && s.resultState == AWAITING && s.transmissionState == TRANSMITTED &&
                    s.transmittedAt?.let { !it.isAfter(overdueBefore) } == true
            }
        }

        /**
         * 재매입 배지 3상태(ACQ-ADMIN-001 rev 0.7 — §5.1 L-9, I-9, MINOR-6): Reader가 센 레코드 단계 건수에 Domain 규칙 [ReacquisitionBadgeRule]을 적용한다. "확인 필요"가 아니라
         * 정보 표시다. (rev 0.6까지의 ACQ-VIEW-001 I-5 정의 "파일 전체 거절 또는 REJECTED·FILE_REJECTED 레코드가 있음"은 새 세대가 매입 확정된 뒤에도 배지를 남겼다 — 그 정의를
         * "어느 세대에도 확정·불요 종결이 없고 더 높은 세대가 없는 거절 레코드"로 좁혔다. 파일 전체 거절은 레코드마다 FILE_REJECTED 결과 행을 남기므로 같은 규칙에 들어온다.)
         */
        fun reacquisitionBadge(s: AcquisitionFileSummary): ReacquisitionBadge = ReacquisitionBadgeRule.fileBadge(s.reacquisitionCandidates, s.reacquisitionInProgress)

        fun reacquisitionCandidate(s: AcquisitionFileSummary): Boolean = reacquisitionBadge(s) == ReacquisitionBadge.CANDIDATE

        internal fun page(rows: List<AcquisitionRecordView>, limit: Int): AcquisitionRecordPage {
            val shown = rows.take(limit)
            return AcquisitionRecordPage(shown, if (rows.size > limit) shown.last().seq else null)
        }

        // 저장 상태 값(V5·V7 CHECK — 표시 분류에만 쓰고 상태를 바꾸지 않는다).
        private const val TRANSMITTED = "TRANSMITTED"
        private const val TRANSMISSION_UNKNOWN = "TRANSMISSION_UNKNOWN"
        private const val TRANSMISSION_QUARANTINED = "TRANSMISSION_QUARANTINED"
        private const val TRANSMISSION_WITHHELD = "TRANSMISSION_WITHHELD"
        private const val RECEIPT_CONFLICT = "RECEIPT_CONFLICT"
        private const val AWAITING = "AWAITING"
        private const val DEADLINE_PASSED = "DEADLINE_PASSED"

        internal fun cursorOf(s: AcquisitionFileSummary) = AcquisitionFileCursor(s.businessDate, s.fileId)

        internal fun item(s: AcquisitionFileSummary, overdueBefore: Instant?): AcquisitionFileListItem {
            val badge = reacquisitionBadge(s)
            return AcquisitionFileListItem(s, attentionOf(s, overdueBefore), badge == ReacquisitionBadge.CANDIDATE, badge == ReacquisitionBadge.IN_PROGRESS)
        }
    }
}
