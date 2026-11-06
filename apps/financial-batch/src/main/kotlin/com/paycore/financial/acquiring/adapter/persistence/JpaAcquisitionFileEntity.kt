package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate

/**
 * acquisition_file 행(매입 파일 Aggregate의 영속 모양).
 *
 * 배치2(2-D)에서 `@Immutable`을 뗐다 — 근거: 전송 축 전이(SEALED → TRANSMITTING → TRANSMITTED/TRANSMISSION_UNKNOWN/격리)는 같은 파일 행의 상태 열을 바꿔야 하고
 * (D-ACQ-004 상태 3축은 파일 행에 둔다 — 배치1 V5), 시도 기록은 별도 append-only 표다. 바뀌는 열은 전송 축 열(transmission_state·transmission_lease_until·
 * transmitted_at)·updated_at·version뿐이고 나머지(식별·봉인·합계·hash·수신·결과 축)는 updatable=false다. runtime 권한도 같은 열만 UPDATE(V8 — 그 밖 1143).
 * 2-F: 수신·결과 축 열(receipt_state·result_state)을 updatable로 더했다(ACK·결과 반영·결과 마감 — runtime 열 권한 V11). 식별·봉인·합계·hash 열은 그대로 1143.
 *
 * JPA 선택(이번 코드): **`@Version`** — 점유 TX가 읽은 version을 결과 TX가 기대 version으로 비교한다. 예: lease가 만료돼 다른 시도가 이어받으면 version이 올라가
 * 늦게 끝난 원래 시도의 결과 TX는 그 version을 기대하지 못해 결과(행·상태)를 버린다(동시 flush는 `UPDATE … WHERE version = ?` 0행 → OptimisticLock).
 * 이유: 전송은 TX 밖 외부 호출이라 잠금을 들고 기다릴 수 없다(TECH §3 — 외부 소켓 호출 동안 잠금 금지). 주의: 변경 감지는 같은 TX에서 읽은 Entity만
 * 추적한다 — 결과 TX는 행을 다시 읽고 Domain의 기대 version과 다르면 저장하지 않는다(stale Domain으로 덮어쓰지 않음).
 */
@Entity
@Table(name = "acquisition_file")
class JpaAcquisitionFileEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    lateinit var environment: String
        protected set

    @field:Column(name = "business_date", nullable = false, updatable = false)
    lateinit var businessDate: LocalDate
        protected set

    @field:Column(name = "file_version", nullable = false, updatable = false)
    var fileVersion: Int = 0
        protected set

    @field:Column(name = "original_file_id", updatable = false, length = 64)
    var originalFileId: String? = null
        protected set

    @field:Column(name = "sender_id", nullable = false, updatable = false, length = 16)
    lateinit var senderId: String
        protected set

    @field:Column(name = "cutoff_policy_version", nullable = false, updatable = false, length = 64)
    lateinit var cutoffPolicyVersion: String
        protected set

    @field:Column(name = "cutoff_at", nullable = false, updatable = false)
    lateinit var cutoffAt: Instant
        protected set

    @field:Column(name = "sealed_at", nullable = false, updatable = false)
    lateinit var sealedAt: Instant
        protected set

    @field:Column(name = "record_count", nullable = false, updatable = false)
    var recordCount: Int = 0
        protected set

    @field:Column(name = "sale_count", nullable = false, updatable = false)
    var saleCount: Int = 0
        protected set

    @field:Column(name = "sale_amount_minor", nullable = false, updatable = false)
    var saleAmountMinor: Long = 0
        protected set

    @field:Column(name = "cancel_count", nullable = false, updatable = false)
    var cancelCount: Int = 0
        protected set

    @field:Column(name = "cancel_amount_minor", nullable = false, updatable = false)
    var cancelAmountMinor: Long = 0
        protected set

    @field:Column(name = "net_amount_minor", nullable = false, updatable = false)
    var netAmountMinor: Long = 0
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    @field:Column(name = "content_bytes", nullable = false, updatable = false)
    var contentBytes: Int = 0
        protected set

    @field:Column(name = "transmission_state", nullable = false, length = 24)
    lateinit var transmissionState: String
        protected set

    @field:Column(name = "transmission_lease_until")
    var transmissionLeaseUntil: Instant? = null
        protected set

    @field:Column(name = "transmitted_at")
    var transmittedAt: Instant? = null
        protected set

    // 2-F: 수신·결과 축 전이(ACK·결과 반영, 결과 마감)가 이 두 열을 바꾼다 — updatable, runtime 열 권한 V11.
    @field:Column(name = "receipt_state", nullable = false, length = 24)
    lateinit var receiptState: String
        protected set

    @field:Column(name = "result_state", nullable = false, length = 24)
    lateinit var resultState: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    /**
     * 상태 축 전이 반영(변경 감지 — flush 때 바뀐 축 열·updated_at·version을 UPDATE하고 WHERE에 기대 version을 둔다). 2-D 전송 축 + 2-F 수신·결과 축.
     * 아무 축도 바뀌지 않으면 손대지 않는다(version을 올리지 않는다 — 전송 시도의 기대 version을 불필요하게 깨지 않게).
     */
    fun applyAxes(state: String, leaseUntil: Instant?, transmittedAt: Instant?, receipt: String, result: String, at: Instant) {
        if (state == transmissionState && leaseUntil == transmissionLeaseUntil && transmittedAt == this.transmittedAt && receipt == receiptState && result == resultState) return
        transmissionState = state
        transmissionLeaseUntil = leaseUntil
        this.transmittedAt = transmittedAt
        receiptState = receipt
        resultState = result
        updatedAt = at
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionFileEntity(fileId=$fileId, transmission=$transmissionState)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            fileId: String, acquirerCode: String, environment: String, businessDate: LocalDate, fileVersion: Int, originalFileId: String?, senderId: String,
            cutoffPolicyVersion: String, cutoffAt: Instant, sealedAt: Instant, recordCount: Int, saleCount: Int, saleAmountMinor: Long, cancelCount: Int,
            cancelAmountMinor: Long, netAmountMinor: Long, contentSha256: String, contentBytes: Int, transmissionState: String, receiptState: String,
            resultState: String, at: Instant,
        ) = JpaAcquisitionFileEntity().also {
            it.fileId = fileId
            it.acquirerCode = acquirerCode
            it.environment = environment
            it.businessDate = businessDate
            it.fileVersion = fileVersion
            it.originalFileId = originalFileId
            it.senderId = senderId
            it.cutoffPolicyVersion = cutoffPolicyVersion
            it.cutoffAt = cutoffAt
            it.sealedAt = sealedAt
            it.recordCount = recordCount
            it.saleCount = saleCount
            it.saleAmountMinor = saleAmountMinor
            it.cancelCount = cancelCount
            it.cancelAmountMinor = cancelAmountMinor
            it.netAmountMinor = netAmountMinor
            it.contentSha256 = contentSha256
            it.contentBytes = contentBytes
            it.transmissionState = transmissionState
            it.receiptState = receiptState
            it.resultState = resultState
            it.createdAt = at
            it.updatedAt = at
        }
    }
}
