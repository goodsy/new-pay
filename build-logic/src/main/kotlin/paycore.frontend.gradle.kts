import com.paycore.buildlogic.frontend.FrontendLockfileCheckTask
import com.paycore.buildlogic.frontend.FrontendLockfileRegenerateTask
import com.paycore.buildlogic.frontend.FrontendNpmEnvironment
import java.security.MessageDigest

// React SPA 프론트 빌드 convention(ADMIN-BOOT-001 §4.11, CHECKOUT-REACT-001 재사용). 적용 모듈의 `frontend/` 디렉터리를 빌드한다.
// - 고정 Node 배포판: node-gradle plugin이 settings의 nodejs-dist ivy 저장소(F-8)에서 내려받아 푼다. 압축 SHA-256은 frontendNodeVerify가
//   catalog 값과 대조한 뒤에만 풀린다(F-9).
// - npm·도구 실행은 Exec task + 격리 환경(FrontendNpmEnvironment — npm_config_*·~/.npmrc 영향 제거, S0 §17.7). argv는 고정이다.
// - check ← frontendLockfileCheck·frontendTypecheck·frontendTest, processResources ← frontendBuild(static/).
// - Vitest JUnit 보고서: <module>/build/test-results/frontend/TEST-vitest.xml(TEST-SUMMARY가 자식 suite별로 집계).
plugins {
    id("com.github.node-gradle.node")
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val pinnedNodeVersion: String = catalog.findVersion("node").get().requiredVersion
val nodeSha256: String = catalog.findVersion("node-sha256-linux-x64").get().requiredVersion

val frontendDir = layout.projectDirectory.dir("frontend")
val nodeWorkDir = layout.projectDirectory.dir(".gradle/nodejs")
val nodeHome = nodeWorkDir.dir("node-v$pinnedNodeVersion-linux-x64")
val nodeExecutableFile = nodeHome.file("bin/node")
val npmCliFile = nodeHome.file("lib/node_modules/npm/bin/npm-cli.js")
val npmWork = layout.buildDirectory.dir("frontend-npm")
val testReports = layout.buildDirectory.dir("test-results/frontend")
val distDir = layout.buildDirectory.dir("frontend-dist")

node {
    version.set(pinnedNodeVersion)
    download.set(true)
    // 비우면 plugin이 project 저장소를 추가하지 않는다(FAIL_ON_PROJECT_REPOS — settings의 nodejs-dist가 해석).
    distBaseUrl.set(null as String?)
    workDir.set(nodeWorkDir)
    nodeProjectDir.set(frontendDir)
}

val frontendNodeDistribution = configurations.create("frontendNodeDistribution") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { frontendNodeDistribution("org.nodejs:node:$pinnedNodeVersion:linux-x64@tar.gz") }

val frontendNodeVerify = tasks.register("frontendNodeVerify") {
    group = "verification"
    description = "Verifies the resolved Node.js archive against the catalog SHA-256 before it is unpacked."
    val archive = frontendNodeDistribution
    val expected = nodeSha256
    inputs.files(archive)
    doLast {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        if (os != "Linux" || arch !in setOf("amd64", "x86_64")) {
            throw GradleException("paycore.frontend supports linux-x64 only (WSL/CI); this host is $os/$arch")
        }
        val file = archive.singleFile
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) throw GradleException("Node.js archive ${file.name} sha256 $actual != catalog $expected")
        logger.lifecycle("frontendNodeVerify: ${file.name} sha256=$actual")
    }
}
tasks.named("nodeSetup") { dependsOn(frontendNodeVerify) }

fun isolatedEnvironment(): Map<String, String> {
    val work = npmWork.get().asFile
    return FrontendNpmEnvironment.of(
        nodeBinDir = nodeHome.dir("bin").asFile,
        home = work.resolve("home"),
        emptyUserConfig = work.resolve("empty-npmrc"),
        npmCache = layout.projectDirectory.dir(".gradle/npm-cache").asFile,
    )
}

fun Exec.runNode(vararg arguments: Any) {
    dependsOn("nodeSetup")
    workingDir = frontendDir.asFile
    executable = nodeExecutableFile.asFile.path
    args(*arguments)
    doFirst {
        val work = npmWork.get().asFile
        work.resolve("home").mkdirs()
        work.resolve("empty-npmrc").writeText("")
        // 상속 환경을 버리고 격리 환경만 쓴다(setEnvironment는 교체).
        setEnvironment(isolatedEnvironment())
    }
}

val frontendLockfileCheck = tasks.register<FrontendLockfileCheckTask>("frontendLockfileCheck") {
    group = "verification"
    description = "Checks package.json/package-lock.json/.npmrc supply-chain rules (offline)."
    packageJson.set(frontendDir.file("package.json"))
    lockfile.set(frontendDir.file("package-lock.json"))
    npmrc.set(frontendDir.file(".npmrc"))
    policy.set(frontendDir.file("lockfile-policy.json"))
    this.nodeVersion.set(pinnedNodeVersion)
    marker.set(layout.buildDirectory.file("frontend-checks/lockfile.ok"))
}

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    group = "build"
    description = "npm ci --ignore-scripts in an isolated npm environment."
    dependsOn(frontendLockfileCheck)
    runNode(npmCliFile.asFile.path, "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"), frontendDir.file(".npmrc"))
    outputs.file(frontendDir.file("node_modules/.package-lock.json"))
}

// 최상위 *.html과 docs/(UI 참조 원본 — 재현 시험이 읽는다)도 입력이다. 관리자 콘솔의 ui-reference.html은 typecheck·test 입력일 뿐
// 기본 빌드(frontendBuild)에는 들어가지 않는다(Vite mode 'uiref' 전용 — vite.config.ts).
// 저장소 docs/pg-workspace-react-kit(PG Workspace React kit — UI 참조 미리보기 시험이 원본 CSS·legacy.json 해시를 대조한다)도 시험 입력이다.
val frontendSources = files(
    frontendDir.dir("src"), fileTree(frontendDir) { include("*.html") }, frontendDir.dir("docs"), frontendDir.file("tsconfig.json"),
    frontendDir.file("vite.config.ts"), frontendDir.file("vitest.config.ts"),
    rootProject.layout.projectDirectory.dir("docs/pg-workspace-react-kit"),
)

val frontendTypecheck = tasks.register<Exec>("frontendTypecheck") {
    group = "verification"
    description = "TypeScript type check (tsc -p tsconfig.json, no emit)."
    dependsOn(frontendInstall)
    runNode("node_modules/typescript/bin/tsc", "-p", "tsconfig.json")
    inputs.files(frontendSources)
}

val frontendTest = tasks.register<Exec>("frontendTest") {
    group = "verification"
    description = "Vitest run (allowOnly=false, passWithNoTests=false) with a JUnit report under build/test-results/frontend."
    dependsOn(frontendInstall)
    val reports = testReports
    doFirst { delete(reports) } // 이전 실행 보고서가 남아 신선도 판정을 흐리지 않게
    runNode("node_modules/vitest/vitest.mjs", "run")
    inputs.files(frontendSources)
    outputs.dir(reports)
    // 모듈 병렬 실행(TEST-PERF-001, docs/repository/TECH-BASELINE.md §7.3)에서 Vitest가 Kotlin 컴파일·DB test JVM과 CPU를 나눠 쓰면 시험별 timeout
    // (기본 5초)에 걸렸다(실측 1건). 시험 timeout은 바꾸지 않고, 이 둘과 겹치지 않게만 한다(같은 이름의 ExclusiveSlots — kotlin-base·kotlin-app).
    usesService(gradle.sharedServices.registerIfAbsent("paycoreKotlinCompileSlots", com.paycore.buildlogic.ExclusiveSlots::class) { maxParallelUsages = 1 })
    usesService(gradle.sharedServices.registerIfAbsent("paycoreDbTestJvmSlots", com.paycore.buildlogic.ExclusiveSlots::class) { maxParallelUsages = 1 })
    // GATE-LOAD-001: 모든 모듈의 jOOQ 생성기(generateJooq* JavaExec — MySQL 컨테이너 기동 + Flyway + codegen)가 끝난 뒤에만 돈다(순서만, 의존 아님 —
    // 생성기를 실행 대상에 넣지 않는다). gate9(MERCHANT-OPS-002-2026-11-11T233256Z)에서 financial-batch codegen과 겹쳐 Vitest 2건이 5초 timeout.
    // 생성기를 DB test 슬롯에 넣었던 1차 안은 gate10(…-2026-11-12T022929Z)에서 codegen이 앞 모듈 시험 뒤로 밀려 큰 모듈 컴파일이 늦게 시작 →
    // GRADLE-CHECK 3300초 timeout이라 되돌렸다. codegen끼리·codegen과 시험은 예전처럼 병렬. 이름 필터(named)라 다른 모듈 task를 미리 만들지 않는다.
    mustRunAfter(rootProject.allprojects.map { p -> p.tasks.withType<JavaExec>().named { it.startsWith("generateJooq") } })
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "build"
    description = "Vite production build into build/frontend-dist (served from static/)."
    dependsOn(frontendInstall)
    runNode("node_modules/vite/bin/vite.js", "build")
    inputs.files(frontendSources)
    outputs.dir(distDir)
}

tasks.register<FrontendLockfileRegenerateTask>("frontendLockfileRegenerate") {
    group = "frontend maintenance"
    description = "Regenerates package-lock.json with the two-step --before + security override procedure (network)."
    dependsOn("nodeSetup")
    this.frontendDir.set(layout.projectDirectory.dir("frontend"))
    workDir.set(layout.buildDirectory.dir("frontend-lock-regenerate"))
    this.nodeExecutable.set(nodeExecutableFile)
    this.npmCli.set(npmCliFile)
    environment.set(provider { isolatedEnvironment() })
    doFirst {
        npmWork.get().asFile.resolve("home").mkdirs()
        npmWork.get().asFile.resolve("empty-npmrc").writeText("")
    }
}

tasks.named("check") { dependsOn(frontendLockfileCheck, frontendTypecheck, frontendTest) }
tasks.named<ProcessResources>("processResources") {
    from(frontendBuild) { into("static") }
}
