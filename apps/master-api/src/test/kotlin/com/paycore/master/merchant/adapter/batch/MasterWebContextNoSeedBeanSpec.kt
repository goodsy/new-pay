package com.paycore.master.merchant.adapter.batch

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.DemoMerchantSeedService
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.context.ApplicationContext
import org.springframework.web.context.WebApplicationContext

/**
 * DEMO-SEED-001 리뷰 m-1: 명령 속성이 없는 일반 master **웹** 컨텍스트(MasterDbTest — 서블릿)에는 seed 조정자(Use Case)·명령 adapter·명령 가드 빈이 없다.
 * seed는 `demo-seed` 명령 프로세스에서만 부를 수 있다(가드가 그때만 조정자를 등록한다).
 */
@MasterDbTest
class MasterWebContextNoSeedBeanSpec(private val context: ApplicationContext) : FunSpec({

    test("the context is a web application context without the command property") {
        (context is WebApplicationContext) shouldBe true
        context.environment.containsProperty("paycore.master.command") shouldBe false
    }

    test("no seed use case, seed coordinator, command line, runner, console or command guard bean exists") {
        listOf(
            DemoMerchantSeedUseCase::class.java, DemoMerchantSeedService::class.java, DemoSeedCommandLine::class.java, DemoSeedCommandRunner::class.java,
            ConsoleAccess::class.java, OneShotModeGuard::class.java,
        ).forEach { type -> context.getBeanNamesForType(type).toList().shouldBeEmpty() }
        context.beanDefinitionNames.filter { it.contains("demoMerchantSeed", ignoreCase = true) || it.contains("demoSeed", ignoreCase = true) }.shouldBeEmpty()
    }
})
