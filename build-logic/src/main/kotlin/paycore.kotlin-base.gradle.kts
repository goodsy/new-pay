// 모든 PayCore Kotlin 모듈(apps/*, libs/*)의 공통 기반. 직접 적용하지 않고 paycore.kotlin-app / paycore.kotlin-library를 쓴다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// precompiled script plugin에는 type-safe `libs` accessor가 생성되지 않는다. 적용 대상 build의 catalog("libs")를 이름으로 읽는다.
// 따라서 버전은 언제나 gradle/libs.versions.toml 하나에서 온다.
val catalog = the<VersionCatalogsExtension>().named("libs")
fun catalogLibrary(alias: String) =
    catalog.findLibrary(alias).orElseThrow { IllegalStateException("version catalog alias '$alias' is missing") }

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        allWarningsAsErrors = true
    }
}

dependencies {
    // Boot BOM과 Modulith BOM만 버전을 결정한다(override 없음, 보안 patch는 루트 build.gradle.kts).
    "implementation"(platform(catalogLibrary("spring-boot-bom")))
    "implementation"(platform(catalogLibrary("spring-modulith-bom")))
    "testImplementation"(platform(catalogLibrary("spring-boot-bom")))
    "testImplementation"(platform(catalogLibrary("spring-modulith-bom")))
}

// Mockito 대신 MockK/springmockk를 쓴다. starter-test뿐 아니라 webmvc-test/security-test/batch-jdbc-test 등
// 모든 테스트 의존성 경로에서 Mockito를 뺀다(의존성마다 exclude를 반복하지 않는다).
configurations.matching { it.name == "testCompileClasspath" || it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.mockito")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 테스트 0건은 성공이 아니다(HARNESS-GUIDE §7).
    failOnNoDiscoveredTests = true
}
