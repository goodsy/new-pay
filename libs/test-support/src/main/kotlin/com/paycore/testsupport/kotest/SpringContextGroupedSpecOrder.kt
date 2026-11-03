package com.paycore.testsupport.kotest

import io.kotest.core.extensions.SpecExecutionOrderExtension
import io.kotest.core.spec.SpecRef
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.test.context.BootstrapUtils
import org.springframework.test.context.BootstrapWith
import org.springframework.test.context.ContextConfiguration

/**
 * spec 실행 순서: 이름순(Kotest 기본 Lexicographic과 같은 기준)으로 정렬한 뒤, **같은 Spring 테스트 컨텍스트 설정**을 쓰는 spec을 붙여 실행한다
 * (TEST-PERF-002, docs/repository/TECH-BASELINE.md §7.4). 컨텍스트 설정이 같은지는 Spring이 컨텍스트 캐시 key로 쓰는 것과 같은
 * `MergedContextConfiguration`의 동등성으로 판정한다(이 저장소의 별도 규칙이 아니다).
 *
 * 왜: 컨텍스트 캐시 크기 상한(예: internal-admin `spring.test.context.cache.maxSize=4` — 메모리 상한)이 있는 모듈에서 이름순으로 섞여 실행되면
 * 같은 설정의 컨텍스트가 밀려났다가 다시 만들어진다(측정: internal-admin 설정 15종에 컨텍스트 기동 30회 — 매번 새 MySQL·Flyway·Spring 기동).
 * 같은 설정끼리 붙이면 설정마다 한 번만 기동한다.
 *
 * 바뀌는 것은 spec **순서**뿐이다. 실행되는 spec·시험·단언·컨텍스트 설정·캐시 상한은 같다. 같은 컨텍스트를 쓰는 spec끼리는 원래도 DB 상태를
 * 공유하므로 각 spec은 다른 spec의 실행 여부·순서에 기대지 않아야 한다(순서가 바뀌어 실패하면 그 시험의 격리 결함이다 — 감추지 않는다).
 * Spring을 쓰지 않는 spec은 한 묶음이다. 설정 계산이 실패한 spec은 자기 혼자 한 묶음이 된다(실패는 그 spec 실행 때 Spring이 그대로 보고한다).
 */
class SpringContextGroupedSpecOrder : SpecExecutionOrderExtension {

    override fun sort(specs: List<SpecRef>): List<SpecRef> {
        val started = System.nanoTime()
        val groups = LinkedHashMap<Any, MutableList<SpecRef>>()
        specs.sortedBy { it.fqn }.forEach { spec -> groups.getOrPut(contextKey(spec.kclass.java)) { mutableListOf() } += spec }
        println("[SpringContextGroupedSpecOrder] ${specs.size} specs in ${groups.size} groups, sorted in ${(System.nanoTime() - started) / 1_000_000}ms")
        return groups.values.flatten()
    }

    companion object {
        private object NoSpringContext

        /** Spring 테스트 컨텍스트 캐시 key와 같은 기준의 묶음 key. */
        fun contextKey(specClass: Class<*>): Any {
            val spring = AnnotatedElementUtils.hasAnnotation(specClass, BootstrapWith::class.java) ||
                AnnotatedElementUtils.hasAnnotation(specClass, ContextConfiguration::class.java)
            if (!spring) return NoSpringContext
            return runCatching { BootstrapUtils.resolveTestContextBootstrapper(specClass).buildMergedContextConfiguration() }.getOrElse { specClass }
        }
    }
}
