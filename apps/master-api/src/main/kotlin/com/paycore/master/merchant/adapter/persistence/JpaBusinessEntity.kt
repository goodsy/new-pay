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

    // BUSINESS-PROFILE-001 V15(명세 §5.1·§5.5): 사업체 구분(NULL = migration·과도기 옛 경로 행만), 등록 상태(D-BIZ-001 — 생명주기와 별개 축),
    // 진위확인(NOT_CHECKED만 — DB CHECK), 마지막 변경 시각·행위자. 사업자 명령(BusinessProfileService)만 Root 필드를 바꾼다 — 변경 감지 UPDATE가 @Version을 +1 한다.
    @field:Column(name = "business_type", length = 16)
    var businessType: String? = null
        protected set

    @field:Column(name = "registration_status", nullable = false, length = 16)
    lateinit var registrationStatus: String
        protected set

    @field:Column(name = "verification_status", nullable = false, length = 16)
    lateinit var verificationStatus: String
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
        protected set

    @field:Column(name = "updated_by", nullable = false, length = 64)
    lateinit var updatedBy: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessEntity).businessId)

    override fun hashCode(): Int = businessId.hashCode()

    override fun toString(): String = "JpaBusinessEntity(businessId=$businessId, lifecycle=$lifecycle, registrationStatus=$registrationStatus)"

    /** Root 필드 덮어쓰기(사업자 명령 전용 — 번호·생명주기·생성 값은 바꾸지 않는다). 값이 하나라도 바뀌었는지 돌려준다(변경 감지 UPDATE 유무 = version +1 경로 판단). */
    fun applyProfile(displayName: String, businessType: String?, registrationStatus: String, updatedAt: Instant, updatedBy: String): Boolean {
        val changed = this.displayName != displayName || this.businessType != businessType || this.registrationStatus != registrationStatus ||
            this.updatedAt != updatedAt || this.updatedBy != updatedBy
        this.displayName = displayName
        this.businessType = businessType
        this.registrationStatus = registrationStatus
        this.updatedAt = updatedAt
        this.updatedBy = updatedBy
        return changed
    }

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            registrationNumber: String,
            displayName: String,
            lifecycle: String,
            at: Instant,
            by: String,
            businessType: String?,
            registrationStatus: String,
            verificationStatus: String,
            updatedAt: Instant,
            updatedBy: String,
        ) = JpaBusinessEntity().also {
            it.businessId = id
            it.registrationNumber = registrationNumber
            it.displayName = displayName
            it.lifecycle = lifecycle
            it.createdAt = at
            it.createdBy = by
            it.businessType = businessType
            it.registrationStatus = registrationStatus
            it.verificationStatus = verificationStatus
            it.updatedAt = updatedAt
            it.updatedBy = updatedBy
        }
    }
}
