import org.cyclonedx.gradle.CyclonedxDirectTask

// Kotlin/Spring Boot plugin은 build-logic의 convention plugin(paycore.kotlin-app/-library)이 적용한다.
// 여기에는 루트가 직접 쓰는 CycloneDX만 둔다(아래 CyclonedxDirectTask 설정).
plugins {
    alias(libs.plugins.cyclonedx) apply false
}

// 보안 patch override (BOM 버전 위). 근거·제거 조건: docs/repository/TECH-BASELINE.md §1.1
// 같은 minor 안의 patch만 올린다. override는 아래 Boot 버전에만 유효하다. Boot를 올리면 빌드를 멈춰
// 새 BOM 버전과 비교해 override를 제거하거나 다시 근거를 남기게 한다(오래된 patch로 조용히 되돌리는 것 방지).
val securityPatchBootVersion = "4.1.1"
check(libs.versions.spring.boot.get() == securityPatchBootVersion) {
    "Spring Boot ${libs.versions.spring.boot.get()} != $securityPatchBootVersion: re-evaluate security patch overrides " +
        "(TECH-BASELINE §1.1) — remove them if the new BOM already manages fixed versions."
}
val securityPatchVersions = mapOf(
    "org.apache.tomcat.embed" to libs.versions.security.patch.tomcat.get(),
    "tools.jackson.core" to libs.versions.security.patch.jackson.get(),
    "tools.jackson.module" to libs.versions.security.patch.jackson.get(),
    "tools.jackson.datatype" to libs.versions.security.patch.jackson.get(),
    "tools.jackson.dataformat" to libs.versions.security.patch.jackson.get(),
)

// 숫자 dotted 버전 비교("11.0.27" > "11.0.26"). pre-release 등 숫자가 아닌 부분이 있으면 GA patch보다 새롭다고 보지 않는다
// (그 경우 patch를 강제해 runtime에 pre-release가 남지 않게 한다).
fun isNewer(candidate: String, than: String): Boolean {
    if (!candidate.matches(Regex("""\d+(\.\d+)*"""))) return false
    val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val b = than.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            securityPatchVersions[requested.group]?.let { patched ->
                // 올리기만 한다. 다른 의존성이 더 새 버전을 명시하면 그대로 둔다(오래된 patch로 내리지 않음).
                val requestedVersion = requested.version.orEmpty()
                if (requestedVersion.isEmpty() || !isNewer(requestedVersion, patched)) {
                    useVersion(patched)
                    because("security patch override over Spring Boot BOM (TECH-BASELINE §1.1)")
                }
            }
        }
    }

    // 실행 단위(apps/*)마다 runtime 의존성 SBOM(CycloneDX)을 만든다. 취약점 검사 입력이자 배포 증거.
    if (path.startsWith(":apps:")) {
        apply(plugin = "org.cyclonedx.bom")
        tasks.withType<CyclonedxDirectTask>().configureEach {
            includeConfigs.set(listOf("runtimeClasspath"))
        }
    }
}

// 루트 `check`는 included build(build-logic) task를 자동으로 고르지 않는다. build-logic 시험(FrontendLockfileCheckSpec — D-ADMIN-UI-002)이
// GRADLE-CHECK에서 빠지지 않게 루트 check가 직접 의존한다(ADMIN-UI-001 리뷰 M-1, harness/tests/test_module_registry.py가 등록을 검사).
tasks.register("check") {
    group = "verification"
    description = "Runs the included build-logic tests (subproject checks run through the task selector)."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
