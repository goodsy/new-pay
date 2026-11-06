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
    // ACQ-VIEW-001(Coordinator 결정 R-2 A): ADMIN → FINANCIAL 서명 내부 채널 전용 보안 체인 + 그 밖 기본 거부(master-api·payment-api 수신 측과 같은 구조). Boot BOM 관리 버전.
    implementation(libs.spring.boot.starter.security)
    // ACQ-VIEW-001(Coordinator 결정 R-1 A): 매입 현황 CQRS 조회(DSLContext, adapter.query). 생성 코드는 아래 generateJooqMain(Flyway 업무 schema → MySQL 8.4 컨테이너 → jOOQ).
    // Boot BOM 관리 버전(새 버전 고정 없음). 매입 판정·적체 count(C-2 JPA native)는 그대로다 — 이 의존성은 관리 조회 전용.
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.modulith.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.mysql)
    // ACQUIRING-001 배치2(2-D): 가상 매입사 SFTP 전송(클라이언트 키 인증 + host key 고정 — D-ACQ-006). Boot BOM 밖 — catalog 고정 2.20.1(Coordinator 결정 C-1).
    // 시험의 SFTP 서버(SshServer)도 같은 artifact(sshd-core)를 쓴다.
    implementation(libs.apache.sshd.sftp)
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

// FIN-DBACCOUNT-001: DB 시험 컨테이너는 로컬 개발 초기화 스크립트(docker/mysql/init)를 그대로 넣어 실제 계정·runtime role·권한 부여 procedure를 만든다
// (payment-api·master-api와 같은 방식 — FinancialDbAccounts). 스크립트가 바뀌면 시험을 다시 실행하도록 입력으로 둔다.
val mysqlInitDir = rootProject.layout.projectDirectory.dir("docker/mysql/init")

tasks.test {
    inputs.dir(mysqlInitDir).withPropertyName("mysqlInitScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("paycore.mysqlInitDir", mysqlInitDir.asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------
// jOOQ codegen(ACQ-VIEW-001 R-1 A — payment-api·master-api와 같은 방식): Flyway **업무 schema** migration(db/migration/financial)만 Testcontainers
// MySQL 8.4에 적용하고 그 live schema에서 생성한다. runtime 권한 migration(db/runtime-privilege/financial)은 생성기 컨테이너에 권한 부여 procedure가 없으므로
// 적용하지 않는다. 생성 대상은 acquisition_* 표만(관리 조회 대상 — 결제 사실·Batch metadata 표 제외). 생성 코드는 build/ 아래(commit 안 함), 생성기는 별도 source set이라
// 앱 runtime classpath에 Testcontainers/codegen이 들어가지 않는다. Docker가 필요하다.
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

val mainMigrations = layout.projectDirectory.dir("src/main/resources/db/migration/financial")

// 생성 패키지는 app 기본 패키지 아래에 두되 Spring Modulith 모듈 판정에서 제외한다(ArchitectureSpec.GENERATED_JOOQ_PACKAGE).
val mainJooqPackage = "com.paycore.financial.jooq.generated"

val generateJooqMain = tasks.register<JavaExec>("generateJooqMain") {
    group = "jooq"
    description = "Generates jOOQ classes ($mainJooqPackage) from the FINANCIAL business-schema migrations applied to a MySQL 8.4 container."
    classpath = jooqCodegen.runtimeClasspath
    mainClass = "com.paycore.financial.jooqcodegen.JooqCodegenKt"
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
