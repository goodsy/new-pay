package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.io.Serializable

/** order_payment_control 복합 PK = OrderKey(environment, MID, merchantOrderId). */
@Embeddable
class OrderKeyColumns protected constructor() : Serializable {
    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is OrderKeyColumns && environment == other.environment && merchantAccountId == other.merchantAccountId &&
                merchantOrderId == other.merchantOrderId
            )

    override fun hashCode(): Int = listOf(environment, merchantAccountId, merchantOrderId).hashCode()

    override fun toString(): String = "OrderKeyColumns($environment/$merchantAccountId/$merchantOrderId)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(environment: String, merchantAccountId: String, merchantOrderId: String): OrderKeyColumns = OrderKeyColumns().also {
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
        }
    }
}
