package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/*
 * 사업자 하위 유효기간 행 Entity 공통(BUSINESS-PROFILE-001 §5.5 — 대표자·업태/종목·주소·등록 담당자):
 * - 내용 열·소속·effective_from·created_by는 `updatable = false` — 변경 감지 UPDATE의 SET에 들어갈 수 있는 열은 종료 기록(effective_to·ended_by)뿐이다
 *   (표 단위 UPDATE 권한의 adapter 측 방어 — C-11·L-10, BIZ-1-6이 실제 SQL로 확인).
 * - 생성 열(current_*_key)은 매핑하지 않는다(DB가 계산하는 UNIQUE 안전망). 행 ID는 IDENTITY(persist 즉시 INSERT — 저장 순서 §5.5 flush 순서 참고).
 * - 연관 매핑 없음: cascade·orphanRemoval이 종료 행(보존 대상)을 지우는 경로를 구조적으로 없앤다.
 * - toString은 ID·유형·순서만(이름·연락처·주소 원문 없음 — §9).
 */

@Entity
@Table(name = "business_representative")
class JpaBusinessRepresentativeEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "representative_id", nullable = false, updatable = false)
    var representativeId: Long? = null
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "name", nullable = false, updatable = false, length = 50)
    lateinit var name: String
        protected set

    @field:Column(name = "representative_type", nullable = false, updatable = false, length = 8)
    lateinit var representativeType: String
        protected set

    @field:Column(name = "display_order", nullable = false, updatable = false, columnDefinition = "smallint")
    var displayOrder: Int = 0
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Column(name = "ended_by", length = 64)
    var endedBy: String? = null
        protected set

    /** 종료 기록(유일한 UPDATE — effective_to·ended_by). 이미 종료된 행은 다시 종료하지 않는다. */
    fun end(at: Instant, by: String) {
        check(effectiveTo == null) { "row already ended" }
        effectiveTo = at
        endedBy = by
    }

    /** 저장 전 id가 없으므로 동일성은 인스턴스 기준(비교·컬렉션에 쓰지 않는다). */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaBusinessRepresentativeEntity(id=$representativeId, type=$representativeType, order=$displayOrder, ended=${effectiveTo != null})"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(businessId: String, name: String, type: String, order: Int, at: Instant, by: String) = JpaBusinessRepresentativeEntity().also {
            it.businessId = businessId
            it.name = name
            it.representativeType = type
            it.displayOrder = order
            it.effectiveFrom = at
            it.createdBy = by
        }
    }
}