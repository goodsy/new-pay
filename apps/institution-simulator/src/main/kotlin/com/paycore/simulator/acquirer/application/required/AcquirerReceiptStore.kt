package com.paycore.simulator.acquirer.application.required

import com.paycore.simulator.acquirer.domain.AcceptedSale
import com.paycore.simulator.acquirer.domain.AcquirerReceipt
import com.paycore.simulator.acquirer.domain.JudgedRecord
import com.paycore.simulator.acquirer.domain.PlannedResultLine
import com.paycore.simulator.acquirer.domain.PlannedResultFile
import com.paycore.simulator.acquirer.domain.ReceivedRecordType

/**
 * 가상 매입사 수신·판정·게시 계획 원장 Port(append-only — 수정·삭제 연산 없음, DB 권한 SELECT·INSERT). 호출자 TX 안에서만(MANDATORY).
 * UNIQUE 충돌(같은 수신의 동시 처리, 같은 거래의 두 번째 ACCEPTED)은 [DuplicateAcquirerRecord] — 호출자 TX는 rollback하고 다음 회차에 다시 판정한다.
 */
interface AcquirerReceiptStore {
    /** 그 파일 ID의 수신 기록(수신 순번 순). */
    fun receipts(acquirerCode: String, fileId: String): List<StoredReceipt>

    fun receipt(receiptId: Long): StoredReceipt

    /** 수신 한 행을 쓰고 receipt_id를 돌려준다. */
    fun append(receipt: AcquirerReceipt): Long

    fun appendJudgements(receiptId: Long, acquirerCode: String, judged: List<JudgedRecord>)

    fun appendResultLines(receiptId: Long, files: List<PlannedResultFile>)

    /** 그 수신의 결과 회차 [resultSeq] 줄(레코드 순). */
    fun resultLines(receiptId: Long, resultSeq: Int): List<PlannedResultLine>

    /** 이 매입사에서 그 거래 ID·종류가 이미 ACCEPTED인가(기관 내부 판정 — 게시 여부와 무관). */
    fun transactionAccepted(acquirerCode: String, transactionId: String, type: ReceivedRecordType): Boolean

    /** 이 매입사에서 그 기관 근거(VAN 원장 행)·종류가 이미 ACCEPTED인가(리뷰 MJ-1 — 이중 매입 방어 키). */
    fun evidenceAccepted(acquirerCode: String, type: ReceivedRecordType, evidenceEntryId: String): Boolean

    /** 이 매입사가 수락한 그 거래 ID의 SALE(없으면 null). */
    fun acceptedSale(acquirerCode: String, transactionId: String): AcceptedSale?

    /** 그 원승인 VAN 행에 이미 소비된 취소 = 수락 SALE 상계액 + 수락 CANCEL 금액 합. */
    fun consumedCancels(acquirerCode: String, authorizationEntryId: String): Long
}

data class StoredReceipt(val receiptId: Long, val receipt: AcquirerReceipt)
