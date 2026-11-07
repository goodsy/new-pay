package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.ExclusionReason
import java.time.Instant
import java.time.LocalDate

/**
 * 매입 판정 기록 저장 Port(append-only — 상계·제외. CONVENTIONS §14 Store ①: 자기 식별자 없이 쌓이는 결정 기록). 호출자 TX 필수.
 */
interface AcquisitionMembershipStore {
    /** 주어진 승인 거래 중 이미 판정이 끝난 것(첫 매입 SALE membership 또는 제외 기록이 있는 것). */
    fun decidedSaleTransactionIds(transactionIds: Collection<String>): Set<String>

    /** 상계 기록(취소 하나는 한 번만 상계 — PK). */
    fun insertNettings(nettings: List<NettingRecord>)

    /** 제외 기록(승인 하나에 하나 — PK). */
    fun insertExclusions(exclusions: List<ExclusionRecord>)

    /** (2-F) 원승인 거래 → 그 첫 매입 SALE 레코드(generation 0 membership). 없는 승인은 빠진다. */
    fun saleMemberships(originalTransactionIds: Collection<String>): Map<String, SaleMembership>

    /** (2-F) 원승인 거래 → 이미 봉인한 CANCEL 레코드(generation 0) 금액 합. 없으면 빠진다. */
    fun cancelledAmounts(originalTransactionIds: Collection<String>): Map<String, Long>

    /** (2-F) 판정이 끝난 취소: 상계됨, CANCEL 레코드(generation 0) 있음, "취소 매입 불요" 기록 있음 중 하나. */
    fun decidedCancellationIds(cancellationTransactionIds: Collection<String>): Set<String>

    /** (2-F — D-ACQ-009) "취소 매입 불요" 기록(취소 하나에 하나 — PK, append-only). */
    fun insertCancelNotRequired(records: List<CancelNotRequiredRecord>)

    /**
     * (2-F — 대사 입력 무결성, §16 리뷰 NIT) 파일 [fileId]의 SALE 레코드 상계액 ≠ 그 레코드를 가리키는 상계 기록 합인 건수 + 그 lane·업무일 제외 기록의 승인액 ≠ 그 승인의
     * 상계 기록(파일 없음) 합인 건수. 0이어야 한다. (ACQ-ADMIN-001 rev 0.3 — 리뷰 MJ-1) 레코드 쪽은 세대 0만 센다(재매입 레코드를 가리키는 상계 기록은 없다).
     */
    fun nettingMismatches(fileId: AcquisitionFileId): Int

    /**
     * (ACQ-ADMIN-001 rev 0.3 — 리뷰 MJ-1, I-17) 파일 [fileId]의 재매입 SALE 레코드(세대 ≥ 1) 중 상계액 ≠ 그 원승인의 판정 끝난 취소 합(원 파일 상계 + 낮은 세대를 가리키는
     * "취소 매입 불요" + 세대 0 CANCEL 레코드)인 건수. 0이어야 한다.
     */
    fun reacquisitionNettingMismatches(fileId: AcquisitionFileId): Int

    /** (rev 1.1 — 리뷰 m-1) 원 SALE 레코드 [original]을 가리키는 "취소 매입 불요" 기록 수(원 SALE이 나중에 CONFLICT가 되면 대사·경보 대상). */
    fun notRequiredCancellationsOf(original: RecordRef): Int
}

/** 원승인의 첫 매입 SALE 레코드와 그 파일. */
data class SaleMembership(val fileId: AcquisitionFileId, val record: com.paycore.financial.acquiring.domain.AcquisitionRecord)

/** "취소 매입 불요"(D-ACQ-009 — 원 SALE REJECTED·파일 거절). [originalFileId]·[originalSeq] = 원 SALE 레코드, [businessDate] = 판정한 봉인의 업무일. */
data class CancelNotRequiredRecord(
    val cancellationTransactionId: String,
    val originalTransactionId: String,
    val originalFileId: AcquisitionFileId,
    val originalSeq: Int,
    val amountMinor: Long,
    val currency: String,
    val businessDate: LocalDate,
    val decidedAt: Instant,
)

/** 취소 [cancellationTransactionId]를 원승인 [originalTransactionId]의 매입에서 상계했다. [fileId]·[seq] = 그 SALE 레코드(제외면 null). */
data class NettingRecord(
    val cancellationTransactionId: String,
    val originalTransactionId: String,
    val amountMinor: Long,
    val currency: String,
    val fileId: AcquisitionFileId?,
    val seq: Int?,
    val decidedAt: Instant,
)

/** 승인 [transactionId]를 매입에서 제외했다(전송 전 전액 취소 — D-ACQ-002). [businessDate] = 판정한 봉인의 업무일. */
data class ExclusionRecord(
    val transactionId: String,
    val acquirerCode: String,
    val environment: AcquisitionEnvironment,
    val reason: ExclusionReason,
    val approvedAmountMinor: Long,
    val nettedAmountMinor: Long,
    val currency: String,
    val businessDate: LocalDate,
    val decidedAt: Instant,
)
