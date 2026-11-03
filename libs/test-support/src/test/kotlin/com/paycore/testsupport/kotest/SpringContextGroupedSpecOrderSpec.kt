package com.paycore.testsupport.kotest

import io.kotest.core.extensions.SpecExecutionOrderExtension
import io.kotest.core.spec.SpecRef
import io.kotest.provided.ProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource

/**
 * TEST-PERF-002: spec 순서만 바꾼다 — 이름순 정렬 뒤 같은 Spring 컨텍스트 설정(Spring 컨텍스트 캐시 key 기준)끼리 붙인다. 모든 spec이 한 번씩
 * 그대로 남는다. 아래 fixture는 abstract라 Kotest가 실행하지 않는다(정렬 입력으로만 쓴다). Docker·컨텍스트 기동 없음.
 */
class SpringContextGroupedSpecOrderSpec : FunSpec({

    fun ref(c: kotlin.reflect.KClass<out io.kotest.core.spec.Spec>): SpecRef = SpecRef.Reference(c, c.java.name)

    test("specs with the same Spring context configuration run next to each other; every spec is kept exactly once") {
        val input = listOf(
            ref(OrderFixture5::class), ref(OrderFixture4::class), ref(OrderFixture3::class), ref(OrderFixture2::class), ref(OrderFixture1::class),
            ref(OrderFixture6::class),
        )
        SpringContextGroupedSpecOrder().sort(input).map { it.kclass.simpleName } shouldBe listOf(
            "OrderFixture1", "OrderFixture3", // 같은 설정 A
            "OrderFixture2", "OrderFixture5", // 같은 설정 B
            "OrderFixture4", // Spring 아님
            "OrderFixture6", // 설정 A + 다른 속성 = 다른 컨텍스트
        )
    }

    test("without Spring the order is the plain name order") {
        SpringContextGroupedSpecOrder().sort(listOf(ref(OrderFixture4::class), ref(SpringContextGroupedSpecOrderSpec::class)))
            .map { it.kclass.simpleName } shouldBe listOf("OrderFixture4", "SpringContextGroupedSpecOrderSpec")
    }

    test("the shared Kotest project config registers exactly this order (Kotest then uses it instead of its default order)") {
        ProjectConfig().extensions.filterIsInstance<SpecExecutionOrderExtension>().map { it::class } shouldBe
            listOf(SpringContextGroupedSpecOrder::class)
    }
})

@Configuration
class OrderFixtureConfigA

@Configuration
class OrderFixtureConfigB

@ContextConfiguration(classes = [OrderFixtureConfigA::class])
abstract class OrderFixture1 : FunSpec()

@ContextConfiguration(classes = [OrderFixtureConfigB::class])
abstract class OrderFixture2 : FunSpec()

@ContextConfiguration(classes = [OrderFixtureConfigA::class])
abstract class OrderFixture3 : FunSpec()

abstract class OrderFixture4 : FunSpec()

@ContextConfiguration(classes = [OrderFixtureConfigB::class])
abstract class OrderFixture5 : FunSpec()

@ContextConfiguration(classes = [OrderFixtureConfigA::class])
@TestPropertySource(properties = ["paycore.order-fixture=other"])
abstract class OrderFixture6 : FunSpec()
