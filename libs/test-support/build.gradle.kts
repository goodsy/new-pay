// 공유 테스트 지원 라이브러리. apps/*가 testImplementation으로만 쓴다(운영 runtime·SBOM 대상 아님).
// 공통 Kotlin 설정: build-logic/src/main/kotlin/paycore.kotlin-library.gradle.kts
plugins {
    id("paycore.kotlin-library")
}

dependencies {
    // 모든 app 테스트가 쓰는 부분: 아키텍처 규칙(ArchUnit)과 Kotest 프로젝트 설정(AbstractProjectConfig + SpringExtension).
    // kotest-extensions-spring은 Kotest engine을 runtime으로만 노출하므로 runner(engine 포함)를 api로 둔다.
    api(libs.archunit)
    api(libs.kotest.runner.junit5)
    api(libs.kotest.extensions.spring)

    // MySQL Testcontainers 설정은 DB를 소유한 app만 쓴다. compileOnly로 두어 DB 없는 app(internal-admin)의
    // 테스트 classpath에 Testcontainers/JDBC가 끌려 들어가지 않게 한다. 사용하는 app이 직접 선언한다.
    compileOnly(libs.spring.boot.test)
    compileOnly(libs.spring.boot.testcontainers)
    compileOnly(libs.testcontainers.mysql)

    testImplementation(libs.kotest.assertions.core)
    // 아키텍처 규칙 meta-test fixture 컴파일용(위반/준수 예제 클래스가 실제 JPA/Spring web/jOOQ 타입을 참조해야 한다).
    testImplementation(libs.jakarta.persistence.api)
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.web)
    testImplementation(libs.jooq)
    testImplementation(libs.spring.data.commons)
    testImplementation(libs.spring.batch.core)
    // 기반 패키지 root·domain 허용 목록·Modulith named interface 위치 규칙 fixture용(@SpringBootApplication, jakarta.transaction,
    // jakarta.validation, slf4j, @NamedInterface).
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.jakarta.transaction.api)
    testImplementation(libs.jakarta.validation.api)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.spring.modulith.api)
    // round3: application 허용 목록(@Transactional) 준수 fixture와 영속 기술 위치 규칙 fixture(JdbcTemplate, RowMapper).
    // 이전에는 spring-batch-core 전이 의존으로만 있었다. fixture가 직접 쓰므로 명시한다(버전은 Boot BOM).
    testImplementation(libs.spring.tx)
    testImplementation(libs.spring.jdbc)
}
