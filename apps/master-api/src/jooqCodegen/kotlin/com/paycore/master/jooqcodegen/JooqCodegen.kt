package com.paycore.master.jooqcodegen

import com.paycore.testsupport.MySqlTestContainerConfig
import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Target
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.system.exitProcess

/**
 * 빌드 도구 전용(애플리케이션 classpath에 포함되지 않음): Flyway migration을 실제 MySQL 8.4 컨테이너에 적용하고
 * 그 live schema에서 jOOQ 코드를 생성한다. DDL 파서/H2 대신 실제 DB를 쓰는 이유는 MySQL 타입·collation·제약을
 * 운영 DB 제품과 같은 방식으로 해석하기 위해서다.
 *
 * 인자: <outputDir> <packageName> <migrationDir>...
 * 존재하지 않는 migration 디렉터리는 무시한다. MASTER 업무 schema 위치(db/migration/master)만 넘긴다 — runtime 권한 migration
 * (db/runtime-privilege/master)은 생성기 컨테이너에 권한 부여 procedure가 없어 적용하지 않는다(MASTER-DOMAIN-001 AC, §9.2).
 * 접속 정보는 일회용 Testcontainers 기본값이며 출력하지 않는다. 운영 DB/키에 연결하지 않는다.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: JooqCodegen <outputDir> <packageName> <migrationDir>...")
        exitProcess(2)
    }
    val outputDir = File(args[0])
    val packageName = args[1]
    val migrationDirs = args.drop(2).map(::File).filter(File::isDirectory)

    // 이미지 tag는 테스트와 같은 단일 상수(libs/test-support)를 쓴다(compile 시 inline).
    MySQLContainer(DockerImageName.parse(MySqlTestContainerConfig.MYSQL_IMAGE)).use { mysql ->
        mysql.start()

        if (migrationDirs.isNotEmpty()) {
            Flyway.configure()
                .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
                .locations(*migrationDirs.map { "filesystem:${it.absolutePath}" }.toTypedArray())
                .load()
                .migrate()
        }

        GenerationTool.generate(
            Configuration()
                .withLogging(Logging.WARN)
                .withJdbc(
                    Jdbc()
                        .withDriver(mysql.driverClassName)
                        .withUrl(mysql.jdbcUrl)
                        .withUser(mysql.username)
                        .withPassword(mysql.password),
                )
                .withGenerator(
                    Generator()
                        .withName("org.jooq.codegen.JavaGenerator")
                        .withDatabase(
                            Database()
                                .withName("org.jooq.meta.mysql.MySQLDatabase")
                                .withInputSchema(mysql.databaseName)
                                // 생성 코드에 컨테이너 schema 이름("test")을 박지 않는다. 런타임 연결의 기본 schema를 쓴다.
                                .withOutputSchemaToDefault(true)
                                .withExcludes("flyway_schema_history"),
                        )
                        .withGenerate(
                            Generate()
                                .withGeneratedAnnotation(false)
                                .withPojos(false)
                                .withDaos(false),
                        )
                        .withTarget(
                            Target()
                                .withPackageName(packageName)
                                .withDirectory(outputDir.absolutePath)
                                .withClean(true),
                        ),
                ),
        )
    }
}
