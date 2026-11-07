package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 매입 파일 Aggregate(ACQUIRING-001 §4.1). 상태는 서로 추정하지 않는 세 축이다(INV-12, D-ACQ-004):
 * 전송([transmissionState] — 정산의 "매입 요청(외부기관 전송 완료)" 근거는 TRANSMITTED뿐), 기관 수신 ACK([receiptState] — 매입 확정 아님),
 * 레코드 결과([resultState] — ACCEPTED = 매입 확정, 레코드별 결과는 별도 append-only 기록).
 *
 * [seal]이 대상 레코드를 정해진 순서로 번호 매기고 바이트를 렌더링해 hash를 정한다. 봉인 뒤 바이트·membership은 바뀌지 않는다
 * (같은 파일 ID에 다른 내용 금지 — 기준선 §5.4-3). 배치2(2-D): 전송 축 전이 [claimTransmission]·[completeTransmission](lease + [version] 기대 version 비교 —
 * 저장 Port가 version이 다르면 갱신을 거부한다). 전송은 언제나 **같은 파일 ID·같은 봉인 바이트**다(새 ID 재전송 없음 — I-10).
 */
class AcquisitionFile private constructor(
    val header: AcquisitionFileHeader,
    val cutoffAt: Instant,
    val totals: AcquisitionTotals,
    val contentSha256: String,
    val contentBytes: Int,
    val transmissionState: TransmissionState,
    val receiptState: ReceiptState,
    val resultState: ResultState,
    /** TRANSMITTING일 때만 있다: 이 시각까지 전송 시도가 파일을 점유한다(만료 뒤 다른 시도가 확인 모드로 이어받는다). */
    val transmissionLeaseUntil: Instant?,
    /** TRANSMITTED가 된 시각(= "매입 요청(전송 완료)" 근거 시각). */
    val transmittedAt: Instant?,
    /** 저장 행의 version(@Version). 전이는 이 값을 기대 version으로 저장한다. */
    val version: Long,
) {
    val id: AcquisitionFileId get() = header.fileId

    init {
        if (!SHA256_HEX.matches(contentSha256)) throw AcquisitionContractViolation("content hash is a lower-case SHA-256 hex")
        if (contentBytes < 1) throw AcquisitionContractViolation("a sealed file has content")
        if ((transmissionState == TransmissionState.TRANSMITTING) != (transmissionLeaseUntil != null)) {
            throw AcquisitionContractViolation("only a transmitting file holds a transmission lease")
        }
        if ((transmissionState == TransmissionState.TRANSMITTED) != (transmittedAt != null)) {
            throw AcquisitionContractViolation("only a transmitted file has a transmission time")
        }
        if (version < 0) throw AcquisitionContractViolation("version must not be negative")
    }

    /**
     * 전송 점유(2-D). SEALED → TRANSMITTING(복귀 상태 SEALED), TRANSMISSION_UNKNOWN 또는 lease가 지난 TRANSMITTING → TRANSMITTING(복귀 상태 TRANSMISSION_UNKNOWN —
     * 앞 시도가 rename까지 갔는지 모르므로 최종명 확인이 먼저다). 그 밖은 점유하지 않는다.
     */
    fun claimTransmission(now: Instant, leaseUntil: Instant): TransmissionClaim {
        if (!leaseUntil.isAfter(now)) throw AcquisitionContractViolation("a transmission lease ends after its claim")
        val resume = when (transmissionState) {
            TransmissionState.SEALED -> TransmissionState.SEALED
            TransmissionState.TRANSMISSION_UNKNOWN -> TransmissionState.TRANSMISSION_UNKNOWN
            TransmissionState.TRANSMITTING ->
                if (now.isBefore(checkNotNull(transmissionLeaseUntil))) return TransmissionClaim.NotClaimable(NotClaimableReason.IN_FLIGHT)
                else TransmissionState.TRANSMISSION_UNKNOWN
            TransmissionState.TRANSMITTED -> return TransmissionClaim.NotClaimable(NotClaimableReason.ALREADY_TRANSMITTED)
            TransmissionState.TRANSMISSION_QUARANTINED -> return TransmissionClaim.NotClaimable(NotClaimableReason.QUARANTINED)
            TransmissionState.TRANSMISSION_WITHHELD -> return TransmissionClaim.NotClaimable(NotClaimableReason.WITHHELD)
        }
        return TransmissionClaim.Claimed(copyTransmission(TransmissionState.TRANSMITTING, leaseUntil, null), resume)
    }

    /**
     * 전송 보류(ACQ-ADMIN-001 B2 — §15 L-9, D-ACQ-014 "세대를 넘는 ACCEPTED 하나"). 재매입 파일의 레코드가 다른 세대에서 이미 매입 확정(표지)이면 이 파일을 보내지 않는다:
     * 점유할 수 있는 상태(SEALED·TRANSMISSION_UNKNOWN·lease가 지난 TRANSMITTING)에서만 TRANSMISSION_WITHHELD로 간다(호출자 = 전송 점유 TX, 잠금 아래 표지를 다시 본 뒤).
     * 보류는 종결이다 — 다시 점유하지 않고(확인 먼저 재업로드도 없음) 대사로 넘긴다. 정기 파일(세대 0)은 다른 세대가 앞서 있을 수 없으므로 보류 대상이 아니다.
     */
    fun withholdTransmission(now: Instant): AcquisitionFile {
        if (header.originalFileId == null) throw AcquisitionContractViolation("only a re-acquisition file is withheld")
        val claimable = when (transmissionState) {
            TransmissionState.SEALED, TransmissionState.TRANSMISSION_UNKNOWN -> true
            TransmissionState.TRANSMITTING -> !now.isBefore(checkNotNull(transmissionLeaseUntil))
            TransmissionState.TRANSMITTED, TransmissionState.TRANSMISSION_QUARANTINED, TransmissionState.TRANSMISSION_WITHHELD -> false
        }
        if (!claimable) throw AcquisitionContractViolation("only a file that could be claimed is withheld")
        return copyTransmission(TransmissionState.TRANSMISSION_WITHHELD, null, null)
    }

    /**
     * 전송 시도 결과 반영(2-D). [resumeState] = 점유 때 정한 복귀 상태. 전달 확인 → TRANSMITTED, 전달 안 됨(rename 전 실패 등 확실) → 복귀 상태,
     * 결과 모름 → TRANSMISSION_UNKNOWN, 같은 ID 다른 내용 → TRANSMISSION_QUARANTINED(격리 — 자동 처리 없음).
     */
    fun completeTransmission(outcome: TransmissionOutcome, resumeState: TransmissionState, now: Instant): AcquisitionFile {
        if (transmissionState != TransmissionState.TRANSMITTING) throw AcquisitionContractViolation("only a claimed (transmitting) file completes a transmission")
        if (resumeState != TransmissionState.SEALED && resumeState != TransmissionState.TRANSMISSION_UNKNOWN) {
            throw AcquisitionContractViolation("a transmission resumes to SEALED or TRANSMISSION_UNKNOWN")
        }
        return when (outcome) {
            is TransmissionOutcome.Delivered -> copyTransmission(TransmissionState.TRANSMITTED, null, now)
            is TransmissionOutcome.NotDelivered -> copyTransmission(resumeState, null, null)
            is TransmissionOutcome.Unknown -> copyTransmission(TransmissionState.TRANSMISSION_UNKNOWN, null, null)
            TransmissionOutcome.ContentMismatch -> copyTransmission(TransmissionState.TRANSMISSION_QUARANTINED, null, null)
        }
    }

    private fun copyTransmission(state: TransmissionState, leaseUntil: Instant?, at: Instant?) = AcquisitionFile(
        header, cutoffAt, totals, contentSha256, contentBytes, state, receiptState, resultState, leaseUntil, at, version,
    )

    /**
     * 기관 응답 반영(2-F — 수신 축). [receiptAfter] = [AcquirerResponseRule]이 정한 수신 축. [confirmsTransmission] = 응답이 봉인 hash와 일치했다(I-9 ACK_HASH_MATCH):
     * 전송 축이 SEALED·TRANSMISSION_UNKNOWN이면 TRANSMITTED(전송 완료 시각 = [now]). 전송 중(TRANSMITTING)인 파일은 호출자가 응답 처리를 미룬다(lease·@Version
     * 경합을 만들지 않는다 — 그 시도가 확인으로 끝낸다). TRANSMITTED·격리는 그대로다.
     */
    fun recordResponse(receiptAfter: ReceiptState, confirmsTransmission: Boolean, now: Instant): AcquisitionFile {
        if (transmissionState == TransmissionState.TRANSMITTING) throw AcquisitionContractViolation("responses of a transmitting file are deferred")
        if (transmissionState == TransmissionState.TRANSMISSION_QUARANTINED && (confirmsTransmission || receiptAfter != receiptState)) {
            // 리뷰 m-2: 전송 축 격리 파일의 응답은 아무 축도 바꾸지 않는다(격리 기록만).
            throw AcquisitionContractViolation("responses of a transmission-quarantined file change nothing")
        }
        val allowed = when (receiptState) {
            ReceiptState.NONE -> true
            ReceiptState.RECEIVED -> receiptAfter == ReceiptState.RECEIVED || receiptAfter == ReceiptState.RECEIPT_CONFLICT
            ReceiptState.FILE_REJECTED -> receiptAfter == ReceiptState.FILE_REJECTED || receiptAfter == ReceiptState.RECEIPT_CONFLICT
            ReceiptState.RECEIPT_CONFLICT -> receiptAfter == ReceiptState.RECEIPT_CONFLICT
        }
        if (!allowed) throw AcquisitionContractViolation("receipt axis transition is not allowed")
        // (ACQ-ADMIN-001 B2 — I-27) 보류 파일(TRANSMISSION_WITHHELD)은 응답이 와도 전송 축을 올리지 않는다(수신·결과 축만 기록 — 그 파일이 기관에 갔는지는 대사가 정한다).
        val promote = confirmsTransmission && (transmissionState == TransmissionState.SEALED || transmissionState == TransmissionState.TRANSMISSION_UNKNOWN)
        return AcquisitionFile(
            header, cutoffAt, totals, contentSha256, contentBytes,
            if (promote) TransmissionState.TRANSMITTED else transmissionState, receiptAfter, resultState, transmissionLeaseUntil,
            if (promote) now else transmittedAt, version,
        )
    }

    /**
     * 결과 축 요약 전이(2-F). AWAITING → COMPLETE(모든 레코드 결정)·DEADLINE_PASSED(결과 마감 — 미결 레코드 UNKNOWN), DEADLINE_PASSED → COMPLETE(늦은 결과로
     * 모두 결정 — D-ACQ-010, UNKNOWN 행은 보존). COMPLETE는 종결.
     */
    fun withResultState(next: ResultState): AcquisitionFile {
        if (next == resultState) return this
        val allowed = when (resultState) {
            ResultState.AWAITING -> next == ResultState.COMPLETE || next == ResultState.DEADLINE_PASSED
            ResultState.DEADLINE_PASSED -> next == ResultState.COMPLETE
            ResultState.COMPLETE -> false
        }
        if (!allowed) throw AcquisitionContractViolation("result axis transition is not allowed")
        return AcquisitionFile(
            header, cutoffAt, totals, contentSha256, contentBytes, transmissionState, receiptState, next, transmissionLeaseUntil, transmittedAt, version,
        )
    }

    /**
     * 저장된 바이트가 저장된 헤더·레코드로 다시 렌더링한 바이트와 같은가(재시작 시 봉인 무결성 검사 — Implementer 해석 I-11). 파일 행 hash·바이트 행 hash
     * ([storedContentSha256])·바이트 실제 SHA-256·재렌더링 hash·크기·합계가 모두 같아야 한다(리뷰 NIT — 바이트 행 hash 열도 대조).
     */
    fun matches(records: List<AcquisitionRecord>, storedContent: ByteArray, storedContentSha256: String): Boolean {
        val rendered = AcquisitionFileFormat.render(header, records)
        return rendered.sha256 == contentSha256 && storedContentSha256 == contentSha256 && AcquisitionFileFormat.sha256(storedContent) == contentSha256 &&
            rendered.content.contentEquals(storedContent) && storedContent.size == contentBytes && rendered.totals == totals
    }

    override fun toString(): String = "AcquisitionFile(id=$id, transmission=$transmissionState, sha256=$contentSha256)"

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * 봉인: [included]를 (승인 시각, 거래 ID) 순으로 SALE 레코드 1..n, [cancels](D-ACQ-009 — 2-F)를 (취소 시각, 거래 ID) 순으로 그 뒤 CANCEL 레코드로 만들고
         * 렌더링한다. 결과 = (Aggregate, 렌더링 — 레코드·바이트).
         */
        fun seal(
            header: AcquisitionFileHeader,
            cutoffAt: Instant,
            included: List<CandidateDecision.Include>,
            cancels: List<CancelDecision.Include> = emptyList(),
        ): Pair<AcquisitionFile, RenderedAcquisitionFile> {
            if (included.map { it.sale.transactionId }.toSet().size != included.size) throw AcquisitionContractViolation("a transaction is a member once")
            if (cancels.map { it.candidate.transactionId }.toSet().size != cancels.size) throw AcquisitionContractViolation("a cancellation is a member once")
            cancels.forEach {
                if (it.candidate.original.acquirerCode != header.fileId.acquirerCode || it.candidate.original.environment != header.fileId.environment) {
                    throw AcquisitionContractViolation("every member belongs to the file's lane")
                }
                if (it.candidate.cancelledAt.isAfter(cutoffAt)) throw AcquisitionContractViolation("every cancellation member was cancelled by the cutoff")
            }
            included.forEach {
                if (it.sale.acquirerCode != header.fileId.acquirerCode || it.sale.environment != header.fileId.environment) {
                    throw AcquisitionContractViolation("every member belongs to the file's lane")
                }
                if (it.sale.approvedAt.isAfter(cutoffAt)) throw AcquisitionContractViolation("every member was approved by the cutoff")
            }
            val sales = included.sortedWith(compareBy({ it.sale.approvedAt }, { it.sale.transactionId }))
                .mapIndexed { i, include -> include.toRecord(i + 1) }
            val records = sales + cancels.sortedWith(compareBy({ it.candidate.cancelledAt }, { it.candidate.transactionId }))
                .mapIndexed { i, include -> include.toRecord(sales.size + i + 1) }
            val rendered = AcquisitionFileFormat.render(header, records)
            return restore(
                header, cutoffAt, rendered.totals, rendered.sha256, rendered.content.size, TransmissionState.SEALED, ReceiptState.NONE, ResultState.AWAITING,
            ) to rendered
        }

        /**
         * 재매입 봉인(ACQ-ADMIN-001 B1 — D-ACQ-004·D-ACQ-011): 같은 lane·업무일의 다음 버전([header].originalFileId 필수). [records]는 [ReacquisitionExecutionRule]이 만든
         * 재매입 레코드(순번 1..n, generation ≥ 1, 원 레코드 참조)다. 원 파일의 마감 시각을 그대로 쓴다(대상은 원 파일 membership이다).
         */
        fun sealReacquisition(header: AcquisitionFileHeader, cutoffAt: Instant, records: List<AcquisitionRecord>): Pair<AcquisitionFile, RenderedAcquisitionFile> {
            if (header.originalFileId == null) throw AcquisitionContractViolation("a re-acquisition file refers to its original file")
            if (records.isEmpty()) throw AcquisitionContractViolation("a re-acquisition file has records")
            if (records.map { it.seq } != (1..records.size).toList()) throw AcquisitionContractViolation("re-acquisition records are numbered 1..n")
            if (records.any { it.generation < 1 || it.reacquisitionOf == null }) throw AcquisitionContractViolation("every record of a re-acquisition file is a re-acquisition")
            if (records.map { it.transactionId to it.type }.toSet().size != records.size) throw AcquisitionContractViolation("a transaction is a member once")
            // ACQ-ADMIN-001 B2(B1-F 리뷰 n-3): 헤더가 originalFileId = 같은 lane·업무일의 더 낮은 버전을 지키고(AcquisitionFileHeader.init), 여기서 각 레코드의 원 참조가
            // 바로 그 원 파일의 레코드(`{originalFileId}#{seq ≥ 1}`)인지 검사한다 — 호출 경로가 하나뿐이어도 다른 lane·업무일·버전 레코드를 섞어 봉인하지 못하게 한다.
            val original = header.originalFileId
            if (records.any { !refersTo(it.reacquisitionOf, original) }) throw AcquisitionContractViolation("every record re-acquires a record of the original file")
            val rendered = AcquisitionFileFormat.render(header, records)
            return restore(
                header, cutoffAt, rendered.totals, rendered.sha256, rendered.content.size, TransmissionState.SEALED, ReceiptState.NONE, ResultState.AWAITING,
            ) to rendered
        }

        private val RECORD_REF = Regex("^(.+)#([1-9][0-9]{0,8})$")

        private fun refersTo(reacquisitionOf: String?, original: AcquisitionFileId): Boolean {
            val m = reacquisitionOf?.let(RECORD_REF::matchEntire) ?: return false
            return m.groupValues[1] == original.value
        }

        /** 저장된 상태 복원(persistence Mapper 전용 — 불변식은 init이 다시 검사한다). */
        @Suppress("LongParameterList")
        fun restore(
            header: AcquisitionFileHeader,
            cutoffAt: Instant,
            totals: AcquisitionTotals,
            contentSha256: String,
            contentBytes: Int,
            transmissionState: TransmissionState,
            receiptState: ReceiptState,
            resultState: ResultState,
            transmissionLeaseUntil: Instant? = null,
            transmittedAt: Instant? = null,
            version: Long = 0,
        ) = AcquisitionFile(
            header, cutoffAt, totals, contentSha256, contentBytes, transmissionState, receiptState, resultState, transmissionLeaseUntil, transmittedAt, version,
        )
    }
}

/**
 * 전송 축(DB CHECK와 같은 집합). TRANSMISSION_QUARANTINED = 같은 파일 ID 최종명에 다른 내용이 있다(격리 — 대사·운영 확인 전 자동 처리 없음, I-23).
 * TRANSMISSION_WITHHELD(ACQ-ADMIN-001 B2 — §15 L-9) = 재매입 파일의 레코드가 다른 세대에서 이미 매입 확정(표지)이라 전송하지 않고 보류했다(D-ACQ-014 — 대사, 자동 해제 없음).
 */
enum class TransmissionState { SEALED, TRANSMITTING, TRANSMITTED, TRANSMISSION_UNKNOWN, TRANSMISSION_QUARANTINED, TRANSMISSION_WITHHELD }

/** 기관 수신(ACK) 축. RECEIVED는 매입 확정이 아니다(architecture 04 금지사항). */
enum class ReceiptState { NONE, RECEIVED, FILE_REJECTED, RECEIPT_CONFLICT }

/** 레코드 결과 축(파일 단위 요약). */
enum class ResultState { AWAITING, COMPLETE, DEADLINE_PASSED }
