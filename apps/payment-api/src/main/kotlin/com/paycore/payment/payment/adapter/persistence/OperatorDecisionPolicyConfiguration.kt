package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.OperatorApprovalWindow
import com.paycore.payment.payment.application.required.OperatorDecisionListSettings
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import javax.sql.DataSource

/**
 * 결정 판정·목록 값(application.required [OperatorApprovalWindow]·[OperatorDecisionListSettings])과 풀 대비 permit 기동 검사([AdminChannelPoolBudget])를 설정에서 만든다
 * (ADMIN-PAYMENT-OPS-001 D-OPS-002·§4.7·§4.17, I-50). web adapter는 required 타입·DataSource에 의존할 수 없어(ArchitectureSpec) persistence 구성에 둔다. 값은
 * `paycore.payment.admin-ops.{max-execution-window,decision-stable-delay,read-permits,command-permits,public-pool-reserve}`와 `paycore.payment.admin-channel.clock-window`
 * (approvedAt 미래 허용 = 서명 시각 창) — 기본값 없음(없으면 기동 실패).
 */
@Configuration(proxyBeanMethods = false)
class OperatorDecisionPolicyConfiguration {

    @Bean
    fun operatorApprovalWindow(environment: Environment): OperatorApprovalWindow {
        val binder = Binder.get(environment)
        val maxWindow = duration(binder, "paycore.payment.admin-ops.max-execution-window")
        // review n-4: 이 값의 유일한 바인딩·검증 위치(AdminOpsSettings에서 뺐다).
        require(maxWindow >= Duration.ofMinutes(1) && maxWindow <= Duration.ofDays(7)) { "admin-ops max-execution-window must be within 1m..7d" }
        return OperatorApprovalWindow(maxWindow, duration(binder, "paycore.payment.admin-channel.clock-window"))
    }

    @Bean
    fun adminChannelPoolBudget(environment: Environment, dataSource: DataSource): AdminChannelPoolBudget {
        val binder = Binder.get(environment)
        fun int(name: String): Int = binder.bind("paycore.payment.admin-ops.$name", Int::class.javaObjectType).orElseThrow { IllegalStateException("paycore.payment.admin-ops.$name is required") }
        val hikari = if (dataSource.isWrapperFor(HikariDataSource::class.java)) dataSource.unwrap(HikariDataSource::class.java) else null
        return AdminChannelPoolBudget(
            int("read-permits"), int("command-permits"), int("public-pool-reserve"), checkNotNull(hikari) { "the admin channel pool budget needs a Hikari pool" }.maximumPoolSize,
        )
    }

    @Bean
    fun operatorDecisionListSettings(environment: Environment): OperatorDecisionListSettings {
        val delay = duration(Binder.get(environment), "paycore.payment.admin-ops.decision-stable-delay")
        require(!delay.isNegative && delay <= Duration.ofHours(1)) { "admin-ops decision-stable-delay must be within 0..1h" }
        return OperatorDecisionListSettings(delay)
    }

    private fun duration(binder: Binder, name: String): Duration = binder.bind(name, Duration::class.java).orElseThrow { IllegalStateException("$name is required") }
}
