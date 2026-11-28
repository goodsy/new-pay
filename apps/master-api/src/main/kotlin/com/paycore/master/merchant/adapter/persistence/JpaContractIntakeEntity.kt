package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * `contract_intake` 행(MERCHANT-FLOW-001 §4A.3·§4A.15 — 사업자 계약 접수 건의 **현재 상태**). DB 행 모양만 가진다 — 전이·생성 조건 규칙은 순수 Domain `ContractIntake`.
 *
 * JPA 선택(이 코드의 역할·예·이유·주의점):
 * - **역할**: 열만 들고 규칙을 갖지 않는다. 예: "상담 중 → 계약 완료 거절", "완료 건이 있으면 새 접수 거절"은 `ContractIntake`가 판정하고, 이 Entity는 판정 결과(상태·version)를
 *   그대로 옮겨 적는다([applyTransition]). 이유: Domain 시험을 DB 없이 돌리고 Domain이 JPA·Spring에 의존하지 않게(Mapper가 둘을 잇는다 — [ContractIntakePersistenceMapper]).
 * - **version**: `@Version` — 전이마다 변경 감지 UPDATE가 +1 하고 `WHERE version = ?`로 다른 TX가 먼저 바꾼 행을 덮어쓰지 않는다(낙관적 잠금 — 화면이 본 version과 다르면 거절).
 *   주의: 사업자 version은 올리지 않는다 — 접수 건 명령은 사업자 행을 `PESSIMISTIC_WRITE`(version 증가 없음)로 잠근다(리뷰 m-4).
 * - **수정 가능 열**: 상태·갱신 시각·행위자 + **접수 항목**(접수일·경로·담당자·희망 서비스 — FLOW-2d 열린 건 접수 항목 수정, D-FLOW-007 ④·D-FLOW-009 ①, [applyItems]).
 *   소속·순번·이관 표시·종류·대상·생성 값은 `updatable = false` — 변경 감지 UPDATE의 SET에 들어가지 않는다. 예: 전이([applyTransition])는 접수 항목을 건드리지 않으므로 같은 값이
 *   다시 쓰일 뿐이고(@DynamicUpdate 없음 — 기본은 수정 가능 열 전체 SET), 항목 수정은 상태를 바꾸지 않는다(Repository가 확인).
 * - **생성 열** `open_key`(열린 건일 때만 business_id — UNIQUE `uk_cin_open`, V21이 V19 `active_key`·`uk_cin_active`를 대체 — D-GOV-014 ①)는 매핑하지 않는다
 *   (DB가 계산하는 이중 방어). 복합 PK는 `@IdClass`(V13 코드 배정 선례).
 * - **종류·대상 열**(`kind`·`target_contract_seq` — FLOW-2a V21, BR-BIZ-324·325): 생성 때 Domain이 판정한 값을 INSERT 때만 쓴다(`updatable = false` — 전이 UPDATE의 SET에 없다).
 * - **연관 매핑 없음**: 사업자 Entity와 `@ManyToOne`/`@OneToMany`를 두지 않는다 — 사업자 수정이 접수 건 컬렉션을 다시 저장하거나 지연 로딩이 판정 입력을 오래된 값으로 만드는 위험을 없앤다.
 * - data class가 아니다. equals/hashCode는 바뀌지 않는 키만, toString은 키·상태만.
 */
@Entity
@IdClass(JpaContractIntakeEntity.Key::class)
@Table(name = "contract_intake")
class JpaContractIntakeEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Id
    @field:Column(name = "intake_seq", nullable = false, updatable = false)
    var intakeSeq: Int = 0
        protected set

    @field:Column(name = "status", nullable = false, length = 24)
    lateinit var status: String
        protected set

    @field:Column(name = "migrated", nullable = false, updatable = false)
    var migrated: Boolean = false
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 8)
    lateinit var kind: String
        protected set

    @field:Column(name = "target_contract_seq", updatable = false)
    var targetContractSeq: Int? = null
        protected set

    @field:Column(name = "received_on")
    var receivedOn: LocalDate? = null
        protected set

    @field:Column(name = "intake_channel", length = 16)
    var intakeChannel: String? = null
        protected set

    @field:Column(name = "contract_manager", length = 64)
    var contractManager: String? = null
        protected set

    @field:Column(name = "desires_card_auth", nullable = false)
    var desiresCardAuth: Boolean = false
        protected set

    @field:Column(name = "desires_card_keyin", nullable = false)
    var desiresCardKeyin: Boolean = false
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
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

    /** Domain 전이 결과를 옮겨 적는다(상태·갱신 시각·행위자 — 변경 감지 UPDATE가 version을 +1). */
    fun applyTransition(status: String, at: Instant, by: String) {
        this.status = status
        updatedAt = at
        updatedBy = by
    }

    /** FLOW-2d: Domain 접수 항목 수정 결과를 옮겨 적는다(접수 항목·갱신 시각·행위자 — 변경 감지 UPDATE가 version을 +1). 상태는 그대로. */
    @Suppress("LongParameterList")
    fun applyItems(receivedOn: LocalDate, channel: String, manager: String, cardAuth: Boolean, cardKeyin: Boolean, at: Instant, by: String) {
        this.receivedOn = receivedOn
        intakeChannel = channel
        contractManager = manager
        desiresCardAuth = cardAuth
        desiresCardKeyin = cardKeyin
        updatedAt = at
        updatedBy = by
    }

    /** 복합 PK 값(@IdClass). */
    data class Key(var businessId: String = "", var intakeSeq: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaContractIntakeEntity).businessId &&
                intakeSeq == other.intakeSeq
            )

    override fun hashCode(): Int = 31 * businessId.hashCode() + intakeSeq

    override fun toString(): String = "JpaContractIntakeEntity(businessId=$businessId, intakeSeq=$intakeSeq, status=$status, kind=$kind, migrated=$migrated, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            businessId: String,
            seq: Int,
            status: String,
            kind: String,
            targetContractSeq: Int?,
            receivedOn: LocalDate,
            channel: String,
            manager: String,
            desiresCardAuth: Boolean,
            desiresCardKeyin: Boolean,
            at: Instant,
            by: String,
        ) = JpaContractIntakeEntity().also {
            it.businessId = businessId
            it.intakeSeq = seq
            it.status = status
            it.migrated = false
            it.kind = kind
            it.targetContractSeq = targetContractSeq
            it.receivedOn = receivedOn
            it.intakeChannel = channel
            it.contractManager = manager
            it.desiresCardAuth = desiresCardAuth
            it.desiresCardKeyin = desiresCardKeyin
            it.createdAt = at
            it.createdBy = by
            it.updatedAt = at
            it.updatedBy = by
        }
    }
}
