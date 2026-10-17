package com.paycore.simulator.authentication.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** lookup_log 한 건(append-only, `@Immutable`). */
@Entity
@Immutable
@Table(name = "lookup_log")
class JpaLookupLogEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "result_ref", nullable = false, updatable = false, columnDefinition = "char(26)")
    lateinit var resultRef: String
        protected set

    @field:Column(name = "looked_up_at", nullable = false, updatable = false)
    lateinit var lookedUpAt: Instant
        protected set

    @field:Column(name = "behaviour", nullable = false, updatable = false, length = 16)
    lateinit var behaviour: String
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaLookupLogEntity && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String = "JpaLookupLogEntity(id=$id, behaviour=$behaviour)"

    companion object {
        fun newRow(resultRef: String, lookedUpAt: Instant, behaviour: String) = JpaLookupLogEntity().also {
            it.resultRef = resultRef
            it.lookedUpAt = lookedUpAt
            it.behaviour = behaviour
        }
    }
}
