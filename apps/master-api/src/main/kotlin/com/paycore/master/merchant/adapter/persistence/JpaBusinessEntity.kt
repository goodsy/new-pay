package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/*
 * MASTER 행 Entity(adapter.persistence 전용, 순수 domain과 분리 — Mapper가 변환한다). 공통 관례(ARCHITECTURE-CONVENTIONS §9.1):
 * - data class가 아니다. equals/hashCode는 바뀌지 않는 업무 ID만, toString은 ID·상태만(등록번호 평문 금지 — D-MD-008).
 * - 생성 후 바뀌지 않는 컬럼(소속·발급 근거·생성 시각)은 `updatable = false`: 변경 감지 UPDATE의 SET에서 빠진다(표 단위 UPDATE 권한은
 *   열을 구분하지 못하므로 이것이 소속·근거 불변의 adapter 측 방어다).
 * - 생성 컬럼(active_channel_key, open_*_key)은 매핑하지 않는다(DB가 계산, UNIQUE 안전망).
 * - 연관 매핑(@OneToMany 등)을 쓰지 않는다: cascade·orphanRemoval이 append-only 행을 지우는 경로를 구조적으로 없앤다.
 */

@Entity
@Table(name = "business")
class JpaBusinessEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "registration_number", nullable = false, updatable = false, columnDefinition = "char(10)")
    lateinit var registrationNumber: String
        protected set

    @field:Column(name = "display_name", nullable = false, length = 100)
    lateinit var displayName: String
        protected set

    @field:Column(name = "lifecycle", nullable = false, length = 16)
    lateinit var lifecycle: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessEntity).businessId)

    override fun hashCode(): Int = businessId.hashCode()

    override fun toString(): String = "JpaBusinessEntity(businessId=$businessId, lifecycle=$lifecycle)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(id: String, registrationNumber: String, displayName: String, lifecycle: String, at: Instant, by: String) =
            JpaBusinessEntity().also {
                it.businessId = id
                it.registrationNumber = registrationNumber
                it.displayName = displayName
                it.lifecycle = lifecycle
                it.createdAt = at
                it.createdBy = by
            }
    }
}
