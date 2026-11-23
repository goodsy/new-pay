package com.paycore.buildlogic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.InvalidUserDataException

/**
 * BUILD-PERF-MAC-001: 호스트별 병렬도 속성 해석. 속성이 없으면 저장소 기본값(TEST-PERF-001 안전값) 그대로이고,
 * 잘못된 값은 기본값으로 조용히 바꾸지 않고 거부한다.
 */
class BuildConcurrencySpec : FunSpec({

    context("slots") {
        val name = BuildConcurrency.DB_TEST_JVM_SLOTS

        test("absent property keeps the repository default") {
            BuildConcurrency.slots(name, null, BuildConcurrency.DEFAULT_DB_TEST_JVM_SLOTS) shouldBe 1
            BuildConcurrency.slots(BuildConcurrency.KOTLIN_COMPILE_SLOTS, null, BuildConcurrency.DEFAULT_KOTLIN_COMPILE_SLOTS) shouldBe 1
        }

        test("a positive integer is used as given, surrounding whitespace ignored") {
            BuildConcurrency.slots(name, "2", 1) shouldBe 2
            BuildConcurrency.slots(name, " 3 ", 1) shouldBe 3
            BuildConcurrency.slots(name, "1", 1) shouldBe 1
        }

        listOf("0", "-1", "", " ", "two", "2.5", "2g", "99999999999").forEach { bad ->
            test("rejects '$bad' with the property name in the message") {
                val e = shouldThrow<InvalidUserDataException> { BuildConcurrency.slots(name, bad, 1) }
                e.message shouldContain name
            }
        }
    }

    context("overrideSummary") {
        test("all repository defaults print nothing") {
            BuildConcurrency.overrideSummary(1, 1, "1g") shouldBe null
        }

        test("any non-default value prints one line with all three values") {
            BuildConcurrency.overrideSummary(3, 2, "1g") shouldBe "paycore concurrency: dbTestJvmSlots=3 kotlinCompileSlots=2 testMaxHeap=1g"
            BuildConcurrency.overrideSummary(1, 1, "1536m") shouldBe "paycore concurrency: dbTestJvmSlots=1 kotlinCompileSlots=1 testMaxHeap=1536m"
            BuildConcurrency.overrideSummary(2, 1, "1g") shouldBe "paycore concurrency: dbTestJvmSlots=2 kotlinCompileSlots=1 testMaxHeap=1g"
        }
    }

    context("heap") {
        val name = BuildConcurrency.TEST_MAX_HEAP

        test("absent property keeps the repository default 1g") {
            BuildConcurrency.heap(name, null, BuildConcurrency.DEFAULT_TEST_MAX_HEAP) shouldBe "1g"
        }

        test("accepts m/g sizes") {
            BuildConcurrency.heap(name, "1536m", "1g") shouldBe "1536m"
            BuildConcurrency.heap(name, "2G", "1g") shouldBe "2G"
            BuildConcurrency.heap(name, " 2g ", "1g") shouldBe "2g"
        }

        listOf("0g", "0", "1024", "-1g", "1.5g", "1gb", "1k", "", "large").forEach { bad ->
            test("rejects '$bad' with the property name in the message") {
                val e = shouldThrow<InvalidUserDataException> { BuildConcurrency.heap(name, bad, "1g") }
                e.message shouldContain name
            }
        }
    }
})
