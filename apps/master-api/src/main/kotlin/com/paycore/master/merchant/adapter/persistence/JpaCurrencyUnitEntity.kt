package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate

/** currency_unit 행(기준정보, 읽기 전용). */
@Entity
@Table(name = "currency_unit")
class JpaCurrencyUnitEntity protected constructor() {
    @field:Id
    @field:Column(name = "currency_code", nullable = false, updatable = false, insertable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "minor_unit", nullable = false, updatable = false, insertable = false)
    var minorUnit: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && currencyCode == (other as JpaCurrencyUnitEntity).currencyCode)

    override fun hashCode(): Int = currencyCode.hashCode()

    override fun toString(): String = "JpaCurrencyUnitEntity($currencyCode/$minorUnit)"
}
