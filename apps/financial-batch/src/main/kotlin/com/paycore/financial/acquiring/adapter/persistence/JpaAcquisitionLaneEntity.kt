package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * acquisition_lane 행. 식별 열(매입사·환경·생성 시각)은 IODKU가 만들 때 정해지고 바뀌지 않는다(updatable=false, DB 1143). 잠가 읽은 Entity의 변경 감지로
 * 마지막 봉인 업무일·갱신 시각만 바꾸고 `@Version`이 함께 오른다.
 */
@Entity
@Table(name = "acquisition_lane")
class JpaAcquisitionLaneEntity protected constructor() {
    @field:EmbeddedId
    lateinit var id: AcquisitionLaneKey
        protected set

    @field:Column(name = "last_sealed_business_date")
    var lastSealedBusinessDate: LocalDate? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionLaneEntity(id=$id, lastSealed=$lastSealedBusinessDate, version=$version)"
}

/** lane 복합 키(값 객체 — equals/hashCode는 두 열). */
@Embeddable
class AcquisitionLaneKey() : Serializable {
    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    var acquirerCode: String = ""

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    var environment: String = ""

    constructor(acquirerCode: String, environment: String) : this() {
        this.acquirerCode = acquirerCode
        this.environment = environment
    }

    override fun equals(other: Any?): Boolean = other is AcquisitionLaneKey && other.acquirerCode == acquirerCode && other.environment == environment

    override fun hashCode(): Int = 31 * acquirerCode.hashCode() + environment.hashCode()

    override fun toString(): String = "$acquirerCode/$environment"

    private companion object {
        private const val serialVersionUID = 1L
    }
}
