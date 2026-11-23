package com.paycore.buildlogic

import org.gradle.api.InvalidUserDataException
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * 호스트별 빌드 병렬도 속성(BUILD-PERF-MAC-001, 측정 근거: docs/repository/TECH-BASELINE.md §7.3 "호스트별 병렬도 속성").
 *
 * 저장소 기본값은 메모리가 작은 PC·CI에서 측정한 안전값 그대로다(DB test JVM 1, Kotlin 컴파일 1, test heap 1g — TEST-PERF-001 §7.3).
 * 메모리가 큰 호스트만 저장소 밖 `~/.gradle/gradle.properties`(GRADLE_USER_HOME, 프로젝트 속성보다 우선)에 아래 속성을 둬서 올린다.
 * 스케줄과 test JVM heap 상한만 바뀐다 — 실행되는 task·시험·판정 기준(GRADLE-CHECK argv·timeout)은 같다.
 *
 * 잘못된 값(숫자 아님, 0 이하, 단위 없는 heap)은 기본값으로 조용히 바꾸지 않고 빌드를 실패시킨다.
 * 기본값과 다른 값이 하나라도 있으면 빌드마다 한 번 `paycore concurrency: …` 한 줄을 lifecycle 로그로 남긴다(기본값이면 출력 없음).
 *
 * 전제: 하위 프로젝트(apps·libs) 디렉터리에는 gradle.properties가 없다. 속성은 루트 build·GRADLE_USER_HOME·`-P`에서만 오므로 모든 프로젝트가
 * 같은 값을 읽는다(하위 프로젝트에 이 속성을 두면 먼저 등록한 프로젝트의 값이 서비스 상한이 된다).
 */
object BuildConcurrency {
    const val DB_TEST_JVM_SLOTS = "paycore.dbTestJvmSlots"
    const val KOTLIN_COMPILE_SLOTS = "paycore.kotlinCompileSlots"
    const val TEST_MAX_HEAP = "paycore.testMaxHeap"

    const val DEFAULT_DB_TEST_JVM_SLOTS = 1
    const val DEFAULT_KOTLIN_COMPILE_SLOTS = 1
    const val DEFAULT_TEST_MAX_HEAP = "1g"

    /** ExclusiveSlots 서비스 이름. 같은 이름을 쓰는 곳(kotlin-base·kotlin-app·frontend)은 모두 아래 register 함수로만 등록한다. */
    const val DB_TEST_JVM_SLOTS_SERVICE = "paycoreDbTestJvmSlots"
    const val KOTLIN_COMPILE_SLOTS_SERVICE = "paycoreKotlinCompileSlots"

    // 형식만 검사한다. 상한(호스트 메모리를 넘는 값 등)은 검사하지 않는다 — 너무 큰 값은 test JVM 기동 시 JVM이 거부한다.
    private val heapPattern = Regex("^[1-9][0-9]*[mMgG]$")

    /** 동시 실행 수 속성. 없으면 [default], 양의 정수가 아니면 [InvalidUserDataException]. */
    fun slots(name: String, raw: String?, default: Int): Int {
        if (raw == null) return default
        val value = raw.trim().toIntOrNull()
            ?: throw InvalidUserDataException("Gradle property '$name' must be a positive integer, but was '$raw'")
        if (value < 1) throw InvalidUserDataException("Gradle property '$name' must be a positive integer (>= 1), but was '$raw'")
        return value
    }

    /** test JVM heap 상한 속성(`-Xmx` 형식, 단위 m/g 필수). 없으면 [default], 형식이 틀리면 [InvalidUserDataException]. */
    fun heap(name: String, raw: String?, default: String): String {
        if (raw == null) return default
        val value = raw.trim()
        if (!heapPattern.matches(value)) {
            throw InvalidUserDataException("Gradle property '$name' must be a heap size like 1g or 1536m (unit m or g, > 0), but was '$raw'")
        }
        return value
    }

    fun dbTestJvmSlots(providers: ProviderFactory): Int =
        slots(DB_TEST_JVM_SLOTS, providers.gradleProperty(DB_TEST_JVM_SLOTS).orNull, DEFAULT_DB_TEST_JVM_SLOTS)

    fun kotlinCompileSlots(providers: ProviderFactory): Int =
        slots(KOTLIN_COMPILE_SLOTS, providers.gradleProperty(KOTLIN_COMPILE_SLOTS).orNull, DEFAULT_KOTLIN_COMPILE_SLOTS)

    fun testMaxHeap(providers: ProviderFactory): String =
        heap(TEST_MAX_HEAP, providers.gradleProperty(TEST_MAX_HEAP).orNull, DEFAULT_TEST_MAX_HEAP)

    /** 세 값이 모두 기본값이면 null, 하나라도 다르면 로그 한 줄. */
    fun overrideSummary(dbTestJvmSlots: Int, kotlinCompileSlots: Int, testMaxHeap: String): String? =
        if (dbTestJvmSlots == DEFAULT_DB_TEST_JVM_SLOTS && kotlinCompileSlots == DEFAULT_KOTLIN_COMPILE_SLOTS && testMaxHeap == DEFAULT_TEST_MAX_HEAP) {
            null
        } else {
            "paycore concurrency: dbTestJvmSlots=$dbTestJvmSlots kotlinCompileSlots=$kotlinCompileSlots testMaxHeap=$testMaxHeap"
        }

    /** 실행 단위(apps 아래 모듈) Test task(test JVM + 그 MySQL 컨테이너) 동시 수. 어느 plugin이 먼저 등록해도 같은 속성 값을 쓴다. */
    fun registerDbTestJvmSlots(gradle: Gradle, providers: ProviderFactory): Provider<ExclusiveSlots> {
        val max = dbTestJvmSlots(providers)
        return gradle.sharedServices.registerIfAbsent(DB_TEST_JVM_SLOTS_SERVICE, ExclusiveSlots::class.java) { maxParallelUsages.set(max) }
    }

    /**
     * in-process Kotlin 컴파일 동시 수(Gradle daemon heap을 나눠 쓴다). 어느 plugin이 먼저 등록해도 같은 속성 값을 쓴다.
     * 모든 Kotlin 모듈(paycore.kotlin-base)이 부르므로 등록 람다(빌드에 한 번만 실행)에서 [overrideSummary] 로그를 남긴다.
     */
    fun registerKotlinCompileSlots(gradle: Gradle, providers: ProviderFactory): Provider<ExclusiveSlots> {
        val max = kotlinCompileSlots(providers)
        return gradle.sharedServices.registerIfAbsent(KOTLIN_COMPILE_SLOTS_SERVICE, ExclusiveSlots::class.java) {
            maxParallelUsages.set(max)
            overrideSummary(dbTestJvmSlots(providers), max, testMaxHeap(providers))?.let { Logging.getLogger(BuildConcurrency::class.java).lifecycle(it) }
        }
    }
}
