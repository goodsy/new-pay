package com.paycore.admin.account.adapter.batch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Schedules
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import org.springframework.web.context.WebApplicationContext

/**
 * 1회성 명령 속성(`paycore.admin.command`)이 있는 프로세스의 가드(ADMIN-BOOT-002 I-11·I-12 — ADMIN-BOOT-001 §4.9 "명령 모드는 비웹·스케줄러 없이"의 보완).
 * 등록 조건은 [OneShotCommandRunner]와 같은 속성 하나다(인자·설정 파일·환경변수 `PAYCORE_ADMIN_COMMAND` 어느 경로든).
 * - **웹 애플리케이션 컨텍스트**(`WebApplicationContext` — 이 앱은 서블릿 전용)면 기동을 실패시킨다(fail-closed — 최종 리뷰 Major-1, Coordinator 결정). 서비스 중인 ADMIN이 명령 속성 하나로
 *   주기 job을 조용히 잃거나 경고 한 줄만 남기고 도는 일을 막는다. 예외 메시지는 고정 code [WEB_CONTEXT_REFUSAL]뿐(속성 값 없음).
 * - **비웹 명령 컨텍스트**면 빈 정의 단계에서 애플리케이션 패키지(`com.paycore.admin.`)의 `@Scheduled`·`@Schedules` 메서드를 가진 빈 정의를 모두 지운다 —
 *   만료·회복·대사·gauge job과 이후 job이 각자의 켬 설정과 무관하게 등록되지 않는다.
 *
 * 한계(Nit — 명세 I-11): 판정은 빈 정의의 `beanClassName`으로 한다. ① `@Bean` factory-method로 정의된 빈(정의에 클래스 이름이 없거나 구성 클래스 이름)은
 * 판정하지 못하고, ② 애플리케이션 패키지 밖 클래스, ③ `@Scheduled` 없이 `SchedulingConfigurer`·직접 `TaskScheduler`로 예약하는 코드도 대상이 아니다.
 * 현재 job은 모두 패키지 안의 `@Component` + `@Scheduled`이고, 명령 모드의 예약 작업 0은 `OneShotModePeriodicJobsSpec`이 `ScheduledTaskHolder`로 확인한다.
 * 위치: 기본 패키지 루트는 진입점 전용(ArchitectureSpec)이라 명령 모드 소유자인 `account.adapter.batch`에 둔다(다른 모듈 타입을 import하지 않는다).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin", name = ["command"])
class OneShotModePeriodicJobGuard : BeanDefinitionRegistryPostProcessor, ApplicationContextAware {

    /** 알 수 없으면 웹으로 본다(fail-closed). */
    private var webContext: Boolean = true

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        webContext = applicationContext is WebApplicationContext
    }

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        check(!webContext) { "$WEB_CONTEXT_REFUSAL: the one-shot command property must not reach a web application context; refusing to start" }
        registry.beanDefinitionNames.filter { name -> hasScheduledMethod(registry.getBeanDefinition(name).beanClassName) }.forEach { name ->
            registry.removeBeanDefinition(name)
            log.info("operation=one-shot result=periodic-job-skipped bean={}", name)
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
        /** 웹 컨텍스트에 명령 속성이 있을 때 기동 실패 예외 메시지의 고정 code(값 없음). */
        const val WEB_CONTEXT_REFUSAL = "ONE_SHOT_COMMAND_IN_WEB_CONTEXT"
        private const val APPLICATION_PACKAGE = "com.paycore.admin."
        private val log = LoggerFactory.getLogger(OneShotModePeriodicJobGuard::class.java)
    }
}
