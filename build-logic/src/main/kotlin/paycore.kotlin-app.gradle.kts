// PayCore 실행 단위(apps/*) 공통 설정. 각 app build 파일에는 그 app의 의존성과 고유 설정만 둔다.
plugins {
    id("paycore.kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    id("org.springframework.boot")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

// JPA Entity는 Hibernate 프록시를 위해 open이어야 한다. 순수 Domain 모델에는 JPA annotation이 없으므로 영향이 없다
// (Entity는 adapter.persistence에만 둔다 — PayCoreArchitectureRules가 검사). DB가 없는 app에는 해당 클래스가 없어 무효과다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // Mockito 제외는 paycore.kotlin-base의 테스트 classpath exclude가 담당한다.
    "testImplementation"(catalog.findLibrary("spring-boot-starter-test").get())
}

// 실행 단위의 Test task(test JVM + Spring 컨텍스트별 MySQL 컨테이너)는 모듈 병렬 실행(org.gradle.parallel) 중에도 한 번에 하나만 돈다
// (TEST-PERF-001, 측정 근거: docs/repository/TECH-BASELINE.md §7.3 — 두 개를 겹치면 WSL 7.8GB에서 가용 메모리가 바닥난다). 순서만 정할 뿐
// 실행되는 시험·판정 기준은 같다.
val dbTestJvmSlots = gradle.sharedServices.registerIfAbsent("paycoreDbTestJvmSlots", com.paycore.buildlogic.ExclusiveSlots::class) {
    maxParallelUsages = 1
}
tasks.withType<Test>().configureEach { usesService(dbTestJvmSlots) }

// ---------------------------------------------------------------------------------------------
// 실행 단위 runtime에 테스트 전용 도구가 새지 않는다(Test Fixture Policy: Fixture Monkey는 테스트 전용, CP-DB-001).
// runtimeClasspath(배포물·SBOM 대상)에 아래 group이 있으면 check가 실패한다. 테스트 classpath는 대상이 아니다.
// ---------------------------------------------------------------------------------------------
val testOnlyGroups = listOf(
    "com.navercorp.fixturemonkey:",
    "org.instancio:",
    "net.jqwik:",
    "io.kotest:",
    "io.mockk:",
    "com.ninja-squad:springmockk:",
    "com.tngtech.archunit:",
    "org.testcontainers:",
)

val verifyRuntimeHasNoTestTools = tasks.register("verifyRuntimeHasNoTestTools") {
    group = "verification"
    description = "Fails if a test-only tool (Fixture Monkey, Instancio, jqwik, Kotest, MockK, ArchUnit, Testcontainers) is on runtimeClasspath."
    val runtimeModules = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
        val modules = linkedSetOf<String>()
        val visited = mutableSetOf<Any>()
        val queue = ArrayDeque<org.gradle.api.artifacts.result.ResolvedComponentResult>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component.id)) continue
            component.moduleVersion?.let { modules += "${it.group}:${it.name}:${it.version}" }
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { queue.add(it.selected) }
        }
        modules.toList()
    }
    doLast {
        val found = runtimeModules.get().filter { m -> testOnlyGroups.any(m::startsWith) }
        if (found.isNotEmpty()) throw GradleException("test-only tools on runtimeClasspath (Test Fixture Policy): $found")
        logger.lifecycle("verifyRuntimeHasNoTestTools: ${runtimeModules.get().size} runtime modules, no test-only tools")
    }
}

tasks.named("check") { dependsOn(verifyRuntimeHasNoTestTools) }
