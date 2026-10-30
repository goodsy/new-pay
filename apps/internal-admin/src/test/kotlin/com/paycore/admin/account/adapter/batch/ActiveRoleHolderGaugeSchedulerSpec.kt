package com.paycore.admin.account.adapter.batch

import com.paycore.admin.account.adapter.integration.MicrometerAdminAccountMetrics
import com.paycore.admin.support.AdminDbTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * D-ADMIN1-023 지표 갱신 job 배선: 설정으로 켜면(운영 application.yml 기본 = 켬, 시험 기본 = 끔) 스케줄러가 등록되고 기동 직후 첫 실행이 두 역할 gauge를 만든다.
 * 1회성 명령 모드에서는 등록되지 않는다(OneShotModeAcceptedSpec).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["paycore.admin.accounts.active-role-gauge.enabled=true", "paycore.admin.accounts.active-role-gauge.fixed-delay=1h"],
)
@AdminDbTest
class ActiveRoleHolderGaugeSchedulerSpec(private val context: ApplicationContext, private val registry: MeterRegistry) : FunSpec({
    test("enabled → the scheduler bean exists and its first run registers the SECURITY_ADMIN and APPROVER gauges") {
        context.getBeanProvider(ActiveRoleHolderGaugeScheduler::class.java).ifAvailable.shouldNotBeNull()
        val deadline = System.nanoTime() + 20_000_000_000L
        fun registered() = listOf("SECURITY_ADMIN", "APPROVER").map { registry.find(MicrometerAdminAccountMetrics.ACTIVE_ROLE_HOLDERS).tag("role", it).gauge() != null }
        while (registered() != listOf(true, true) && System.nanoTime() < deadline) Thread.sleep(200)
        registered() shouldBe listOf(true, true)
    }
})
