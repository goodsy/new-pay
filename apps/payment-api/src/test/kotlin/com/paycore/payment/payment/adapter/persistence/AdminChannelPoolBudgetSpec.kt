package com.paycore.payment.payment.adapter.persistence

import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource

/**
 * 풀 대비 permit 기동 검사(ADMIN-PAYMENT-OPS-001 §4.7 M-7 `AdminChannelPoolBudgetSpec`): `readPermits + commandPermits ≤ Hikari 풀 − publicPoolReserve`가 아니면 기동 거부(경계 포함).
 * 명령 permit은 실행·기한 확인 공용이라 합계에서 빠지는 쓰기 경로가 없다(rev 0.4 L-3). 풀 크기는 실행 중 Hikari 값을 읽는다(연결을 열지 않는다).
 */
class AdminChannelPoolBudgetSpec : BehaviorSpec({

    Given("the budget rule") {
        Then("read + command equal to pool − reserve starts; one more refuses") {
            AdminChannelPoolBudget(4, 2, 4, 10)
            shouldThrow<IllegalStateException> { AdminChannelPoolBudget(4, 3, 4, 10) }
            shouldThrow<IllegalStateException> { AdminChannelPoolBudget(5, 2, 4, 10) }
        }
    }

    Given("the Spring configuration with a real Hikari pool size") {
        fun runner(poolSize: Int) = ApplicationContextRunner()
            .withUserConfiguration(OperatorDecisionPolicyConfiguration::class.java)
            .withBean(DataSource::class.java, { HikariDataSource().apply { maximumPoolSize = poolSize; jdbcUrl = "jdbc:mysql://127.0.0.1:1/none" } })
            .withPropertyValues(
                "paycore.payment.admin-ops.max-execution-window=PT24H", "paycore.payment.admin-ops.decision-stable-delay=PT2M",
                "paycore.payment.admin-ops.read-permits=4", "paycore.payment.admin-ops.command-permits=2", "paycore.payment.admin-ops.public-pool-reserve=4",
                "paycore.payment.admin-channel.clock-window=PT300S",
            )
        Then("pool 10 (Boot default) starts, pool 9 refuses to start") {
            runner(10).run { it.startupFailure shouldBe null }
            runner(9).run { it.startupFailure.shouldNotBeNull() }
        }
        Then("the execution window and stable delay are bound and checked only here (review n-4): out of range refuses to start") {
            runner(10).withPropertyValues("paycore.payment.admin-ops.max-execution-window=P8D").run { it.startupFailure.shouldNotBeNull() }
            runner(10).withPropertyValues("paycore.payment.admin-ops.decision-stable-delay=PT2H").run { it.startupFailure.shouldNotBeNull() }
        }
    }
})
