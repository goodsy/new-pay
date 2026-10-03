plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        allWarningsAsErrors = true
    }
}

// JPA Entity는 Hibernate 프록시를 위해 open이어야 한다. Domain 모델에는 적용되지 않는다(Entity는 adapter에만 둔다).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(libs.spring.boot.starter.test) {
        // Mockito 대신 MockK/springmockk를 사용한다.
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.archunit)
}

tasks.test {
    useJUnitPlatform()
    // 테스트 0건은 성공이 아니다(HARNESS-GUIDE §7).
    failOnNoDiscoveredTests = true
}

// ---------------------------------------------------------------------------------------------
// jOOQ codegen: Flyway migration → Testcontainers MySQL 8.4 → jOOQ 생성(live schema).
// 생성 코드는 build/ 아래에만 두고 commit하지 않는다. 생성기는 별도 source set(jooqCodegen)이라
// 애플리케이션 runtime classpath에 Testcontainers/codegen이 들어가지 않는다. Docker가 필요하다.
// ---------------------------------------------------------------------------------------------
val jooqCodegen: SourceSet = sourceSets.create("jooqCodegen")

dependencies {
    "jooqCodegenImplementation"(platform(libs.spring.boot.bom))
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

val generateJooqMain = registerJooqGenerate(
    taskName = "generateJooqMain",
    migrationDirs = listOf(mainMigrations),
    packageName = "com.paycore.payment.jooq.generated",
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
