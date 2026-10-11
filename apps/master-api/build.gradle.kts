// 공통 Kotlin/Boot/테스트 설정(kotlin jvm·spring·jpa, Boot plugin, toolchain 25, 경고=오류, JPA allOpen, BOM, JUnit Platform,
// 테스트 0건 실패, Mockito 제외, test JVM 메모리 상한): build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
// MASTER DB만 소유한다(D-CPC-006, MASTER-BOOT-001). PAYMENT/FINANCIAL 접속 정보·Entity·SQL을 두지 않는다.
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    // 복잡한 CQRS 조회용(DSLContext, adapter.query). 생성 코드는 아래 generateJooqMain(Flyway 업무 schema → MySQL 8.4 컨테이너 → jOOQ).
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
    // 자동 Fixture(테스트 전용, Test Fixture Policy — MASTER-DOMAIN-001). 프로젝트 Fixture(src/test) 뒤에만 쓴다. Instancio는 쓰지 않는다.
    // 버전은 catalog(payment-api와 같은 값, Boot BOM 밖이라 catalog 고정). Fixture Monkey는 jqwik 구현 클래스를 쓰므로 jqwik-engine은 classpath에
    // 남기고 JUnit Platform 엔진만 실행 대상에서 뺀다(payment-api와 같은 이유).
    testImplementation(libs.fixture.monkey.kotlin)
}

// 로컬 개발 DB 초기화 스크립트(docker/mysql/init)를 시험 컨테이너에 그대로 넣어 계정·권한 분리를 실제 MySQL로 확인한다
// (MasterDbTestConfig). 스크립트가 바뀌면 시험이 다시 실행되도록 입력으로 선언한다.
val mysqlInitDir = rootProject.layout.projectDirectory.dir("docker/mysql/init")

tasks.test {
    useJUnitPlatform {
        // jqwik 엔진은 시험을 갖지 않으며 실행되면 작업 트리에 .jqwik-database를 만들어 source fingerprint를 바꿀 수 있다.
        excludeEngines("jqwik")
    }
    inputs.dir(mysqlInitDir).withPropertyName("mysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.mysqlInitDir", mysqlInitDir.asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------
// jOOQ codegen(MASTER-DOMAIN-001, payment-api와 같은 방식): Flyway **업무 schema** migration(db/migration/master)만 Testcontainers
// MySQL 8.4에 적용하고 그 live schema에서 생성한다. runtime 권한 migration(db/runtime-privilege/master)은 생성기 컨테이너에 권한 부여
// procedure가 없으므로 적용하지 않는다(위치 분리 유지 — AC). 생성 코드는 build/ 아래(commit 안 함), 생성기는 별도 source set이라 앱 runtime
// classpath에 Testcontainers/codegen이 들어가지 않는다. Docker가 필요하다.
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

val mainMigrations = layout.projectDirectory.dir("src/main/resources/db/migration/master")

// 생성 패키지는 app 기본 패키지 아래에 두되 Spring Modulith 모듈 판정에서 제외한다(ArchitectureSpec.GENERATED_JOOQ_PACKAGE).
val mainJooqPackage = "com.paycore.master.jooq.generated"

val generateJooqMain = tasks.register<JavaExec>("generateJooqMain") {
    group = "jooq"
    description = "Generates jOOQ classes ($mainJooqPackage) from the MASTER business-schema migrations applied to a MySQL 8.4 container."
    classpath = jooqCodegen.runtimeClasspath
    mainClass = "com.paycore.master.jooqcodegen.JooqCodegenKt"
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
