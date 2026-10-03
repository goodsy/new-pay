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

    // JDBC JobRepository: 재시작 상태(JobInstance/Execution/ExecutionContext)를 FINANCIAL DB에 남긴다.
    implementation(libs.spring.boot.starter.batch.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(libs.spring.boot.starter.test) {
        // Mockito 대신 MockK/springmockk를 사용한다.
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.starter.batch.jdbc.test) {
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.mysql)
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
