// 공통 Kotlin/Boot/테스트 설정(kotlin jvm·spring·jpa, Boot plugin, toolchain 25, 경고=오류, JPA allOpen, BOM, JUnit Platform,
// 테스트 0건 실패, Mockito 제외, test JVM 메모리 상한): build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
// MASTER DB만 소유한다(D-CPC-006, MASTER-BOOT-001). PAYMENT/FINANCIAL 접속 정보·Entity·SQL을 두지 않는다.
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    // 복잡한 CQRS 조회용(DSLContext). 생성 코드(codegen)는 첫 MASTER 업무 테이블이 생기는 MASTER-DOMAIN-001에서 payment-api와 같은
    // 방식(Flyway migration → MySQL 8.4 컨테이너 → jOOQ 생성)으로 추가한다. 지금은 생성할 테이블이 없다(MASTER-BOOT-001 결정).
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(project(":libs:test-support"))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}

// 로컬 개발 DB 초기화 스크립트(docker/mysql/init)를 시험 컨테이너에 그대로 넣어 계정·권한 분리를 실제 MySQL로 확인한다
// (MasterDbTestConfig). 스크립트가 바뀌면 시험이 다시 실행되도록 입력으로 선언한다.
val mysqlInitDir = rootProject.layout.projectDirectory.dir("docker/mysql/init")

tasks.test {
    inputs.dir(mysqlInitDir).withPropertyName("mysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.mysqlInitDir", mysqlInitDir.asFile.absolutePath)
}
