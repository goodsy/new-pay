// 공통 Kotlin/Boot/테스트 설정: build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    // JDBC JobRepository: 재시작 상태(JobInstance/Execution/ExecutionContext)를 FINANCIAL DB에 남긴다.
    implementation(libs.spring.boot.starter.batch.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    // FIN-FACT-RELAY-001: PAYMENT 결제 사실 내부 Inbox(서명 HTTP push 수신 — D-ACQ-001)·JPA 투영·운영 지표. 모두 Boot 4.1.1 BOM 관리 버전(카탈로그 기존 항목 —
    // payment-api와 같은 조합, 새 버전·override 없음). Kafka/Redis는 쓰지 않는다(ADR-001 — 전달 안전성은 Outbox·Inbox·effect key UNIQUE·로컬 TX가 지킨다).
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
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
