import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
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

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            securityPatchVersions[requested.group]?.let { patched ->
                useVersion(patched)
                because("security patch override over Spring Boot BOM (TECH-BASELINE §1.1)")
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
