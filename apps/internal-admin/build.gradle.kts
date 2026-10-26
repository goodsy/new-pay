// 공통 Kotlin/Boot/테스트 설정: build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
// ADMIN DB(`paycore_admin`)만 소유한다(D-ADMIN-005 — baseline 01 23행 편차, ADMIN-BOOT-001 §4.2). MASTER/PAYMENT/FINANCIAL 접속 정보·Entity·SQL을 두지 않는다.
plugins {
    id("paycore.kotlin-app")
    // React SPA(frontend/) 빌드·시험·공급망 검사(ADMIN-BOOT-001 §4.11). 산출물은 static/으로 같은 출처 서빙.
    id("paycore.frontend")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    // Thymeleaf 제거(D-ADMIN-008 React SPA, ADMIN-BOOT-001 §3.3 F-3). 화면은 frontend/ 정적 번들.
    implementation(libs.spring.boot.starter.security)
    // ADMIN 도메인 영속화(JPA — adapter.persistence만), CQRS 조회(jOOQ — adapter.query만), schema·권한 migration(Flyway — migration 계정).
    // 버전은 모두 Boot 4.1.1 BOM(catalog 기존 항목 — master-api·payment-api와 같다).
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.mysql)
    // health만 노출. 웹 포트에서는 SecurityConfig 허용 목록 밖이라 익명·인증 모두 거부(별도 management 포트는 D-15, S8).
    implementation(libs.spring.boot.starter.actuator)
    // 관리자 세션(D-ADMIN-004 JDBC 세션 — Redis 도입 없음). 표 admin_session·admin_session_attributes(V3), 질의·문자열 값 변환은 security.session(S4).
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(project(":libs:test-support"))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.archunit)
}

// 로컬 개발 DB 초기화 스크립트(docker/mysql/init)를 시험 컨테이너에 그대로 넣어 계정·권한을 실제 MySQL로 확인한다(AdminDbAccounts).
// 스크립트가 바뀌면 시험이 다시 실행되도록 입력으로 선언한다.
val mysqlInitDir = rootProject.layout.projectDirectory.dir("docker/mysql/init")

tasks.test {
    inputs.dir(mysqlInitDir).withPropertyName("mysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.mysqlInitDir", mysqlInitDir.asFile.absolutePath)
    // 시험 컨텍스트마다 MySQL 컨테이너(bean)가 하나씩 뜬다. 기본 캐시(32개)면 S5에서 컨텍스트 종류가 늘어 WSL VM 메모리가 바닥나 시험이 멈췄다(실측 2026-10-25) —
    // 동시에 살아 있는 컨텍스트를 4개로 묶어, 밀려난 컨텍스트는 닫히고(컨테이너 bean도 close → 중지) 메모리를 돌려준다.
    systemProperty("spring.test.context.cache.maxSize", "4")
}

// ---------------------------------------------------------------------------------------------
// jOOQ codegen(master-api와 같은 방식): Flyway **업무 schema** migration(db/migration/admin)만 Testcontainers MySQL 8.4에 적용하고 그 live
// schema에서 생성한다. runtime 권한 migration(db/runtime-privilege/admin)은 생성기 컨테이너에 권한 부여 procedure가 없으므로 적용하지 않는다
// (§9.2 위치 분리). Spring Session 표는 jOOQ 조회 대상이 아니므로 생성하지 않는다. 생성 코드는 build/ 아래(commit 안 함), 생성기는 별도
// source set이라 앱 runtime classpath에 Testcontainers/codegen이 들어가지 않는다. Docker가 필요하다.
// ---------------------------------------------------------------------------------------------
val jooqCodegen: SourceSet = sourceSets.create("jooqCodegen")

dependencies {
    "jooqCodegenImplementation"(platform(libs.spring.boot.bom))
    "jooqCodegenCompileOnly"(project(":libs:test-support")) { isTransitive = false }
    "jooqCodegenImplementation"(libs.jooq.codegen)
    "jooqCodegenImplementation"(libs.jooq.meta)
    "jooqCodegenImplementation"(libs.flyway.mysql)
    "jooqCodegenImplementation"(libs.testcontainers.mysql)
    "jooqCodegenRuntimeOnly"(libs.mysql.connector.j)
    "jooqCodegenRuntimeOnly"(libs.slf4j.simple)
}

val mainMigrations = layout.projectDirectory.dir("src/main/resources/db/migration/admin")

// 생성 패키지는 app 기본 패키지 아래에 두되 Spring Modulith 모듈 판정·계층 규칙에서 제외한다(ArchitectureSpec.GENERATED_JOOQ_PACKAGE).
val mainJooqPackage = "com.paycore.admin.jooq.generated"

val generateJooqMain = tasks.register<JavaExec>("generateJooqMain") {
    group = "jooq"
    description = "Generates jOOQ classes ($mainJooqPackage) from the ADMIN business-schema migrations applied to a MySQL 8.4 container."
    classpath = jooqCodegen.runtimeClasspath
    mainClass = "com.paycore.admin.jooqcodegen.JooqCodegenKt"
    systemProperty("org.jooq.no-logo", "true")
    systemProperty("org.jooq.no-tips", "true")
    val outputDir = layout.buildDirectory.dir("generated-sources/jooq/main")
    inputs.dir(mainMigrations).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("packageName", mainJooqPackage)
    outputs.dir(outputDir).withPropertyName("generatedSources")
    argumentProviders.add(
        CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath, mainJooqPackage, mainMigrations.asFile.absolutePath) },
    )
}

sourceSets {
    main { java.srcDir(generateJooqMain) }
}

tasks.compileKotlin { dependsOn(generateJooqMain) }

tasks.test { systemProperty("paycore.jooq.mainPackage", mainJooqPackage) }
