// 공통 Kotlin/Boot/테스트 설정: build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    // JDBC JobRepository: 재시작 상태(JobInstance/Execution/ExecutionContext)를 FINANCIAL DB에 남긴다.
    implementation(libs.spring.boot.starter.batch.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(project(":libs:test-support"))
    testImplementation(libs.spring.boot.starter.batch.jdbc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}
