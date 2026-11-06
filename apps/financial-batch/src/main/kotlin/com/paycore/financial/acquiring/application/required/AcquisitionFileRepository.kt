package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecord
import com.paycore.financial.acquiring.domain.RenderedAcquisitionFile
import java.time.Instant

/**
 * 매입 파일 Aggregate 저장 Port(CONVENTIONS §14 — 이 모듈이 소유한 Aggregate를 자기 식별자로 load/save). 호출자 TX 필수.
 * 바이트·레코드는 봉인 뒤 바뀌지 않는다. 파일 행은 상태 축 열만 바뀐다(2-D 전송 축 — [update], Versioned: 기대 version이 다르면 OptimisticConflict).
 */
interface AcquisitionFileRepository {
    fun find(id: AcquisitionFileId): StoredAcquisitionFile?

    /** 파일 행만(레코드·봉인 바이트를 읽지 않는다 — 상태 전이 TX용). */
    fun findFile(id: AcquisitionFileId): AcquisitionFile?

    /** 파일 행 + 바이트(content) + 레코드(membership)를 함께 INSERT한다. 같은 파일 ID·같은 거래의 두 번째 membership은 UNIQUE 위반. */
    fun insert(file: AcquisitionFile, rendered: RenderedAcquisitionFile)

    /**
     * 상태 축 전이 저장(Versioned — 전송·수신·결과 세 축 열만). [file].version = 이 TX에서 읽은 기대 version. 저장 행 version이 다르면(다른 시도가 먼저 바꿈)
     * [AcquiringPersistenceFailure.OptimisticConflict]. 성공하면 version + 1.
     */
    fun update(file: AcquisitionFile, at: Instant)

    /** lane에서 전송할 차례인 파일(SEALED·TRANSMISSION_UNKNOWN·lease 지난 TRANSMITTING) — 업무일·버전 순. */
    fun transmittable(acquirerCode: String, environment: AcquisitionEnvironment, now: Instant, limit: Int): List<AcquisitionFileId>

    /**
     * 파일 행을 `SELECT … FOR UPDATE`로 잠그고 읽는다(2-F 응답·결과 마감 TX — 같은 파일의 응답 반영·마감을 인스턴스 사이에서 직렬화한다. 외부 호출 없이 짧게 쥔다).
     * 전송 TX는 잠그지 않고 @Version으로 전이하므로, 잠금 중 전송 점유는 version 비교에서 OptimisticConflict(IN_FLIGHT)로 끝난다.
     */
    fun lockFile(id: AcquisitionFileId): AcquisitionFile?

    /** 파일 레코드(순번 순). 저장 값이 계약을 어기면 [com.paycore.financial.acquiring.domain.AcquisitionContractViolation]. */
    fun records(id: AcquisitionFileId): List<AcquisitionRecord>

    /** 결과 마감 대상: 결과 축 AWAITING이고 전송 완료 시각이 [transmittedUpTo] 이하인 파일(업무일·버전 순). */
    fun awaitingResults(acquirerCode: String, environment: AcquisitionEnvironment, transmittedUpTo: Instant, limit: Int): List<AcquisitionFileId>
}

/** 저장된 파일(Aggregate + 레코드 + 봉인 바이트 + 바이트 행의 hash 열). */
class StoredAcquisitionFile(val file: AcquisitionFile, val records: List<AcquisitionRecord>, val content: ByteArray, val contentRowSha256: String) {
    /** 저장 상태가 자기 일관적인가(I-11). */
    fun intact(): Boolean = file.matches(records, content, contentRowSha256)
}
