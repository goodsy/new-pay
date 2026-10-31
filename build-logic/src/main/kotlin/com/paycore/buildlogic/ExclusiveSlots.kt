package com.paycore.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * 동시에 실행되는 task 수의 상한을 거는 표식 서비스(TEST-PERF-001, 측정 근거: docs/repository/TECH-BASELINE.md §7.3).
 *
 * 상태가 없다. `paycore.kotlin-base`가 같은 종류의 task(Test, Kotlin 컴파일)에 `usesService`로 연결하고 `maxParallelUsages`를 정하면,
 * Gradle은 모듈 병렬 실행(`org.gradle.parallel`) 중에도 그 수를 넘는 task를 동시에 시작하지 않는다. 실행되는 task·시험·판정 기준은
 * 바뀌지 않는다(스케줄만 바뀐다).
 */
abstract class ExclusiveSlots : BuildService<BuildServiceParameters.None>
