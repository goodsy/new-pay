package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.RecordResultEntry

/**
 * 레코드 결과 행 Port(CONVENTIONS §14 Store ① — 레코드(파일, 순번)마다 쌓이는 결정 로그, 상태는 로그에서 계산 — `RecordResultRule.status`). append-only.
 * 같은 (파일, 순번, 행 번호) 두 번째 INSERT는 PK 위반(동시 반영 방어 — 응답 TX는 파일 행을 잠근 뒤 쓴다). 호출자 TX 필수.
 */
interface AcquisitionRecordResultStore {
    /** 파일 [fileId]의 순번 → 결과 행(행 번호 순). 행이 없는 순번은 빠진다. */
    fun entries(fileId: AcquisitionFileId): Map<Int, List<RecordResultEntry>>

    /** 여러 레코드의 결과 행(CANCEL 판정 — 원 SALE 상태). */
    fun entriesOf(records: Collection<RecordRef>): Map<RecordRef, List<RecordResultEntry>>

    /** 결과 행 하나. [responseId] = 근거 응답 행(결과 마감 UNKNOWN은 null). */
    fun append(record: RecordRef, entry: RecordResultEntry, responseId: Long?)

    /** (rev 1.1 — D-ACQ-014) 거래·종류의 ACCEPTED 레코드(어느 세대든 하나). 없으면 null. */
    fun acceptedRecordOf(transactionId: String, recordType: AcquisitionRecordType): RecordRef?

    /** (rev 1.1 — D-ACQ-014) ACCEPTED 행과 같은 TX에서 매입 확정 표지를 남긴다. 같은 거래·종류 두 번째는 PK 위반(UniqueConflict). */
    fun markAccepted(record: RecordRef, transactionId: String, recordType: AcquisitionRecordType, generation: Int, entry: RecordResultEntry)
}

/** 파일 레코드 참조. */
data class RecordRef(val fileId: AcquisitionFileId, val seq: Int)
