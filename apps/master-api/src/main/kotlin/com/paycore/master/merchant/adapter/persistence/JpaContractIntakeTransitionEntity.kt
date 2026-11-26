package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/**
 * `contract_intake_transition` 행(MERCHANT-FLOW-001 §4A.3 — 접수 건 전이 이력, **INSERT 전용**, §4A.14 M-2).
 *
 * JPA 선택(역할·이유·주의점):
 * - `@Immutable` + 모든 열 `updatable = false` + protected setter: Hibernate가 이 Entity에 UPDATE를 만들 길이 없다(dirty checking이 UPDATE를 보내지 않는다).
 *   runtime DB 권한도 SELECT, INSERT만이라 UPDATE·DELETE는 1142로 이중 방어한다(V20).
 * - 새 행은 `persist`만(Spring Data `save`는 merge가 될 수 있다 — `PessimisticRowLock.kt` 관례). 순번은 사업자 행 잠금 아래 [JpaContractIntakeTransitionStore]가 부여하고
 *   PK(사업자, 접수 건, 순번)가 이중 방어한다.
 * - 사유(`reason`)·근거 참조(`document_ref`)는 Domain 전이 결과(FLOW-2a MINOR-1 — [com.paycore.master.merchant.domain.ContractIntakeTransition])를 그대로 옮긴다.
 *   원문은 이 표와 `master_change_history`에만 둔다 — toString·로그에 넣지 않는다.
 */
@Entity
@Immutable
@IdClass(JpaContractIntakeTransitionEntity.Key::class)
@Table(name = "contract_intake_transition")
class JpaContractIntakeTransitionEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Id
    @field:Column(name = "intake_seq", nullable = false, updatable = false)
    var intakeSeq: Int = 0
        protected set

    @field:Id
    @field:Column(name = "transition_seq", nullable = false, updatable = false)
    var transitionSeq: Int = 0
        protected set

    @field:Column(name = "from_status", updatable = false, length = 24)
    var fromStatus: String? = null
        protected set

    @field:Column(name = "to_status", nullable = false, updatable = false, length = 24)
    lateinit var toStatus: String
        protected set

    @field:Column(name = "migrated", nullable = false, updatable = false)
    var migrated: Boolean = false
        protected set

    @field:Column(name = "reason", updatable = false, length = 200)
    var reason: String? = null
        protected set

    @field:Column(name = "document_ref", updatable = false, length = 128)
    var documentRef: String? = null
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    /** 복합 PK 값(@IdClass). */
    data class Key(var businessId: String = "", var intakeSeq: Int = 0, var transitionSeq: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaContractIntakeTransitionEntity).businessId &&
                intakeSeq == other.intakeSeq && transitionSeq == other.transitionSeq
            )

    override fun hashCode(): Int = (31 * businessId.hashCode() + intakeSeq) * 31 + transitionSeq

    override fun toString(): String =
        "JpaContractIntakeTransitionEntity(businessId=$businessId, intakeSeq=$intakeSeq, transitionSeq=$transitionSeq, from=$fromStatus, to=$toStatus, migrated=$migrated)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            businessId: String,
            intakeSeq: Int,
            transitionSeq: Int,
            from: String?,
            to: String,
            migrated: Boolean,
            reason: String?,
            documentRef: String?,
            at: Instant,
            by: String,
        ) =
            JpaContractIntakeTransitionEntity().also {
                it.businessId = businessId
                it.intakeSeq = intakeSeq
                it.transitionSeq = transitionSeq
                it.fromStatus = from
                it.toStatus = to
                it.migrated = migrated
                it.reason = reason
                it.documentRef = documentRef
                it.actorRef = by
                it.occurredAt = at
            }
    }
}
