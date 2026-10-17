// 가상 인증기관 Simulator(AUTHN-001 §13, D-AUTHN-004). 공통 Kotlin/Boot/테스트 설정: build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
// PayCore 앱(apps:*)에 project 의존을 두지 않는다 — 계약은 harness golden 파일로만 공유한다(harness test_simulator_isolation).
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    // 모두 Boot 4.1.1 BOM 관리 카탈로그 항목(버전 override 없음). jOOQ 없음(CQRS 조회 요구 없음).
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(project(":libs:test-support"))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}

// Simulator 시험 컨테이너는 Simulator 전용 초기화 스크립트(docker/simulator-mysql/init)를 그대로 넣어 DB·migration/runtime 계정을 만든다
// (PayCore 공유 스크립트 docker/mysql/init는 쓰지 않는다 — AN-19). 스크립트가 바뀌면 시험을 다시 실행하도록 입력으로 둔다.
val simulatorMysqlInitDir = rootProject.layout.projectDirectory.dir("docker/simulator-mysql/init")

tasks.test {
    inputs.dir(simulatorMysqlInitDir).withPropertyName("simulatorMysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.simulatorMysqlInitDir", simulatorMysqlInitDir.asFile.absolutePath)
}
