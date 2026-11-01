package com.paycore.master.merchant.adapter.batch

import com.paycore.master.merchant.application.DemoMerchantSeedService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.EnvironmentAware
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Schedules
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import org.springframework.web.context.WebApplicationContext

/**
 * 명령 속성(`paycore.master.command`)이 있는 프로세스의 가드(DEMO-SEED-001 명세 §4.6 — internal-admin ADMIN-BOOT-002 I-11·I-12와 같은 판정).
 * - **웹 애플리케이션 컨텍스트**면 빈 정의 단계에서 기동을 실패시킨다(fail-closed, 고정 code [WEB_CONTEXT_REFUSAL], 값 없음).
 * - **비웹 명령 컨텍스트**면 `com.paycore.master.` 패키지의 `@Scheduled`·`@Schedules` 메서드를 가진 빈 정의(relay·lease 갱신 등)를 지운다 —
 *   진입점이 덧붙인 끔 인자와 함께 이중으로 막는다.
 * - 명령이 `demo-seed`이면 seed 조정자([DemoMerchantSeedService])를 **여기서만** 등록한다(리뷰 m-1 — 일반 웹 컨텍스트에는 빈 정의가 없다).
 *
 * 한계(ADMIN I-11과 같음): 판정은 빈 정의의 `beanClassName`이다. `@Bean` factory-method 빈·패키지 밖 클래스·`SchedulingConfigurer`로 예약하는 코드는 대상이 아니다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.master", name = ["command"])
class OneShotModeGuard : BeanDefinitionRegistryPostProcessor, ApplicationContextAware, EnvironmentAware {

    /** 알 수 없으면 웹으로 본다(fail-closed). */
    private var webContext: Boolean = true

    private var command: String? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        webContext = applicationContext is WebApplicationContext
    }

    override fun setEnvironment(environment: Environment) {
        command = environment.getProperty(DemoCommandModeGate.COMMAND_PROPERTY)
    }

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        check(!webContext) { "$WEB_CONTEXT_REFUSAL: the one-shot command property must not reach a web application context; refusing to start" }
        registry.beanDefinitionNames.filter { name -> hasScheduledMethod(registry.getBeanDefinition(name).beanClassName) }.forEach { name ->
            registry.removeBeanDefinition(name)
            log.info("operation=one-shot result=periodic-job-skipped bean={}", name)
        }
        if (command == DemoSeedCommandLine.COMMAND) {
            registry.registerBeanDefinition(
                DEMO_SEED_SERVICE, RootBeanDefinition(DemoMerchantSeedService::class.java).apply { autowireMode = AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR },
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

    private fun hasScheduledMethod(className: String?): Boolean {
        if (className == null || !className.startsWith(APPLICATION_PACKAGE)) return false
        val type = runCatching { ClassUtils.forName(className, javaClass.classLoader) }.getOrNull() ?: return false
        var found = false
        ReflectionUtils.doWithMethods(type) { method ->
            if (AnnotatedElementUtils.hasAnnotation(method, Scheduled::class.java) || AnnotatedElementUtils.hasAnnotation(method, Schedules::class.java)) found = true
        }
        return found
    }

    companion object {
        const val WEB_CONTEXT_REFUSAL = "ONE_SHOT_COMMAND_IN_WEB_CONTEXT"
        const val DEMO_SEED_SERVICE = "demoMerchantSeedService"
        private const val APPLICATION_PACKAGE = "com.paycore.master."
        private val log = LoggerFactory.getLogger(OneShotModeGuard::class.java)
    }
}
