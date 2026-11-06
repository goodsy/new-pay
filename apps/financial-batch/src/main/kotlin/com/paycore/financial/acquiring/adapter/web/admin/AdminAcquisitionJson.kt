package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.application.provided.AcquisitionAttemptView
import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileDetail
import com.paycore.financial.acquiring.application.provided.AcquisitionFileListItem
import com.paycore.financial.acquiring.application.provided.AcquisitionFilePage
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordPage
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordView
import com.paycore.financial.acquiring.application.provided.AcquisitionResponseView
import java.time.Instant

/**
 * 매입 현황 응답 모양(ACQ-VIEW-001 §4.1–§4.3). 키 순서 고정, 값이 없는 칸도 `null`(ADMIN 허용 필드 검사 — fail-closed). 시각은 `…T…Z` ms 3자리 UTC, 업무일·기관 거래일은
 * `yyyy-MM-dd`. 숨기는 값(§4.5·D-ACQV-002)은 provided 타입에 칸이 없어 여기서 만들 수 없다.
 */
object AdminAcquisitionJson {

    fun page(p: AcquisitionFilePage): Map<String, Any?> = linkedMapOf("items" to p.items.map(::item), "nextCursor" to p.nextCursor?.let(::cursor))

    fun records(p: AcquisitionRecordPage): Map<String, Any?> = linkedMapOf("items" to p.items.map(::record), "nextCursor" to p.nextCursor)

    fun detail(d: AcquisitionFileDetail): Map<String, Any?> = linkedMapOf(
        "file" to item(d.file).apply {
            put("cutoffPolicyVersion", d.cutoffPolicyVersion)
            put("cutoffAt", time(d.cutoffAt))
            put("contentSha256", d.contentSha256)
            put("contentBytes", d.contentBytes)
            put("transmissionLeaseUntil", d.transmissionLeaseUntil?.let(::time))
            put("updatedAt", time(d.updatedAt))
        },
        "attempts" to linkedMapOf("total" to d.attempts.total, "latest" to d.attempts.latest.map(::attempt)),
        "responses" to linkedMapOf("total" to d.responses.total, "quarantined" to d.file.summary.quarantinedResponses, "latest" to d.responses.latest.map(::response)),
        "records" to records(d.records),
        "readAt" to time(d.readAt),
    )

    private fun cursor(c: AcquisitionFileCursor) = linkedMapOf("businessDate" to c.businessDate.toString(), "fileId" to c.fileId)

    private fun item(i: AcquisitionFileListItem): LinkedHashMap<String, Any?> = i.summary.let { s ->
        linkedMapOf(
            "fileId" to s.fileId, "acquirerCode" to s.acquirerCode, "environment" to s.environment, "businessDate" to s.businessDate.toString(),
            "fileVersion" to s.fileVersion, "originalFileId" to s.originalFileId, "recordCount" to s.recordCount, "saleCount" to s.saleCount,
            "saleAmountMinor" to s.saleAmountMinor, "cancelCount" to s.cancelCount, "cancelAmountMinor" to s.cancelAmountMinor, "netAmountMinor" to s.netAmountMinor,
            "currency" to s.currency, "transmissionState" to s.transmissionState, "receiptState" to s.receiptState, "resultState" to s.resultState,
            "sealedAt" to time(s.sealedAt), "transmittedAt" to s.transmittedAt?.let(::time),
            "recordStatusCounts" to s.recordStatusCounts.let { c ->
                linkedMapOf(
                    "awaiting" to c.awaiting, "accepted" to c.accepted, "rejected" to c.rejected, "fileRejected" to c.fileRejected, "unknown" to c.unknown,
                    "conflict" to c.conflict,
                )
            },
            "attention" to i.attention.map { it.name },
            "reacquisitionCandidate" to i.reacquisitionCandidate,
        )
    }

    private fun attempt(a: AcquisitionAttemptView) = linkedMapOf(
        "attemptNo" to a.attemptNo, "resumeState" to a.resumeState, "claimedAt" to time(a.claimedAt), "leaseUntil" to time(a.leaseUntil), "outcome" to a.outcome,
        "observed" to a.observed, "evidence" to a.evidence, "reason" to a.reason, "resultingState" to a.resultingState, "decidedAt" to a.decidedAt?.let(::time),
    )

    private fun response(r: AcquisitionResponseView) = linkedMapOf(
        "kind" to r.kind, "sequenceNo" to r.sequenceNo, "disposition" to r.disposition, "quarantineReason" to r.quarantineReason, "ackOutcome" to r.ackOutcome,
        "ackReasonCode" to r.ackReasonCode, "evidence" to r.evidence, "institutionAt" to r.institutionAt?.let(::time), "observedAt" to time(r.observedAt),
    )

    private fun record(r: AcquisitionRecordView) = linkedMapOf(
        "seq" to r.seq, "recordType" to r.recordType, "transactionId" to r.transactionId, "originalTransactionId" to r.originalTransactionId,
        "merchantAccountId" to r.merchantAccountId, "institutionTransactionDate" to r.institutionTransactionDate.toString(), "eventAt" to time(r.eventAt),
        "currency" to r.currency, "approvedAmountMinor" to r.approvedAmountMinor, "nettedCancelAmountMinor" to r.nettedCancelAmountMinor,
        "recordAmountMinor" to r.recordAmountMinor, "generation" to r.generation, "reacquisitionOf" to r.reacquisitionOf, "status" to r.status,
        "results" to r.results.map { x ->
            linkedMapOf(
                "entryNo" to x.entryNo, "result" to x.result, "observedResult" to x.observedResult, "reasonCode" to x.reasonCode, "source" to x.source,
                "responseRef" to x.responseRef?.let { ref -> linkedMapOf("kind" to ref.kind, "sequenceNo" to ref.sequenceNo) }, "recognizedAt" to time(x.recognizedAt),
            )
        },
    )

    private fun time(at: Instant): String = AdminChannelResponses.time(at)
}
