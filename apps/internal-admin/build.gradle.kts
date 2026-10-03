plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))

    // DB 의존성 없음(아키텍처 01: internal-admin 허용 DB 없음).
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test) {
        // Mockito 대신 MockK/springmockk를 사용한다.
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.starter.webmvc.test) {
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.starter.security.test) {
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}

tasks.test {
    useJUnitPlatform()
    // 테스트 0건은 성공이 아니다(HARNESS-GUIDE §7).
    failOnNoDiscoveredTests = true
}
