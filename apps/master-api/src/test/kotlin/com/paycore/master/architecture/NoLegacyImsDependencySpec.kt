package com.paycore.master.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * BUSINESS-PROFILE-001 BIZ-2-10(TC-20 — PC-BIZ-POL-001 §1-1 [확정] "기존 IMS는 분석·참조·연동·이관하지 않는다"): 저장소 검사로 IMS·레거시 연동·이관 흔적이 0건임을 고정한다.
 * 검사 대상(작업 디렉터리 = `apps/master-api`, 저장소 루트 = `../..`):
 * 1. `settings.gradle.kts`의 모듈 이름(`include(...)`).
 * 2. Gradle 의존성 좌표 — `gradle/libs.versions.toml` 전체와 루트·`apps/` 아래 `build.gradle.kts`의 의존성 선언 줄.
 * 3. `apps/` 아래 `src/main/resources/application*.yml`의 datasource URL·schema·Flyway 위치 줄.
 * 4. `apps/` 아래 `src/main/kotlin` 패키지(디렉터리) 이름.
 * 패턴: [IMS] `(?i)\bims\b`, [LEGACY] `(?i)legacy`(모듈·좌표·datasource·패키지 이름에서 — 코드 식별자 `legacyOperation` 같은 과도기 이름은 대상 밖), [MIGRATION_TARGET]
 * 사업자 경로의 이관 대상 이름 `(?i)(ims|legacy)[-_.]?(migration|import|sync)`. 검사 대상 수가 0이면 실패(빈 검사로 통과 금지). 대상·패턴은 시험 출력에 남긴다.
 */
class NoLegacyImsDependencySpec : FunSpec({

    val root = File("../..").canonicalFile
    val skip = setOf("node_modules", "build", ".git", ".gradle", ".idea", "out", "dist")
    fun files(base: File, maxDepth: Int, accept: (File) -> Boolean): List<File> =
        base.walkTopDown().maxDepth(maxDepth).onEnter { it.name !in skip }.filter { it.isFile && accept(it) }.toList()

    val ims = Regex("(?i)\\bims\\b")
    val legacy = Regex("(?i)legacy")
    val migrationTarget = Regex("(?i)(ims|legacy)[-_.]?(migration|import|sync)")
    val patterns = listOf(ims, legacy, migrationTarget)

    fun findings(target: String, lines: List<String>): List<String> =
        lines.withIndex().filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }.map { (i, line) -> "$target:${i + 1}: ${line.trim()}" }

    test("TC-20 settings, dependency coordinates, datasource configuration and package names carry no IMS / legacy integration or migration trace") {
        val settings = File(root, "settings.gradle.kts")
        settings.isFile shouldBe true
        val modules = settings.readLines().filter { it.contains("include(") }
        modules.size shouldBeGreaterThan 0

        val catalog = File(root, "gradle/libs.versions.toml")
        catalog.isFile shouldBe true
        val buildFiles = files(root, 3) { it.name == "build.gradle.kts" }
        buildFiles.size shouldBeGreaterThan 1
        val dependencyLines = buildFiles.flatMap { f ->
            f.readLines().filter { l -> Regex("^\\s*(implementation|api|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly|developmentOnly|annotationProcessor|kapt|ksp|jooqGenerator)\\b").containsMatchIn(l) }
                .map { f.relativeTo(root).path to it }
        }

        val configs = files(File(root, "apps"), 6) { it.name.matches(Regex("application.*\\.ya?ml")) && it.path.replace('\\', '/').contains("/src/main/resources/") }
        configs.size shouldBeGreaterThan 0
        val datasourceLines = configs.flatMap { f ->
            f.readLines().filter { l -> Regex("(?i)(url|jdbc|schema|locations|datasource|database)").containsMatchIn(l) }.map { f.relativeTo(root).path to it }
        }

        val packages = File(root, "apps").listFiles().orEmpty().map { File(it, "src/main/kotlin") }.filter { it.isDirectory }.flatMap { base ->
            base.walkTopDown().onEnter { it.name !in skip }.filter { it.isDirectory && it != base }.map { it.relativeTo(base).path.replace(File.separatorChar, '.') }.toList()
        }
        packages.size shouldBeGreaterThan 10

        val found = findings("settings.gradle.kts", modules) +
            findings("gradle/libs.versions.toml", catalog.readLines()) +
            dependencyLines.flatMap { (path, line) -> findings(path, listOf(line)) } +
            datasourceLines.flatMap { (path, line) -> findings(path, listOf(line)) } +
            packages.filter { p -> patterns.any { it.containsMatchIn(p) } }.map { "package: $it" }

        println(
            "TC-20 scan targets: modules=${modules.size} catalog=1 buildFiles=${buildFiles.size} dependencyLines=${dependencyLines.size} " +
                "configFiles=${configs.size} datasourceLines=${datasourceLines.size} packages=${packages.size}; patterns=${patterns.map { it.pattern }}",
        )
        found.shouldBeEmpty()
    }

    test("TC-20 control: the patterns do detect an IMS coordinate, a legacy datasource and a legacy migration package (no vacuous pass)") {
        findings("x", listOf("""implementation("com.example:ims-client:1.0")""")).size shouldBe 1
        findings("x", listOf("url: jdbc:mysql://legacy-db:3306/biz")).size shouldBe 1
        listOf("com.paycore.master.imsmigration", "com.paycore.master.legacy_import").count { p -> patterns.any { it.containsMatchIn(p) } } shouldBe 2
        findings("x", listOf("implementation(libs.spring.boot.starter.web)", "url: jdbc:mysql://localhost:3306/paycore_master")).shouldBeEmpty()
    }
})
