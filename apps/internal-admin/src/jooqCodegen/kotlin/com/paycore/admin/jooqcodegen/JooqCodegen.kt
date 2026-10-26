package com.paycore.admin.jooqcodegen

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
 * 빌드 도구 전용(애플리케이션 classpath에 포함되지 않음, master-api 생성기와 같은 방식): Flyway 업무 schema migration(db/migration/admin)을
 * 실제 MySQL 8.4 컨테이너에 적용하고 그 live schema에서 jOOQ 코드를 생성한다. runtime 권한 migration은 생성기 컨테이너에 procedure가 없어
 * 적용하지 않는다(§9.2). Spring Session 표(admin_session·admin_session_attributes)는 jOOQ 조회 대상이 아니므로 제외한다.
 *
 * 인자: <outputDir> <packageName> <migrationDir>... 존재하지 않는 migration 디렉터리는 무시한다.
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
                                .withExcludes("flyway_schema_history|admin_session|admin_session_attributes"),
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
