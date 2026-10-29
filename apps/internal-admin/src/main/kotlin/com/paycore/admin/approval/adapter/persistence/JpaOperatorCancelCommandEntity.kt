package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * admin_operator_cancel_command 행(ADMIN-PAYMENT-OPS-001 §4.11 — 행 모양만, 규칙은 domain `OperatorCancelRequestSnapshot`). INSERT-only:
 * `@Immutable`(Hibernate가 UPDATE를 내지 않는다) + DB 권한 SELECT·INSERT만(1142) — 고정 값(금액·사유·`pinned_target`)은 두 겹으로 막는다. `merge`(save) 금지, persist만.
 * equals/hashCode는 ID만, toString에 사유·근거·고정 값이 없다.
 */
@Entity
@Immutable
@Table(name = "admin_operator_cancel_command")
class JpaOperatorCancelCommandEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "purpose_kind", nullable = false, updatable = false, length = 40)
    lateinit var purposeKind: String
        protected set

    @field:Column(name = "purpose_ref", nullable = false, updatable = false, length = 200)
    lateinit var purposeRef: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 500)
    lateinit var reason: String
        protected set

    @field:Column(name = "pinned_target", nullable = false, updatable = false, columnDefinition = "json")
    lateinit var pinnedTarget: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaOperatorCancelCommandEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaOperatorCancelCommandEntity(approvalId=$approvalId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, paymentId: String, amountMinor: Long, currency: String, purposeKind: String, purposeRef: String, reason: String, pinnedTarget: String,
            createdAt: Instant,
        ) = JpaOperatorCancelCommandEntity().also {
            it.approvalId = approvalId
            it.paymentId = paymentId
            it.amountMinor = amountMinor
            it.currency = currency
            it.purposeKind = purposeKind
            it.purposeRef = purposeRef
            it.reason = reason
            it.pinnedTarget = pinnedTarget
            it.createdAt = createdAt
        }
    }
}
