package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquirerResponseKind
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.QuarantineReason
import com.paycore.financial.acquiring.domain.ResponseEvidence
import java.time.Instant

/**
 * 기관 응답 파일(ACK·결과) 처리 기록 Port(CONVENTIONS §14 Store ③ — 행 식별자가 있어도 Aggregate가 아닌 프로토콜 기록). append-only: outbox 파일은 지워지지 않고 덮어써지지
 * 않으므로(§5.1) FINANCIAL이 (매입사, 이름, SHA-256)로 처리 여부를 기록한다 — 같은 키 두 번째 INSERT는 UNIQUE 위반. 호출자 TX 필수.
 */
interface AcquisitionResponseStore {
    /** 매입사 [acquirerCode] outbox에서 이미 기록한 이름 → 기록된 (hash, 크기) 목록. */
    fun known(acquirerCode: String, names: Collection<String>): Map<String, List<KnownResponse>>

    /** 처리 기록 한 행(응답 바이트 포함). 반환 = 행 id(레코드 결과 행이 참조). */
    fun append(response: ResponseRecord): Long
}

data class KnownResponse(val contentSha256: String, val contentBytes: Long)

/**
 * [disposition] = APPLIED(반영 — 중복 결과 포함)·QUARANTINED(격리, [quarantineReason]). [fileId]·[seq]·[kind] = 이름에서 읽은 값(이름 문법을 통과한 것만 기록한다),
 * [fileId]는 FINANCIAL에 그 파일이 있을 때만. [institutionAt] = ACK receivedAt·결과 producedAt(해석된 경우), [evidence] = 이 응답이 수신·전송 근거가 됐으면 그 근거.
 */
data class ResponseRecord(
    val acquirerCode: String,
    val environment: AcquisitionEnvironment,
    val fileName: String,
    val content: ByteArray,
    val contentSha256: String,
    val kind: AcquirerResponseKind,
    val fileId: AcquisitionFileId?,
    val seq: Int,
    val disposition: ResponseDisposition,
    val quarantineReason: QuarantineReason?,
    val ackOutcome: String?,
    val ackReasonCode: String?,
    val institutionAt: Instant?,
    val evidence: ResponseEvidence?,
    val observedAt: Instant,
) {
    init {
        require((disposition == ResponseDisposition.QUARANTINED) == (quarantineReason != null)) { "only a quarantined response has a quarantine reason" }
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "ResponseRecord(fileName=$fileName, disposition=$disposition, reason=$quarantineReason)"
}

enum class ResponseDisposition { APPLIED, QUARANTINED }
