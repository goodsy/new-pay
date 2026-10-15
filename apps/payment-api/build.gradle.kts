// 공통 Kotlin/Boot/테스트 설정(kotlin jvm·spring·jpa, Boot plugin, toolchain 25, 경고=오류, JPA allOpen, BOM, JUnit Platform,
// 테스트 0건 실패, Mockito 제외): build-logic/src/main/kotlin/paycore.kotlin-app.gradle.kts
plugins {
    id("paycore.kotlin-app")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    // 가맹점 공개 API 인증(D-CPA-001 Bearer API 키). Boot 4.1.1 BOM 관리 버전(카탈로그 기존 항목 — internal-admin과 같다).
    implementation(libs.spring.boot.starter.security)
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
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.archunit)
    // 자동 Fixture(테스트 전용, Test Fixture Policy). 프로젝트 Fixture(src/test) 뒤에만 쓴다. Instancio는 쓰지 않는다.
    // Fixture Monkey는 값 생성에 jqwik(1.7.3) 구현 클래스를 쓰므로 jqwik-engine 모듈은 classpath에 남긴다(빼면 NoClassDefFoundError —
    // 2026-10-07 확인). 단 jqwik의 JUnit Platform 엔진은 아래 test 설정에서 실행 대상에서 뺀다.
    testImplementation(libs.fixture.monkey.kotlin)
}

tasks.test {
    useJUnitPlatform {
        // jqwik 엔진은 시험을 갖지 않으며(이 저장소의 시험은 Kotest), 실행되면 작업 트리에 .jqwik-database를 만들어 source fingerprint를
        // 바꿀 수 있다. Kotest 엔진 실행·failOnNoDiscoveredTests(paycore.kotlin-base)는 그대로다.
        excludeEngines("jqwik")
    }
}

// ---------------------------------------------------------------------------------------------
// jOOQ codegen: Flyway migration → Testcontainers MySQL 8.4 → jOOQ 생성(live schema).
// 생성 코드는 build/ 아래에만 두고 commit하지 않는다. 생성기는 별도 source set(jooqCodegen)이라
// 애플리케이션 runtime classpath에 Testcontainers/codegen이 들어가지 않는다. Docker가 필요하다.
// ---------------------------------------------------------------------------------------------
val jooqCodegen: SourceSet = sourceSets.create("jooqCodegen")

dependencies {
    "jooqCodegenImplementation"(platform(libs.spring.boot.bom))
    // MySQL 이미지 tag의 단일 기준(MySqlTestContainerConfig.MYSQL_IMAGE)을 컴파일 시점 상수로만 읽는다.
    // const val은 호출 측에 inline되므로 생성기 runtime classpath에는 test-support/그 의존성이 들어가지 않는다.
    "jooqCodegenCompileOnly"(project(":libs:test-support")) { isTransitive = false }
    "jooqCodegenImplementation"(libs.jooq.codegen)
    "jooqCodegenImplementation"(libs.jooq.meta)
    "jooqCodegenImplementation"(libs.flyway.mysql)
    "jooqCodegenImplementation"(libs.testcontainers.mysql)
    "jooqCodegenRuntimeOnly"(libs.mysql.connector.j)
    "jooqCodegenRuntimeOnly"(libs.slf4j.simple)
}

val mainMigrations = layout.projectDirectory.dir("src/main/resources/db/migration/payment")
val testMigrations = layout.projectDirectory.dir("src/test/resources/db/testmigration/payment")

fun registerJooqGenerate(
    taskName: String,
    migrationDirs: List<Directory>,
    packageName: String,
    outputName: String,
): TaskProvider<JavaExec> = tasks.register<JavaExec>(taskName) {
    group = "jooq"
    description = "Generates jOOQ classes ($packageName) from Flyway migrations applied to a MySQL 8.4 container."
    classpath = jooqCodegen.runtimeClasspath
    mainClass = "com.paycore.payment.jooqcodegen.JooqCodegenKt"
    systemProperty("org.jooq.no-logo", "true")
    systemProperty("org.jooq.no-tips", "true")
    val outputDir = layout.buildDirectory.dir("generated-sources/jooq/$outputName")
    // migration 내용·패키지·생성기 classpath(JavaExec 기본 입력)가 같으면 up-to-date로 건너뛴다.
    inputs.files(migrationDirs).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("packageName", packageName)
    outputs.dir(outputDir).withPropertyName("generatedSources")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(outputDir.get().asFile.absolutePath, packageName) + migrationDirs.map { it.asFile.absolutePath }
        },
    )
}

// 생성 패키지는 app 기본 패키지 아래에 두되 Spring Modulith 모듈 판정에서 제외한다(ArchitectureSpec.GENERATED_JOOQ_PACKAGE,
// PayCoreArchitectureRules.generatedCode()). adapter.query만 접근할 수 있다. 값이 어긋나지 않게 테스트에 system property로 넘겨 비교한다.
val mainJooqPackage = "com.paycore.payment.jooq.generated"

val generateJooqMain = registerJooqGenerate(
    taskName = "generateJooqMain",
    migrationDirs = listOf(mainMigrations),
    packageName = mainJooqPackage,
    outputName = "main",
)

// 테스트용: main + test-only migration(tech_smoke_* 등). 테스트 source set에만 들어간다.
val generateJooqTest = registerJooqGenerate(
    taskName = "generateJooqTest",
    migrationDirs = listOf(mainMigrations, testMigrations),
    packageName = "com.paycore.payment.techsmoke.jooq",
    outputName = "test",
)

sourceSets {
    main { java.srcDir(generateJooqMain) }
    test { java.srcDir(generateJooqTest) }
}

tasks.compileKotlin { dependsOn(generateJooqMain) }
tasks.compileTestKotlin { dependsOn(generateJooqTest) }

tasks.test { systemProperty("paycore.jooq.mainPackage", mainJooqPackage) }

// DB 시험 컨테이너는 로컬 개발 초기화 스크립트(docker/mysql/init)를 그대로 넣어 실제 계정·runtime role·권한 부여 procedure를 만든다
// (PAYMENT-DBACCOUNT-001, master-api와 같은 방식). 스크립트가 바뀌면 시험을 다시 실행하도록 입력으로 둔다.
// jOOQ 생성기(generateJooq*)는 이 스크립트를 쓰지 않는다: 업무 schema migration만 적용하고 runtime 권한 migration(db/runtime-privilege)은
// 별도 위치라 적용 대상이 아니다.
val mysqlInitDir = rootProject.layout.projectDirectory.dir("docker/mysql/init")

tasks.test {
    inputs.dir(mysqlInitDir).withPropertyName("mysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.mysqlInitDir", mysqlInitDir.asFile.absolutePath)
}
