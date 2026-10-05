// 공통 Kotlin/Boot/테스트 설정: build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    // DB 의존성 없음(아키텍처 01: internal-admin 허용 DB 없음).
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)

    // test-support의 MySQL/Testcontainers 부분은 compileOnly라 여기 테스트 classpath에 DB 의존성이 들어오지 않는다.
    testImplementation(project(":libs:test-support"))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}
