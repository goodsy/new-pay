package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.json.JsonMapper

/**
 * 대량·다양한 입력의 영속 왕복(자동 Fixture — RandomCreatePaymentFixture 뒤의 Fixture Monkey, seed 고정). 핵심 업무 규칙은 명시 Fixture
 * 시험(CreatePaymentServiceSpec 등)이 보고, 여기서는 임의의 유효 금액(minor unit)·통화·UTF-8 주문명이 Payment·Outbox payload·재생 응답에
 * **바뀌지 않고** 보존되는지 본다(Financial Consistency: 금액 보존).
 */
@PaymentDbTest
class CreatePaymentBulkSpec(
    private val service: CreatePaymentUseCase,
    private val db: DbAccess,
) : BehaviorSpec({

    val json = JsonMapper.builder().build()

    Given("40 generated valid new orders (fixed seed)") {
        val commands = RandomCreatePaymentFixture.validNewOrders(count = 40, seed = 20261007L)

        Then("the fixture yields varied inputs (both currencies, absent and present names, non-ASCII names)") {
            commands.map { it.orderIntent.money.currency.value }.toSet() shouldBe setOf("KRW", "USD")
            commands.any { it.orderName == null } shouldBe true
            commands.any { name -> name.orderName?.any { it.code > 127 } == true } shouldBe true
        }

        Then("each is created once and amount, currency and name round-trip exactly through the row, the event and the replay") {
            commands.forEach { cmd ->
                val created = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
                created.outcome shouldBe CreatePaymentOutcome.CREATED
                created.payment.money shouldBe cmd.orderIntent.money

                val row = db.withApp { c ->
                    with(db) { c.rows("SELECT amount_minor, currency, order_name FROM payment WHERE payment_id = ?", cmd.newIdentifiers.paymentId.value) }
                }.single()
                (row["amount_minor"] as Number).toLong() shouldBe cmd.orderIntent.money.amountMinor
                row["currency"] shouldBe cmd.orderIntent.money.currency.value
                row["order_name"] shouldBe cmd.orderName

                val payload = db.withApp { c ->
                    with(db) { c.scalar("SELECT payload FROM outbox_event WHERE aggregate_id = ?", cmd.newIdentifiers.paymentId.value) }
                }.toString().let(json::readTree)
                payload.get("amount").asLong() shouldBe cmd.orderIntent.money.amountMinor
                payload.get("currency").asString() shouldBe cmd.orderIntent.money.currency.value

                val replay = service.create(cmd.copy(newIdentifiers = newIdentifiers()))
                    .shouldBeInstanceOf<CreatePaymentResult.Accepted>()
                replay.replayed shouldBe true
                replay.payment shouldBe created.payment
            }
        }
    }
})
